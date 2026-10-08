#include <stdlib.h>
#include <string.h>
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

// gn: a second generator used for the Nether conditions, applied only once everything else matched
static int matches(Generator *g, Generator *gn, const SfFilter *f, uint64_t seed)
{
    applySeed(g, DIM_OVERWORLD, seed);
    Pos s = getSpawn(g);
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
    Pos s = getSpawn(g);
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
    Pos s = getSpawn(g);
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
    Pos s = getSpawn(g);
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
