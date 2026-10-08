package seedfilter;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import static java.lang.foreign.ValueLayout.*;

/** FFM bindings to seedfilter.dll. Mirrors native/seedfilter.h. */
public final class Native {

    private static MethodHandle search, spawn, spawnSizeH, netherBiomeH, netherNameH, biomeName, structId;
    private static String error;

    private Native() {}

    public static final int MAX_RULES = 16, MAX_NEAR = 16, MIN_DIST = 50, MAX_DIST = 3000, ANY_BIOME = -1;
    public static final int MAX_NETHER_DIST = 1000; // nether blocks; 0 = rule off
    private static final int RULES = 4, NEAR = RULES + 2 * MAX_RULES; // int offsets in SfFilter
    private static final int NETHER = NEAR + 1 + 2 * MAX_NEAR;        // netherBiome, fortressDist, bastionDist, bastionType
    private static final int INTS = NETHER + 4;                       // sizeof(SfFilter) / 4 = 73

    /** Ordinal is SF_SIZE_* in seedfilter.h. */
    public enum Size { ANY, SMALL, MEDIUM, LARGE }

    /** Bastion start piece; native value is ordinal - 1 (SF_BASTION_*, -1 = any). */
    public enum Bastion { ANY, HOUSING, STABLES, TREASURE, BRIDGE }

    public record Query(boolean largeBiomes, int spawnBiome, Size spawnSize,
                        int[] structures, int[] maxDists, int[] nearBiomes, int[] nearDists,
                        int netherBiome, int fortressDist, int bastionDist, Bastion bastionType) {
        /** Overworld-only query. */
        public Query(boolean largeBiomes, int spawnBiome, Size spawnSize,
                     int[] structures, int[] maxDists, int[] nearBiomes, int[] nearDists) {
            this(largeBiomes, spawnBiome, spawnSize, structures, maxDists, nearBiomes, nearDists, ANY_BIOME, 0, 0, Bastion.ANY);
        }

        public Query {
            if (netherBiome < ANY_BIOME || netherBiome > 255) throw new IllegalArgumentException("bad nether biome " + netherBiome);
            for (int d : new int[]{fortressDist, bastionDist})
                if (d != 0 && (d < MIN_DIST || d > MAX_NETHER_DIST)) throw new IllegalArgumentException("bad nether distance " + d);
            if (bastionType == null) throw new IllegalArgumentException("no bastion type");
            if (spawnBiome < ANY_BIOME || spawnBiome > 255) throw new IllegalArgumentException("bad spawn biome " + spawnBiome);
            if (spawnSize == null) throw new IllegalArgumentException("no spawn size");
            if (structures.length != maxDists.length || structures.length > MAX_RULES) throw new IllegalArgumentException("bad rule count");
            for (int s : structures) if (s < 0) throw new IllegalArgumentException("bad structure id " + s);
            if (nearBiomes.length != nearDists.length || nearBiomes.length > MAX_NEAR) throw new IllegalArgumentException("bad nearby count");
            for (int b : nearBiomes) if (b < 0 || b > 255) throw new IllegalArgumentException("bad biome id " + b);
            for (int[] ds : new int[][]{maxDists, nearDists})
                for (int d : ds) if (d < MIN_DIST || d > MAX_DIST) throw new IllegalArgumentException("bad distance " + d);
        }
    }

    public record Spawn(int x, int z, int biome) {}

