#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "seedfilter.h"
#include "cubiomes/finders.h"
#include "cubiomes/util.h"

#define MC MC_26_3
#define SURFACE_Y 256 // above terrain the depth noise selects the surface biome, never a cave biome

static int near(int x, int z, int sx, int sz, int r)
{
    long long dx = x - sx, dz = z - sz;
    return dx * dx + dz * dz <= (long long)r * r;
}

#define PI 3.14159265358979323846

static uint64_t dist2(int x, int z) { return (uint64_t)((int64_t)x * x) + (uint64_t)((int64_t)z * z); }

// squared distance of a climate value (x10000) outside the spawn target interval [lo, hi]
static uint64_t pen2(int64_t v, int64_t lo, int64_t hi)
{
    int64_t q = v > hi ? v - hi : v < lo ? lo - v : 0;
    return (uint64_t)(q * q);
}

static int64_t climate(const Generator *g, int np, double px, double pz)
{
    float v = (float)sampleDoublePerlin(&g->bn.climate[np], px, 0, pz); // same float rounding as sampleBiomeNoise
    return (int64_t)(10000.0F * v);
}

// cubiomes' calcFitness for MC > 1.21.1 (climate distance from the spawn target * 2048^2 + distance^2 from 0,0),
// sampled like sampleBiomeNoise(SAMPLE_NO_DEPTH). Continentalness and weirdness come first: once they alone can't
// beat `best`, the returned lower bound (>= best) is enough and temperature, humidity and erosion are skipped.
static uint64_t spawnFitness(const Generator *g, int x, int z, uint64_t best)
{
    int qx = x >> 2, qz = z >> 2;
    double px = qx + sampleDoublePerlin(&g->bn.climate[NP_SHIFT], qx, 0, qz) * 4.0;
    double pz = qz + sampleDoublePerlin(&g->bn.climate[NP_SHIFT], qz, qx, 0) * 4.0;
    int64_t c = climate(g, NP_CONTINENTALNESS, px, pz), w = climate(g, NP_WEIRDNESS, px, pz);
    uint64_t wl = pen2(w, -10000, -1600), wh = pen2(w, 1600, 10000);
    uint64_t ds = pen2(c, -1100, 10000) + (wl <= wh ? wl : wh); // depth is 0 with SAMPLE_NO_DEPTH: no penalty
    uint64_t f = ds * (2048ULL * 2048ULL) + dist2(x, z);
    if (f >= best) return f;
    ds += pen2(climate(g, NP_TEMPERATURE, px, pz), -10000, 10000) + pen2(climate(g, NP_HUMIDITY, px, pz), -10000, 10000)
        + pen2(climate(g, NP_EROSION, px, pz), -10000, 10000);
    return ds * (2048ULL * 2048ULL) + dist2(x, z);
}

// cubiomes' findFittest, pruned: fitness >= x^2+z^2, so a point no closer to 0,0 than the best fitness so far can
// never win and its (expensive) noise is skipped. Same result, ~14x fewer samples.
static void fittest(const Generator *g, Pos *pos, uint64_t *best, double maxrad, double step)
{
    Pos p = *pos;
    for (double rad = step; rad <= maxrad; rad += step)
        for (double ang = 0; ang <= PI * 2; ang += step / rad) {
            int x = p.x + (int)(sin(ang) * rad), z = p.z + (int)(cos(ang) * rad);
            if (dist2(x, z) >= *best) continue;
            uint64_t f = spawnFitness(g, x, z, *best);
            if (f < *best) { pos->x = x; pos->z = z; *best = f; }
        }
}

// First (coarse) pass of the spawn search: cheap, and its best fitness already bounds where spawn can end up.
static Pos spawnCoarse(const Generator *g, uint64_t *best)
{
    Pos spawn = {0, 0};
    *best = spawnFitness(g, 0, 0, UINT64_MAX);
    fittest(g, &spawn, best, 2048.0, 512.0);
    return spawn;
}

