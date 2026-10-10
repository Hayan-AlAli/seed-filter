package seedfilter;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SearcherTest {
    static final int PLAINS = 1, THE_VOID = 127; // the_void never generates naturally

    @BeforeAll
    static void load() {
        Native.load(Path.of(System.getProperty("seedfilter.dll")));
        assertTrue(Native.isAvailable(), Native.error());
    }

    static Native.Query q(int spawnBiome, Native.Size size, int[] s, int[] d, int[] nb, int[] nd) {
        return new Native.Query(false, spawnBiome, size, s, d, nb, nd);
    }

    static Native.Query biomeOnly(int biome) {
        return q(biome, Native.Size.ANY, new int[0], new int[0], new int[0], new int[0]);
    }

    @Test
    void findsPlainsSpawn() throws Exception {
        Searcher s = new Searcher(biomeOnly(PLAINS), 2);
        long seed = s.result().get(30, TimeUnit.SECONDS);
        assertEquals(PLAINS, Native.spawn(false, seed).biome());
    }

    @Test
    void catalogLookups() {
        assertEquals("plains", Native.biomeName(PLAINS));
        assertNull(Native.biomeName(-5));
        assertTrue(Native.structureId("village") >= 0);
        assertEquals(-1, Native.structureId("nope"));
    }

    @Test
    void cancelStopsThreads() throws Exception {
        // stronghold rule: ~1 s per 256-seed batch, so only a per-seed stop check passes this window
        int sh = Native.structureId("stronghold");
        Searcher s = new Searcher(q(THE_VOID, Native.Size.ANY, new int[]{sh}, new int[]{50}, new int[0], new int[0]), 2);
        Thread.sleep(300);
        s.cancel();
        Thread.sleep(200);
        long before = s.checked();
        Thread.sleep(1500);
        assertEquals(before, s.checked(), "threads still running after cancel");
    }

    @Test
    void noResultAfterCancel() {
        Searcher s = new Searcher(biomeOnly(THE_VOID), 0); // no threads: drive offer() by hand
        s.cancel();
        s.offer(42); // a batch that finished just after Cancel
        assertFalse(s.result().isDone(), "a cancelled search must never deliver a seed");

        Searcher t = new Searcher(biomeOnly(THE_VOID), 0);
        t.offer(7);
        t.offer(8);
        assertEquals(7L, t.result().join(), "first hit wins");
    }

    @Test
    void queryValidation() {
        int[] none = new int[0];
        assertThrows(IllegalArgumentException.class, () -> q(-2, Native.Size.ANY, none, none, none, none));
        assertThrows(IllegalArgumentException.class, () -> q(256, Native.Size.ANY, none, none, none, none));
        assertThrows(IllegalArgumentException.class, () -> q(-1, null, none, none, none, none));
        assertThrows(IllegalArgumentException.class, () -> q(-1, Native.Size.ANY, new int[]{-1}, new int[]{100}, none, none));
        assertThrows(IllegalArgumentException.class, () -> q(-1, Native.Size.ANY, new int[]{5}, new int[]{0}, none, none));
        assertThrows(IllegalArgumentException.class, () -> q(-1, Native.Size.ANY, new int[]{5, 5}, new int[]{100}, none, none));
        assertThrows(IllegalArgumentException.class, () -> q(-1, Native.Size.ANY, new int[17], new int[17], none, none));
        assertThrows(IllegalArgumentException.class, () -> q(-1, Native.Size.ANY, none, none, new int[]{300}, new int[]{100}));
        assertThrows(IllegalArgumentException.class, () -> q(-1, Native.Size.ANY, none, none, new int[]{1}, new int[]{9999}));
        assertThrows(IllegalArgumentException.class, () -> q(-1, Native.Size.ANY, none, none, new int[17], new int[17]));
    }

    @Test
    void toSegmentWritesAnyBiome() {
        var seg = Native.toSegment(java.lang.foreign.Arena.ofAuto(), q(-1, Native.Size.LARGE, new int[0], new int[0], new int[0], new int[0]));
        assertEquals(73 * 4, seg.byteSize());
        assertEquals(-1, seg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 1));
        assertEquals(3, seg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 2));
    }

    @Test
    void findsLargeSpawnPatch() throws Exception {
        Searcher s = new Searcher(q(-1, Native.Size.LARGE, new int[0], new int[0], new int[0], new int[0]), 2);
        long seed = s.result().get(60, TimeUnit.SECONDS);
        assertEquals(Native.Size.LARGE, Native.spawnSize(false, seed));
    }

    @Test
    void loadBadPathIsUnavailableButKeepsWorkingLib() {
        // load() on a bad path must not throw and must not clobber an already-loaded library
        Native.load(Path.of("does/not/exist.dll"));
        assertNotNull(Native.error());
        assertTrue(Native.isAvailable());
    }

    @Test
    void rarityBoundFollowsRuleOfThree() {
        assertEquals(0, Searcher.rarityBound(19_999), "too few seeds to say anything");
        assertEquals(10_000, Searcher.rarityBound(30_000));
        assertEquals(1_000_000, Searcher.rarityBound(3_000_000));
    }

    @Test
    void netherQueryValidation() {
        int[] none = new int[0];
        assertThrows(IllegalArgumentException.class, () -> new Native.Query(false, -1, Native.Size.ANY, none, none, none, none, 256, 0, 0, Native.Bastion.ANY));
        assertThrows(IllegalArgumentException.class, () -> new Native.Query(false, -1, Native.Size.ANY, none, none, none, none, -1, 20, 0, Native.Bastion.ANY));
        assertThrows(IllegalArgumentException.class, () -> new Native.Query(false, -1, Native.Size.ANY, none, none, none, none, -1, 0, 1001, Native.Bastion.ANY));
        assertThrows(IllegalArgumentException.class, () -> new Native.Query(false, -1, Native.Size.ANY, none, none, none, none, -1, 0, 0, null));
        var seg = Native.toSegment(java.lang.foreign.Arena.ofAuto(),
                new Native.Query(false, -1, Native.Size.ANY, none, none, none, none, 171, 150, 200, Native.Bastion.BRIDGE));
        assertEquals(73 * 4, seg.byteSize());
        assertEquals(171, seg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 69));
        assertEquals(3, seg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 72));
    }

    @Test
    void findsCrimsonArrival() throws Exception {
        Searcher s = new Searcher(new Native.Query(false, -1, Native.Size.ANY, new int[0], new int[0], new int[0], new int[0],
                171, 0, 0, Native.Bastion.ANY), 2);
        assertEquals(171, Native.netherBiome(false, s.result().get(60, TimeUnit.SECONDS)));
    }

    @Test
    void cpuLevels() {
        assertEquals(List.of(4, 8, 15), Arrays.stream(Searcher.Cpu.values()).map(c -> c.threads(16)).toList());
        assertEquals(List.of(1, 1, 1), Arrays.stream(Searcher.Cpu.values()).map(c -> c.threads(2)).toList());
        assertEquals(Searcher.Cpu.LOW, Searcher.Cpu.MAX.next());
    }

    @Test
    void cpuLevelIsRemembered(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("cpu.txt");
        assertEquals(Searcher.Cpu.BALANCED, Searcher.Cpu.load(f)); // missing file: default
        Searcher.Cpu.LOW.save(f);
        assertEquals(Searcher.Cpu.LOW, Searcher.Cpu.load(f));
        Files.writeString(f, "turbo");
        assertEquals(Searcher.Cpu.BALANCED, Searcher.Cpu.load(f));
    }

    @Test
    void pausedThreadsStopSearching() throws Exception {
        Searcher s = new Searcher(biomeOnly(THE_VOID), 2);
        s.setActive(0);
        Thread.sleep(500); // let running batches finish
        long before = s.checked();
        Thread.sleep(500);
        assertEquals(before, s.checked());
        s.setActive(2);
        Thread.sleep(500);
        assertTrue(s.checked() > before);
        s.cancel();
    }
}
