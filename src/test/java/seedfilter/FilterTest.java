package seedfilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FilterTest {
    @BeforeAll
    static void load() {
        Native.load(Path.of(System.getProperty("seedfilter.dll")));
    }

    @Test
    void catalogHasSurfaceBiomesOnly() {
        assertTrue(Ids.biomes().containsKey("cherry_grove"));
        assertTrue(Ids.biomes().containsKey("dappled_forest"));
        assertFalse(Ids.biomes().containsKey("deep_dark"));
        assertFalse(Ids.biomes().containsKey("lush_caves"));
        assertTrue(Ids.structure("village").isPresent());
    }

    @Test
    void corruptFileGivesEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seedfilter.json");
        Files.writeString(file, "{ this is not json");
        assertEquals(Filter.EMPTY, Filter.load(file));
        assertEquals(Filter.EMPTY, Filter.load(dir.resolve("missing.json")));
    }

    @Test
    void everyLocateTargetExistsInVanillaData() {
        for (Ids.Structure st : Ids.structures()) {
            String t = st.locate();
            String file = t.startsWith("#")
                    ? "data/minecraft/tags/worldgen/structure/" + t.substring("#minecraft:".length()) + ".json"
                    : "data/minecraft/worldgen/structure/" + t.substring("minecraft:".length()) + ".json";
            assertNotNull(FilterTest.class.getClassLoader().getResource(file), st.id() + " -> " + t + " is not in 26.3 data");
        }
    }

    @Test
    void sanitizeDropsUnknownClampsAndDefaults() {
        Filter f = new Filter("not_a_biome", null,
                List.of(new Filter.Rule("village", 10), new Filter.Rule("nope", 500), new Filter.Rule("igloo", 99999)),
                List.of(new Filter.Near("deep_dark", 300), new Filter.Near("jungle", 20))).sanitize();
        assertNull(f.spawnBiome());
        assertEquals(Native.Size.ANY, f.spawnSize());
        assertEquals(List.of(new Filter.Rule("village", 50), new Filter.Rule("igloo", 3000)), f.rules());
        assertEquals(List.of(new Filter.Near("jungle", 50)), f.nearby());
    }

    @Test
    void sanitizeDedupesAndCaps() {
        Filter f = new Filter("plains", Native.Size.LARGE,
                List.of(new Filter.Rule("village", 300), new Filter.Rule("village", 900)),
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(new Filter.Near("jungle", 100), new Filter.Near("jungle", 200)),
                        Ids.biomes().keySet().stream().map(b -> new Filter.Near(b, 500))).toList()).sanitize();
        assertEquals(List.of(new Filter.Rule("village", 300)), f.rules());
        assertEquals(new Filter.Near("jungle", 100), f.nearby().getFirst());
        assertEquals(Native.MAX_NEAR, f.nearby().size());
        assertEquals(f.nearby().size(), f.nearby().stream().map(Filter.Near::biome).distinct().count());
        assertEquals(Filter.EMPTY, new Filter(null, null, null, null).sanitize());
    }

    @Test
    void saveLoadRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("seedfilter.json");
        Filter f = new Filter("cherry_grove", Native.Size.MEDIUM, List.of(new Filter.Rule("village", 300)), List.of(new Filter.Near("jungle", 800)));
        f.save(file);
        assertEquals(f, Filter.load(file));
    }

    @Test
    void v1FileLoads(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seedfilter.json");
        Files.writeString(file, "{\"biomes\":[\"desert\",\"jungle\"],\"rules\":[{\"structure\":\"village\",\"maxDist\":500}]}");
        Filter f = Filter.load(file);
        assertNull(f.spawnBiome());
        assertEquals(List.of(new Filter.Rule("village", 500)), f.rules());
        assertEquals(List.of(), f.nearby());
    }

    @Test
    void toQueryMapsNamesToIds() {
        Native.Query q = new Filter("plains", Native.Size.SMALL, List.of(new Filter.Rule("village", 300)),
                List.of(new Filter.Near("jungle", 700))).toQuery(true);
        assertTrue(q.largeBiomes());
        assertEquals(1, q.spawnBiome());
        assertEquals(Native.Size.SMALL, q.spawnSize());
        assertArrayEquals(new int[]{Native.structureId("village")}, q.structures());
        assertArrayEquals(new int[]{300}, q.maxDists());
        assertArrayEquals(new int[]{Ids.biomes().get("jungle")}, q.nearBiomes());
        assertArrayEquals(new int[]{700}, q.nearDists());
        assertEquals(Native.ANY_BIOME, Filter.EMPTY.toQuery(false).spawnBiome());
    }

    @Test
    void emptyMeansNoCondition() {
        assertTrue(Filter.EMPTY.isEmpty());
        assertFalse(new Filter("plains", Native.Size.ANY, List.of(), List.of()).isEmpty());
        assertFalse(new Filter(null, Native.Size.LARGE, List.of(), List.of()).isEmpty());
        assertFalse(new Filter(null, Native.Size.ANY, List.of(new Filter.Rule("village", 500)), List.of()).isEmpty());
        assertFalse(new Filter(null, Native.Size.ANY, List.of(), List.of(new Filter.Near("jungle", 500))).isEmpty());
    }

    @Test
    void summaryListsEveryCondition() {
        Filter f = new Filter("cherry_grove", Native.Size.LARGE,
                List.of(new Filter.Rule("village", 500), new Filter.Rule("igloo", 800)), List.of(new Filter.Near("jungle", 1000)));
        assertEquals("cherry_grove (large) · village ≤500 · igloo ≤800 · jungle nearby ≤1000", f.summary(b -> b, s -> s));
        assertEquals("any biome (small)", new Filter(null, Native.Size.SMALL, List.of(), List.of()).summary(b -> b, s -> s));
        assertEquals("", Filter.EMPTY.summary(b -> b, s -> s));
    }

    @Test
    void hideSeedIsSavedAndDefaultsToShowing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("seedfilter.json");
        Filter hidden = new Filter("plains", Native.Size.ANY, List.of(), List.of(), true);
        hidden.save(file);
        assertTrue(Filter.load(file).hideSeed());
        assertFalse(new Filter("plains", Native.Size.ANY, List.of(), List.of()).hideSeed());
        Files.writeString(file, "{\"spawnBiome\":\"plains\"}");
        assertFalse(Filter.load(file).hideSeed(), "older files show the seed");
        assertTrue(new Filter(null, Native.Size.ANY, List.of(), List.of(), true).isEmpty(), "a preference is not a condition");
    }

    @Test
    void warnsAboutCloseStrongholdOnly() {
        assertEquals(1, new Filter(null, Native.Size.ANY, List.of(new Filter.Rule("stronghold", 500)), List.of()).warnings().size());
        assertEquals(List.of(), new Filter(null, Native.Size.ANY, List.of(new Filter.Rule("stronghold", 1500)), List.of()).warnings());
        assertEquals(List.of(), new Filter(null, Native.Size.ANY, List.of(new Filter.Rule("village", 50)), List.of()).warnings());
    }

    static Filter nether(String biome, int fortress, int bastion, Native.Bastion type) {
        return new Filter(null, Native.Size.ANY, List.of(), List.of(), false, biome, fortress, bastion, type);
    }

    @Test
    void netherSanitizeAndQuery() {
        Filter f = nether("crimson_forest", 10, 5000, null).sanitize();
        assertEquals("crimson_forest", f.netherBiome());
        assertEquals(Native.MIN_DIST, f.fortressDist());
        assertEquals(Native.MAX_NETHER_DIST, f.bastionDist());
        assertEquals(Native.Bastion.ANY, f.bastionType());
        assertNull(nether("plains", 0, 0, Native.Bastion.ANY).sanitize().netherBiome(), "overworld biome is not a nether arrival biome");
        assertEquals(0, nether(null, 0, 0, Native.Bastion.TREASURE).sanitize().bastionDist(), "0 = off stays off");

        Native.Query q = nether("crimson_forest", 150, 200, Native.Bastion.TREASURE).sanitize().toQuery(false);
        assertEquals(Ids.netherBiomes().get("crimson_forest"), q.netherBiome());
        assertEquals(150, q.fortressDist());
        assertEquals(200, q.bastionDist());
        assertEquals(Native.Bastion.TREASURE, q.bastionType());
        assertEquals(Native.ANY_BIOME, Filter.EMPTY.toQuery(false).netherBiome());
    }

    @Test
    void netherCountsAsConditionAndInSummary() {
        assertFalse(nether(null, 150, 0, Native.Bastion.ANY).isEmpty());
        assertFalse(nether("warped_forest", 0, 0, Native.Bastion.ANY).isEmpty());
        assertTrue(nether(null, 0, 0, Native.Bastion.TREASURE).sanitize().isEmpty(), "a type without a bastion rule is nothing");
        assertEquals("nether: warped_forest · fortress ≤150 · treasure bastion ≤200",
                nether("warped_forest", 150, 200, Native.Bastion.TREASURE).summary(b -> b, s -> s));
    }

    @Test
    void catalogHasNetherBiomes() {
        assertEquals(Set.of("nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas"),
                Ids.netherBiomes().keySet());
    }
}