// Rest of cubiomes' getSpawn for MC > 1.17 (fine pass, then the ground search); test_seedfilter.c checks the
// pair matches getSpawn
static Pos spawnFinish(const Generator *g, Pos spawn, uint64_t best)
{
    fittest(g, &spawn, &best, 512.0, 32.0);
    spawn.x &= ~15;
    spawn.z &= ~15;

    int j = 0, k = 0, u = 0, v = -1;
    for (int i = 0; i < 121; i++) {
        if (j >= -5 && j <= 5 && k >= -5 && k <= 5) {
            int cx0 = (spawn.x & ~15) + j * 16, cz0 = (spawn.z & ~15) + k * 16;
            for (int ii = 0; ii < 4; ii++)
                for (int jj = 0; jj < 4; jj++) {
                    float y;
                    int id, x = cx0 + ii * 4, z = cz0 + jj * 4;
                    mapApproxHeight(&y, &id, g, NULL, x >> 2, z >> 2, 1, 1); // 1.18+ overworld ignores SurfaceNoise
                    if (y > 63 || id == frozen_ocean || id == deep_frozen_ocean || id == frozen_river)
                        return (Pos){x, z};
                }
        }
        if (j == k || (j < 0 && j == -k) || (j > 0 && j == 1 - k)) {
            int tmp = u;
            u = -v;
            v = tmp;
        }
        j += u;
        k += v;
    }
    spawn.x = (spawn.x & ~15) + 8;
    spawn.z = (spawn.z & ~15) + 8;
    return spawn;
}

static Pos spawnOf(const Generator *g)
{
    uint64_t best;
    Pos p = spawnCoarse(g, &best);
    return spawnFinish(g, p, best);
}

// variant >= 0 also requires that start piece (bastion type); -1 = any
static int structureNear(Generator *g, int type, int sx, int sz, int r, int variant)
{
    if (type == Stronghold) {
        // first ring holds 3 strongholds 1280-2816 blocks out; max rule distance is 3000
        StrongholdIter sh;
        initFirstStronghold(&sh, MC, g->seed & 0xFFFFFFFFFFFFULL);
        for (int i = 0; i < 3; i++) {
            // the biome snap moves a stronghold at most ~112 blocks; skip it (g = NULL) when it can't reach the circle
            nextStronghold(&sh, near(sh.nextapprox.x, sh.nextapprox.z, sx, sz, r + 160) ? g : NULL);
            if (near(sh.pos.x, sh.pos.z, sx, sz, r)) return 1;
        }
        return 0;
    }
    StructureConfig sc;
    if (!getStructureConfig(type, MC, &sc)) return 0;
    int size = sc.regionSize * 16;
    for (int rx = floordiv(sx - r, size); rx <= floordiv(sx + r, size); rx++)
        for (int rz = floordiv(sz - r, size); rz <= floordiv(sz + r, size); rz++) {
            Pos p;
            if (getStructurePos(type, MC, g->seed, rx, rz, &p)
                && near(p.x, p.z, sx, sz, r)
                && isViableStructurePos(type, g, p.x, p.z, 0)) {
                if (variant < 0) return 1;
                StructureVariant sv;
                getVariant(&sv, type, MC, g->seed, p.x, p.z, -1);
                if (sv.start == variant) return 1;
            }
        }
    return 0;
}

#define CELL 16    // flood-fill step in blocks
#define PATCH_R 64 // cells each way: patches are measured within ±1024 blocks
#define NEAR_STEP 32 // nearby-biome sample spacing; a patch narrower than this can be missed

static int surfaceBiome(Generator *g, int x, int z)
{
    return getBiomeAt(g, 4, x >> 2, SURFACE_Y >> 2, z >> 2);
}

static int sizeClass(long long area)
{
    return area > 600LL * 600 ? SF_SIZE_LARGE : area >= 250LL * 250 ? SF_SIZE_MEDIUM : SF_SIZE_SMALL;
}

// 4-neighbour flood fill of `biome` around spawn in CELL steps; stops once the patch is known to be large.
static int patchSize(Generator *g, int sx, int sz, int biome)
{
    // spawn's block biome can differ from its 4x4 cell's; start from the first cell within 4 blocks that matches
    static const int O[9][2] = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}, {4, 4}, {4, -4}, {-4, 4}, {-4, -4}};
    int k = 0;
    while (k < 9 && surfaceBiome(g, sx + O[k][0], sz + O[k][1]) != biome) k++;
    if (k == 9) return SF_SIZE_SMALL;
    sx += O[k][0];
    sz += O[k][1];
    const int W = 2 * PATCH_R + 1;
    unsigned char *seen = calloc((size_t)W * W, 1);
    int *queue = malloc(sizeof(int) * (size_t)W * W);
    if (!seen || !queue) { free(seen); free(queue); return SF_SIZE_SMALL; }
    static const int D[4][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    int head = 0, tail = 0, cells = 0, start = PATCH_R * W + PATCH_R;
    seen[start] = 1;
    queue[tail++] = start;
    while (head < tail && (long long)cells * CELL * CELL <= 600LL * 600) {
        int i = queue[head++];
        int cx = i % W - PATCH_R, cz = i / W - PATCH_R;
        if (surfaceBiome(g, sx + cx * CELL, sz + cz * CELL) != biome) continue;
        cells++;
        for (int k = 0; k < 4; k++) {
            int nx = cx + D[k][0], nz = cz + D[k][1];
            if (nx < -PATCH_R || nx > PATCH_R || nz < -PATCH_R || nz > PATCH_R) continue;
            int j = (nz + PATCH_R) * W + (nx + PATCH_R);
            if (!seen[j]) { seen[j] = 1; queue[tail++] = j; }
        }
    }
    free(seen);
    free(queue);
    return sizeClass((long long)cells * CELL * CELL);
}

