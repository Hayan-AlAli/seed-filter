package seedfilter;

import java.util.*;

/** Biome and structure catalog; ids come from the DLL so they can't drift from cubiomes. */
public final class Ids {
    public record Structure(String id, String label, String locate) {}

    private static final Set<String> CAVES = Set.of("deep_dark", "dripstone_caves", "lush_caves", "sulfur_caves");

    // id = cubiomes struct2str name; locate = what /locate structure accepts in 26.3
    private static final List<Structure> ALL = List.of(
            new Structure("village", "Village", "#minecraft:village"),
            new Structure("pillager_outpost", "Pillager Outpost", "minecraft:pillager_outpost"),
            new Structure("trial_chambers", "Trial Chambers", "minecraft:trial_chambers"),
            new Structure("ancient_city", "Ancient City", "minecraft:ancient_city"),
            new Structure("mansion", "Woodland Mansion", "minecraft:mansion"),
            new Structure("monument", "Ocean Monument", "minecraft:monument"),
            new Structure("desert_pyramid", "Desert Pyramid", "minecraft:desert_pyramid"),
            new Structure("jungle_pyramid", "Jungle Temple", "minecraft:jungle_pyramid"),
            new Structure("swamp_hut", "Swamp Hut", "minecraft:swamp_hut"),
            new Structure("igloo", "Igloo", "minecraft:igloo"),
            new Structure("shipwreck", "Shipwreck", "#minecraft:shipwreck"),
            new Structure("ocean_ruin", "Ocean Ruin", "#minecraft:ocean_ruin"),
            new Structure("ruined_portal", "Ruined Portal", "#minecraft:ruined_portal"),
            new Structure("trail_ruins", "Trail Ruins", "minecraft:trail_ruins"),
            new Structure("abandoned_camp", "Abandoned Camp", "#minecraft:abandoned_camp"),
            new Structure("stronghold", "Stronghold", "minecraft:stronghold"));

    private static List<Structure> structures;
    private static Map<String, Integer> biomes, netherBiomes;

    private Ids() {}

    public static synchronized List<Structure> structures() {
        if (structures == null)
            structures = ALL.stream().filter(s -> Native.structureId(s.id()) >= 0).toList();
        return structures;
    }

    public static Optional<Structure> structure(String id) {
        return structures().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public static synchronized Map<String, Integer> netherBiomes() {
        if (netherBiomes == null) {
            Map<String, Integer> m = new TreeMap<>();
            for (int id = 0; id < 256; id++) {
                String name = Native.netherBiomeName(id);
                if (name != null) m.put(name, id);
            }
            netherBiomes = Collections.unmodifiableMap(m);
        }
        return netherBiomes;
    }

    public static synchronized Map<String, Integer> biomes() {
        if (biomes == null) {
            Map<String, Integer> m = new TreeMap<>();
            for (int id = 0; id < 256; id++) {
                String name = Native.biomeName(id);
                if (name != null && !CAVES.contains(name)) m.put(name, id);
            }
            biomes = Collections.unmodifiableMap(m);
        }
        return biomes;
    }
}
