#pragma once
#include <stdint.h>

#if defined(SF_BUILD_DLL) && defined(_WIN32)
#define SF_API __declspec(dllexport)
#elif defined(SF_BUILD_DLL)
#define SF_API __attribute__((visibility("default")))
#else
#define SF_API
#endif

#define SF_MAX_RULES 16
#define SF_MAX_NEAR 16

enum { SF_SIZE_ANY = 0, SF_SIZE_SMALL = 1, SF_SIZE_MEDIUM = 2, SF_SIZE_LARGE = 3 };
// cubiomes bastion start piece: units, hoglin_stable, treasure, bridge
enum { SF_BASTION_HOUSING = 0, SF_BASTION_STABLES = 1, SF_BASTION_TREASURE = 2, SF_BASTION_BRIDGE = 3 };

// Layout is mirrored by Native.java: 73 consecutive int32s.
typedef struct {
    int32_t largeBiomes;
    int32_t spawnBiome;                  // cubiomes BiomeID, -1 = any
    int32_t spawnSize;                   // SF_SIZE_*
    int32_t ruleCount;                   // 0..SF_MAX_RULES
    int32_t ruleStructure[SF_MAX_RULES]; // cubiomes StructureType
    int32_t ruleMaxDist[SF_MAX_RULES];   // blocks from spawn
    int32_t nearCount;                   // 0..SF_MAX_NEAR
    int32_t nearBiome[SF_MAX_NEAR];      // cubiomes BiomeID
    int32_t nearMaxDist[SF_MAX_NEAR];    // blocks from spawn
    // Nether, measured from spawn / 8 (where a portal built at spawn leads)
    int32_t netherBiome;                 // cubiomes BiomeID there, -1 = any
    int32_t fortressDist;                // nether blocks, 0 = off
    int32_t bastionDist;                 // nether blocks, 0 = off
    int32_t bastionType;                 // SF_BASTION_*, -1 = any
} SfFilter;

// Stops early (returns 0) once *stop becomes non-zero; checked before every seed.
SF_API int sf_search(const SfFilter *f, uint64_t start, int count, const volatile int32_t *stop, uint64_t *out);
SF_API void sf_spawn(int largeBiomes, uint64_t seed, int *x, int *z, int *biome);
// Size class (SF_SIZE_SMALL..LARGE) of the spawn biome's connected patch.
SF_API int sf_spawn_size(int largeBiomes, uint64_t seed);
// Nether biome at spawn / 8.
SF_API int sf_nether_biome(int largeBiomes, uint64_t seed);
SF_API const char *sf_biome_name(int id);
// Name of a Nether biome id, NULL for anything else.
SF_API const char *sf_nether_biome_name(int id);
SF_API int sf_struct_id(const char *name);