static int probe(Generator *g, int biome, int sx, int sz, int r, int i, int j)
{
    int x = sx + i * NEAR_STEP, z = sz + j * NEAR_STEP;
    return near(x, z, sx, sz, r) && surfaceBiome(g, x, z) == biome;
}

// Rings outward from spawn so close matches return early.
static int biomeNear(Generator *g, int biome, int sx, int sz, int r)
{
    for (int ring = 0; ring <= r / NEAR_STEP; ring++) {
        for (int i = -ring; i <= ring; i++)
            if (probe(g, biome, sx, sz, r, i, -ring) || (ring && probe(g, biome, sx, sz, r, i, ring))) return 1;
        for (int j = -ring + 1; j <= ring - 1; j++)
            if (probe(g, biome, sx, sz, r, -ring, j) || probe(g, biome, sx, sz, r, ring, j)) return 1;
    }
    return 0;
}

static int netherBiomeAt(Generator *gn, int x, int z)
{
    return getBiomeAt(gn, 1, x, 64, z); // nether biomes do not vary with height
}

// Position-only (no biome check, so never stricter than structureNear): any start of `type` within r of 0,0?
static int structureMaybe(int type, uint64_t seed, int r)
{
    StructureConfig sc;
    if (!getStructureConfig(type, MC, &sc)) return 1; // unknown: can't rule it out
    int size = sc.regionSize * 16;
    for (int rx = floordiv(-r, size); rx <= floordiv(r, size); rx++)
        for (int rz = floordiv(-r, size); rz <= floordiv(r, size); rz++) {
            Pos p;
            if (getStructurePos(type, MC, seed, rx, rz, &p) && near(p.x, p.z, 0, 0, r)) return 1;
        }
    return 0;
}

// Exact reject between the cheap coarse spawn pass and the costly fine one. Fitness >= distance^2 from 0,0 and the
// search only keeps lower fitness, so the final fittest point is within sqrt(best) of 0,0 (and at most 2048 + 512
// out); the chunk snap and the ±5-chunk ground search then move spawn < 136 blocks. A required Nether structure with
// no start within that reach / 8 plus its distance can't be near the arrival point. Overworld structures aren't
// checked: the reach (~500 blocks) almost always contains one, so it measured no faster (Nether: 3x).
static int couldMatch(const Generator *g, const SfFilter *f, uint64_t best)
{
    if (f->fortressDist <= 0 && f->bastionDist <= 0) return 1;
    double fit = sqrt((double)best);
    int reach = (fit < 2560 ? (int)fit + 1 : 2560) + 136;
    int netherReach = reach / 8 + 3; // spawn / 8 is floored per axis
    if (f->fortressDist > 0 && !structureMaybe(Fortress, g->seed, netherReach + f->fortressDist)) return 0;
    if (f->bastionDist > 0 && !structureMaybe(Bastion, g->seed, netherReach + f->bastionDist)) return 0;
    return 1;
}

static int precheck = 1; // the self-test turns it off to prove it never drops a match

