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

    /** Conditions that rarely match (they need an unusual spawn), explained for the player. */
    public List<String> warnings() {
        return rules.stream().filter(r -> r.structure().equals("stronghold") && r.maxDist() < STRONGHOLD_MIN_SENSIBLE)
                .map(r -> "Possible but rare: strongholds are 1,280+ blocks from the center")
                .toList();
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
