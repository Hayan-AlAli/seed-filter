#include <stdio.h>
#include <string.h>
#include <time.h>
#include "seedfilter.h"

static int fails = 0;
#define CHECK(c) do { if (!(c)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #c); fails++; } } while (0)

static SfFilter blank(void)
{
    SfFilter f;
    memset(&f, 0, sizeof f);
    f.spawnBiome = -1;
    f.netherBiome = -1;
    f.bastionType = -1;
    return f;
}

int main(void)
{
    CHECK(sizeof(SfFilter) == 73 * 4);
    CHECK(sf_biome_name(1) && strcmp(sf_biome_name(1), "plains") == 0);
    CHECK(sf_biome_name(188) && strcmp(sf_biome_name(188), "dappled_forest") == 0);
    CHECK(sf_biome_name(-1) == NULL);
    CHECK(sf_biome_name(9999) == NULL);
    CHECK(sf_struct_id("village") >= 0);
    CHECK(sf_struct_id("stronghold") >= 0);
    CHECK(sf_struct_id("not_a_structure") == -1);

    uint64_t seed, again;
    volatile int32_t noStop = 0;
    int x, z, b;

    // blank filter (any biome) matches the first seed of the batch
    SfFilter any = blank();
    CHECK(sf_search(&any, 42, 1, &noStop, &seed) && seed == 42);

    // spawn biome: the returned seed really spawns in plains
    SfFilter plains = blank();
    plains.spawnBiome = 1;
    CHECK(sf_search(&plains, 0, 5000, &noStop, &seed));
    sf_spawn(0, seed, &x, &z, &b);
    CHECK(b == 1);

    // structure rule is neither always-true nor always-false
    SfFilter vil = blank();
    vil.ruleCount = 1; vil.ruleStructure[0] = sf_struct_id("village"); vil.ruleMaxDist[0] = 300;
    int hits = 0;
    clock_t t0 = clock();
    for (uint64_t s = 0; s < 500; s++) hits += sf_search(&vil, s, 1, &noStop, &again);
    double ms = 1000.0 * (clock() - t0) / CLOCKS_PER_SEC;
    printf("village<=300: %d/500 seeds, %.0f seeds/s single-thread\n", hits, 500 / (ms / 1000.0));
    CHECK(hits > 0 && hits < 500);
    CHECK(sf_search(&vil, 0, 500, &noStop, &seed) && sf_search(&vil, seed, 1, &noStop, &again) && again == seed);

    // stronghold within 3000 always exists in the first ring
    SfFilter sh = blank();
    sh.ruleCount = 1; sh.ruleStructure[0] = sf_struct_id("stronghold"); sh.ruleMaxDist[0] = 3000;
    CHECK(sf_search(&sh, 7, 1, &noStop, &seed));

    // a set stop flag aborts the batch before the first seed
    volatile int32_t stopNow = 1;
    CHECK(!sf_search(&any, 42, 1000000, &stopNow, &seed));

    // stronghold rules must not pay the biome snap for strongholds that can't be in range
    SfFilter shNear = blank();
    shNear.ruleCount = 1; shNear.ruleStructure[0] = sf_struct_id("stronghold"); shNear.ruleMaxDist[0] = 50;
    t0 = clock();
    sf_search(&shNear, 1000, 256, &noStop, &seed);
    ms = 1000.0 * (clock() - t0) / CLOCKS_PER_SEC;
    printf("stronghold<=50 batch of 256: %.0f ms\n", ms);
    CHECK(ms < 1500);

    // biome size: each size is findable and the found seed measures as that size
    for (int size = SF_SIZE_SMALL; size <= SF_SIZE_LARGE; size++) {
        SfFilter sz = blank();
        sz.spawnSize = size;
        t0 = clock();
        int found = sf_search(&sz, 0, 3000, &noStop, &seed);
        printf("size %d: found=%d seed %llu (%.0f ms)\n", size, found, (unsigned long long)seed,
               1000.0 * (clock() - t0) / CLOCKS_PER_SEC);
        CHECK(found && sf_spawn_size(0, seed) == size);
    }

    // spawn on a 4x4 cell edge (block biome != its cell's biome): the fill must still start in the spawn biome
    CHECK(sf_spawn_size(0, 48) == SF_SIZE_LARGE);  // large desert
    CHECK(sf_spawn_size(0, 366) == SF_SIZE_LARGE); // large plains
    CHECK(sf_spawn_size(0, 158) == SF_SIZE_MEDIUM);

    // nearby biome: ocean within 500 is neither always-true nor always-false
    SfFilter oc = blank();
    oc.nearCount = 1; oc.nearBiome[0] = 0; oc.nearMaxDist[0] = 500; // 0 = ocean
    hits = 0;
    t0 = clock();
    for (uint64_t s = 0; s < 200; s++) hits += sf_search(&oc, s, 1, &noStop, &again);
    printf("ocean within 500: %d/200 (%.0f ms)\n", hits, 1000.0 * (clock() - t0) / CLOCKS_PER_SEC);
    CHECK(hits > 0 && hits < 200);

    // nearby biome rule: the spawn biome itself is always within 50
    SfFilter self = blank();
    self.spawnBiome = 1;
    self.nearCount = 1; self.nearBiome[0] = 1; self.nearMaxDist[0] = 50;
    CHECK(sf_search(&self, 0, 5000, &noStop, &seed));

    // nether: arrival biome under spawn/8 is what the filter asked for
    SfFilter crimson = blank();
    crimson.netherBiome = 171; // crimson_forest
    CHECK(sf_search(&crimson, 0, 3000, &noStop, &seed));
    CHECK(sf_nether_biome(0, seed) == 171);

    // fortress / bastion within 150 nether blocks: neither always nor never
    SfFilter fort = blank(), bast = blank();
    fort.fortressDist = 150;
    bast.bastionDist = 150;
    int fortHits = 0, bastHits = 0;
    t0 = clock();
    for (uint64_t s = 0; s < 300; s++) {
        fortHits += sf_search(&fort, s, 1, &noStop, &again);
        bastHits += sf_search(&bast, s, 1, &noStop, &again);
    }
    printf("nether within 150: fortress %d/300, bastion %d/300 (%.0f ms)\n", fortHits, bastHits,
           1000.0 * (clock() - t0) / CLOCKS_PER_SEC);
    CHECK(fortHits > 0 && fortHits < 300);
    CHECK(bastHits > 0 && bastHits < 300);

    // every bastion type is findable, and asking for one type never matches more seeds than "any"
    for (int type = SF_BASTION_HOUSING; type <= SF_BASTION_BRIDGE; type++) {
        SfFilter bt = blank();
        bt.bastionDist = 300;
        bt.bastionType = type;
        CHECK(sf_search(&bt, 0, 2000, &noStop, &seed));
        int typed = 0, any = 0;
        SfFilter ba = blank();
        ba.bastionDist = 300;
        for (uint64_t s = 0; s < 100; s++) {
            typed += sf_search(&bt, s, 1, &noStop, &again);
            any += sf_search(&ba, s, 1, &noStop, &again);
        }
        CHECK(typed <= any);
    }

    // golden: verified in-game on 26.3 (desert spawn, ancient city + village within 500, no Verifier warning)
    SfFilter golden = blank();
    golden.spawnBiome = 2; // desert
    golden.ruleCount = 2;
    golden.ruleStructure[0] = sf_struct_id("ancient_city"); golden.ruleMaxDist[0] = 500;
    golden.ruleStructure[1] = sf_struct_id("village");      golden.ruleMaxDist[1] = 500;
    uint64_t gseed = (uint64_t)-1072170155597295798LL;
    CHECK(sf_search(&golden, gseed, 1, &noStop, &again) && again == gseed);
    sf_spawn(0, gseed, &x, &z, &b);
    CHECK(b == 2);

    printf(fails ? "%d FAILED\n" : "all passed\n", fails);
    return fails != 0;
}