// gn: a second generator used for the Nether conditions, applied only once everything else matched
static int matches(Generator *g, Generator *gn, const SfFilter *f, uint64_t seed)
{
    applySeed(g, DIM_OVERWORLD, seed);
    uint64_t best;
    Pos s = spawnCoarse(g, &best);
    if (precheck && !couldMatch(g, f, best)) return 0;
    s = spawnFinish(g, s, best);
    int b = -1;
    if (f->spawnBiome >= 0 || f->spawnSize != SF_SIZE_ANY) b = getBiomeAt(g, 1, s.x, SURFACE_Y, s.z);
    if (f->spawnBiome >= 0 && b != f->spawnBiome) return 0;
    for (int i = 0; i < f->ruleCount; i++)
        if (!structureNear(g, f->ruleStructure[i], s.x, s.z, f->ruleMaxDist[i], -1)) return 0;
    if (f->spawnSize != SF_SIZE_ANY && patchSize(g, s.x, s.z, b) != f->spawnSize) return 0;
    for (int i = 0; i < f->nearCount; i++)
        if (!biomeNear(g, f->nearBiome[i], s.x, s.z, f->nearMaxDist[i])) return 0;
    if (f->netherBiome < 0 && f->fortressDist <= 0 && f->bastionDist <= 0) return 1;
    applySeed(gn, DIM_NETHER, seed);
    int nx = floordiv(s.x, 8), nz = floordiv(s.z, 8);
    if (f->netherBiome >= 0 && netherBiomeAt(gn, nx, nz) != f->netherBiome) return 0;
    if (f->fortressDist > 0 && !structureNear(gn, Fortress, nx, nz, f->fortressDist, -1)) return 0;
    if (f->bastionDist > 0 && !structureNear(gn, Bastion, nx, nz, f->bastionDist, f->bastionType)) return 0;
    return 1;
}

SF_API int sf_search(const SfFilter *f, uint64_t start, int count, const volatile int32_t *stop, uint64_t *out)
{
    Generator *g = malloc(2 * sizeof *g); // [0] overworld, [1] nether; large structs, kept off the thread stack
    if (!g) return 0;
    setupGenerator(&g[0], MC, f->largeBiomes ? LARGE_BIOMES : 0);
    setupGenerator(&g[1], MC, 0);
    int found = 0;
    for (int i = 0; i < count && !found && !*stop; i++) {
        uint64_t seed = start + (uint64_t)i;
        if (matches(&g[0], &g[1], f, seed)) { *out = seed; found = 1; }
    }
    free(g);
    return found;
}

SF_API void sf_spawn(int largeBiomes, uint64_t seed, int *x, int *z, int *biome)
{
    Generator *g = malloc(sizeof *g);
    if (!g) { *x = *z = 0; *biome = -1; return; }
    setupGenerator(g, MC, largeBiomes ? LARGE_BIOMES : 0);
    applySeed(g, DIM_OVERWORLD, seed);
    Pos s = spawnOf(g);
    *x = s.x; *z = s.z;
    *biome = getBiomeAt(g, 1, s.x, SURFACE_Y, s.z);
    free(g);
}

SF_API int sf_spawn_size(int largeBiomes, uint64_t seed)
{
    Generator *g = malloc(sizeof *g);
    if (!g) return SF_SIZE_SMALL;
    setupGenerator(g, MC, largeBiomes ? LARGE_BIOMES : 0);
    applySeed(g, DIM_OVERWORLD, seed);
    Pos s = spawnOf(g);
    int size = patchSize(g, s.x, s.z, getBiomeAt(g, 1, s.x, SURFACE_Y, s.z));
    free(g);
    return size;
}

SF_API int sf_nether_biome(int largeBiomes, uint64_t seed)
{
    Generator *g = malloc(sizeof *g);
    if (!g) return -1;
    setupGenerator(g, MC, largeBiomes ? LARGE_BIOMES : 0);
    applySeed(g, DIM_OVERWORLD, seed);
    Pos s = spawnOf(g);
    setupGenerator(g, MC, 0);
    applySeed(g, DIM_NETHER, seed);
    int b = netherBiomeAt(g, floordiv(s.x, 8), floordiv(s.z, 8));
    free(g);
    return b;
}

SF_API const char *sf_nether_biome_name(int id)
{
    return id >= 0 && id < 256 && getDimension(id) == DIM_NETHER ? biome2str(MC, id) : NULL;
}

SF_API const char *sf_biome_name(int id)
{
    return id >= 0 && id < 256 && isOverworld(MC, id) ? biome2str(MC, id) : NULL;
}

SF_API int sf_struct_id(const char *name)
{
    for (int t = 0; t < FEATURE_NUM; t++) {
        const char *s = struct2str(t);
        if (s && strcmp(s, name) == 0) {
            StructureConfig sc;
            return t == Stronghold || getStructureConfig(t, MC, &sc) ? t : -1;
        }
    }
    return -1;
}
