package seedfilter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@code hideSeed} is a display preference, not a search condition: it only skips the seed line in chat.
 * Nether fields are measured from spawn / 8; a distance of 0 means that rule is off.
 */
public record Filter(String spawnBiome, Native.Size spawnSize, List<Rule> rules, List<Near> nearby, boolean hideSeed,
                     String netherBiome, int fortressDist, int bastionDist, Native.Bastion bastionType) {
    public record Rule(String structure, int maxDist) {}
    public record Near(String biome, int maxDist) {}

    public Filter(String spawnBiome, Native.Size spawnSize, List<Rule> rules, List<Near> nearby) {
        this(spawnBiome, spawnSize, rules, nearby, false);
    }

    public Filter(String spawnBiome, Native.Size spawnSize, List<Rule> rules, List<Near> nearby, boolean hideSeed) {
        this(spawnBiome, spawnSize, rules, nearby, hideSeed, null, 0, 0, Native.Bastion.ANY);
    }

    public static final Filter EMPTY = new Filter(null, Native.Size.ANY, List.of(), List.of());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = LoggerFactory.getLogger("seedfilter");

    /** Drops names the current DLL doesn't know, dedupes (first wins), clamps distances, caps counts. */
    public Filter sanitize() {
        String b = spawnBiome != null && Ids.biomes().containsKey(spawnBiome) ? spawnBiome : null;
        Set<String> seenR = new HashSet<>(), seenN = new HashSet<>();
        List<Rule> r = rules == null ? List.of() : rules.stream()
                .filter(Objects::nonNull).filter(x -> Ids.structure(x.structure()).isPresent()).filter(x -> seenR.add(x.structure()))
                .map(x -> new Rule(x.structure(), clampDist(x.maxDist()))).limit(Native.MAX_RULES).toList();
        List<Near> n = nearby == null ? List.of() : nearby.stream()
                .filter(Objects::nonNull).filter(x -> Ids.biomes().containsKey(x.biome())).filter(x -> seenN.add(x.biome()))
                .map(x -> new Near(x.biome(), clampDist(x.maxDist()))).limit(Native.MAX_NEAR).toList();
        String nb = netherBiome != null && Ids.netherBiomes().containsKey(netherBiome) ? netherBiome : null;
        return new Filter(b, spawnSize == null ? Native.Size.ANY : spawnSize, r, n, hideSeed,
                nb, netherDist(fortressDist), netherDist(bastionDist), bastionType == null ? Native.Bastion.ANY : bastionType);
    }

    private static int clampDist(int d) { return Math.clamp(d, Native.MIN_DIST, Native.MAX_DIST); }

    private static int netherDist(int d) { return d <= 0 ? 0 : Math.clamp(d, Native.MIN_DIST, Native.MAX_NETHER_DIST); }

    // Strongholds generate 1280-2816 blocks from the world center and spawn is almost always near it.
    private static final int STRONGHOLD_MIN_SENSIBLE = 1000;

    // Biomes at opposite ends of the temperature noise (wiki: levels 0 vs 3-4), with the structures that need them.
    private static final Set<String> FROZEN = Set.of("snowy_plains", "ice_spikes", "snowy_taiga", "frozen_ocean",
            "deep_frozen_ocean", "frozen_river", "snowy_beach", "igloo");
    private static final Set<String> HOT = Set.of("desert", "badlands", "eroded_badlands", "wooded_badlands", "savanna",
            "savanna_plateau", "windswept_savanna", "jungle", "bamboo_jungle", "sparse_jungle", "mangrove_swamp",
            "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "desert_pyramid", "jungle_pyramid");
    private static final Set<String> STRIPS = Set.of("river", "frozen_river", "beach", "snowy_beach", "stony_shore");
    private static final int CLIMATE_CLASH = 1000;         // ponytail: rough cut-off, opposite climates rarely meet closer
    private static final int TOO_MANY = 8;                 // ponytail: rough count, each extra condition multiplies rarity
    private static final int OUTPOST_VILLAGE_GAP = 160;    // outposts keep 10 chunks from any village
    private static final int FORTRESS_BASTION_GAP = 80;    // one per 432-block region with a 4-chunk buffer

    /**
     * Conditions that (almost) never match, explained for the player. Nothing is blocked: the search still runs,
     * a modded or bugged world might break the rule.
     */
    public List<String> warnings() {
        List<String> w = new ArrayList<>();
        if ("mushroom_fields".equals(spawnBiome)) w.add("Impossible: players never spawn in Mushroom Fields");
        else if (spawnBiome != null && (spawnBiome.contains("ocean") || spawnBiome.endsWith("river")))
            w.add("Almost impossible: spawn avoids oceans and rivers");
        if (spawnSize == Native.Size.LARGE && STRIPS.contains(spawnBiome))
            w.add("Almost impossible: this biome is a thin strip, never Large");
        int village = dist("village"), outpost = dist("pillager_outpost");
        if (village + outpost < OUTPOST_VILLAGE_GAP)
            w.add("Impossible: outposts never generate within 10 chunks of a village");
        if (fortressDist > 0 && bastionDist > 0 && fortressDist + bastionDist < FORTRESS_BASTION_GAP)
            w.add("Impossible: fortresses and bastions are always 80+ blocks apart");
        if (dist(FROZEN) + dist(HOT) <= CLIMATE_CLASH)
            w.add("Very rare: frozen and hot biomes are far apart");
        nearby.stream().filter(n -> n.biome().equals("mushroom_fields") && n.maxDist() < 300).findFirst()
                .ifPresent(n -> w.add("Very rare: Mushroom Fields are islands far out in deep ocean"));
        if (dist("stronghold") < STRONGHOLD_MIN_SENSIBLE)
            w.add("Possible but rare: strongholds are 1,280+ blocks from the center");
        if (rules.size() + nearby.size() >= TOO_MANY)
            w.add("Almost impossible: " + (rules.size() + nearby.size()) + " structures/biomes at once");
        w.sort(java.util.Comparator.comparing(s -> !s.startsWith("Impossible"))); // impossible first: the title shows only one
        return w;
    }

    /** Red for "Impossible…", yellow for the rare ones. */
    public static int warningColor(String warning) {
        return warning.startsWith("Impossible") ? 0xFFFF5555 : 0xFFFFD040;
    }

    private int dist(String name) { return dist(Set.of(name)); }

    /** Smallest distance asked for any of {@code names} (spawn biome counts as 0); huge when none is asked for. */
    private int dist(Set<String> names) {
        int d = spawnBiome != null && names.contains(spawnBiome) ? 0 : 1_000_000;
        for (Rule r : rules) if (names.contains(r.structure())) d = Math.min(d, r.maxDist());
        for (Near n : nearby) if (names.contains(n.biome())) d = Math.min(d, n.maxDist());
        return d;
    }

    /** True when the filter asks for nothing, so world creation needs no search. */
    public boolean isEmpty() {
        return spawnBiome == null && spawnSize == Native.Size.ANY && rules.isEmpty() && nearby.isEmpty()
                && netherBiome == null && fortressDist <= 0 && bastionDist <= 0;
    }

    /** One line for the button tooltip, e.g. "Cherry Grove (large) · Village ≤500 · Jungle nearby ≤1000". */
    public String summary(Function<String, String> biomeLabel, Function<String, String> structureLabel) {
        List<String> parts = new ArrayList<>();
        if (spawnBiome != null || spawnSize != Native.Size.ANY)
            parts.add((spawnBiome == null ? "any biome" : biomeLabel.apply(spawnBiome))
                    + (spawnSize == Native.Size.ANY ? "" : " (" + spawnSize.name().toLowerCase(Locale.ROOT) + ")"));
        rules.forEach(r -> parts.add(structureLabel.apply(r.structure()) + " ≤" + r.maxDist()));
        nearby.forEach(n -> parts.add(biomeLabel.apply(n.biome()) + " nearby ≤" + n.maxDist()));
        List<String> nether = new ArrayList<>();
        if (netherBiome != null) nether.add(biomeLabel.apply(netherBiome));
        if (fortressDist > 0) nether.add("fortress ≤" + fortressDist);
        if (bastionDist > 0) nether.add((bastionType == Native.Bastion.ANY ? "" : bastionType.name().toLowerCase(Locale.ROOT) + " ")
                + "bastion ≤" + bastionDist);
        if (!nether.isEmpty()) parts.add("nether: " + String.join(" · ", nether));
        return String.join(" · ", parts);
    }

    public Native.Query toQuery(boolean largeBiomes) {
        return new Native.Query(largeBiomes,
                spawnBiome == null ? Native.ANY_BIOME : Ids.biomes().get(spawnBiome), spawnSize,
                rules.stream().mapToInt(x -> Native.structureId(x.structure())).toArray(),
                rules.stream().mapToInt(Rule::maxDist).toArray(),
                nearby.stream().mapToInt(x -> Ids.biomes().get(x.biome())).toArray(),
                nearby.stream().mapToInt(Near::maxDist).toArray(),
                netherBiome == null ? Native.ANY_BIOME : Ids.netherBiomes().get(netherBiome),
                fortressDist, bastionDist, bastionType);
    }

    public static Filter load(Path file) {
        if (!Files.exists(file)) return EMPTY;
        try {
            Filter f = GSON.fromJson(Files.readString(file), Filter.class);
            return f == null ? EMPTY : f.sanitize();
        } catch (Exception e) {
            LOG.warn("Ignoring unreadable {}: {}", file, e.toString());
            return EMPTY;
        }
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this));
        } catch (Exception e) {
            LOG.warn("Could not save {}: {}", file, e.toString());
        }
    }
}