    public static synchronized void load(Path dll) {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lib = SymbolLookup.libraryLookup(dll, Arena.global());
            MethodHandle se = linker.downcallHandle(lib.findOrThrow("sf_search"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS));
            MethodHandle sp = linker.downcallHandle(lib.findOrThrow("sf_spawn"),
                    FunctionDescriptor.ofVoid(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle ss = linker.downcallHandle(lib.findOrThrow("sf_spawn_size"),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
            MethodHandle nb = linker.downcallHandle(lib.findOrThrow("sf_nether_biome"),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG));
            MethodHandle nn = linker.downcallHandle(lib.findOrThrow("sf_nether_biome_name"),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT));
            MethodHandle bn = linker.downcallHandle(lib.findOrThrow("sf_biome_name"),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT));
            MethodHandle si = linker.downcallHandle(lib.findOrThrow("sf_struct_id"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            search = se; spawn = sp; spawnSizeH = ss; netherBiomeH = nb; netherNameH = nn; biomeName = bn; structId = si;
            error = null;
        } catch (Throwable t) {
            error = t.toString();
        }
    }

    public static boolean isAvailable() { return search != null; }

    /** Jar resource of the native library for this OS / CPU, or empty when no build exists for it. */
    public static Optional<String> resourceFor(String osName, String osArch) {
        String os = osName.startsWith("Windows") ? "windows"
                : osName.startsWith("Mac") || osName.startsWith("Darwin") ? "macos"
                : osName.startsWith("Linux") ? "linux" : null;
        String arch = switch (osArch) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (os == null || arch == null) return Optional.empty();
        String file = switch (os) {
            case "windows" -> "seedfilter.dll";
            case "macos" -> "libseedfilter.dylib";
            default -> "libseedfilter.so";
        };
        return Optional.of("natives/" + os + "-" + arch + "/" + file);
    }

    public static String error() { return error; }

    public static void fail(String message) { error = message; }

    public static MemorySegment toSegment(Arena arena, Query q) {
        MemorySegment m = arena.allocate(JAVA_INT, INTS);
        m.setAtIndex(JAVA_INT, 0, q.largeBiomes() ? 1 : 0);
        m.setAtIndex(JAVA_INT, 1, q.spawnBiome());
        m.setAtIndex(JAVA_INT, 2, q.spawnSize().ordinal());
        m.setAtIndex(JAVA_INT, 3, q.structures().length);
        for (int i = 0; i < q.structures().length; i++) {
            m.setAtIndex(JAVA_INT, RULES + i, q.structures()[i]);
            m.setAtIndex(JAVA_INT, RULES + MAX_RULES + i, q.maxDists()[i]);
        }
        m.setAtIndex(JAVA_INT, NETHER, q.netherBiome());
        m.setAtIndex(JAVA_INT, NETHER + 1, q.fortressDist());
        m.setAtIndex(JAVA_INT, NETHER + 2, q.bastionDist());
        m.setAtIndex(JAVA_INT, NETHER + 3, q.bastionType().ordinal() - 1);
        m.setAtIndex(JAVA_INT, NEAR, q.nearBiomes().length);
        for (int i = 0; i < q.nearBiomes().length; i++) {
            m.setAtIndex(JAVA_INT, NEAR + 1 + i, q.nearBiomes()[i]);
            m.setAtIndex(JAVA_INT, NEAR + 1 + MAX_NEAR + i, q.nearDists()[i]);
        }
        return m;
    }

    /** {@code stop} is a 4-byte flag; the native loop returns early once it is non-zero. */
    public static OptionalLong search(MemorySegment filter, long start, int count, MemorySegment stop) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment out = a.allocate(JAVA_LONG);
            int found = (int) search.invokeExact(filter, start, count, stop, out);
            return found != 0 ? OptionalLong.of(out.get(JAVA_LONG, 0)) : OptionalLong.empty();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static Spawn spawn(boolean largeBiomes, long seed) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment x = a.allocate(JAVA_INT), z = a.allocate(JAVA_INT), b = a.allocate(JAVA_INT);
            spawn.invokeExact(largeBiomes ? 1 : 0, seed, x, z, b);
            return new Spawn(x.get(JAVA_INT, 0), z.get(JAVA_INT, 0), b.get(JAVA_INT, 0));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static Size spawnSize(boolean largeBiomes, long seed) {
        try {
            return Size.values()[(int) spawnSizeH.invokeExact(largeBiomes ? 1 : 0, seed)];
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Nether biome id where a portal built at spawn leads. */
    public static int netherBiome(boolean largeBiomes, long seed) {
        try {
            return (int) netherBiomeH.invokeExact(largeBiomes ? 1 : 0, seed);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static String netherBiomeName(int id) {
        try {
            MemorySegment s = (MemorySegment) netherNameH.invokeExact(id);
            return s.address() == 0 ? null : s.reinterpret(64).getString(0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static String biomeName(int id) {
        try {
            MemorySegment s = (MemorySegment) biomeName.invokeExact(id);
            return s.address() == 0 ? null : s.reinterpret(64).getString(0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static int structureId(String name) {
        try (Arena a = Arena.ofConfined()) {
            return (int) structId.invokeExact(a.allocateFrom(name));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
