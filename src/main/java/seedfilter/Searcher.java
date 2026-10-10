package seedfilter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** Runs Native.search on N daemon threads over consecutive batches from a random start seed. */
public final class Searcher {
    static final int BATCH = 256; // native code also checks the stop flag before every seed

    private final MemorySegment filter;
    private final MemorySegment nativeStop; // mirrors `stop` for the C loop
    private final AtomicLong next = new AtomicLong(ThreadLocalRandom.current().nextLong());
    private final AtomicLong checked = new AtomicLong();
    private final AtomicBoolean stop = new AtomicBoolean();
    private final CompletableFuture<Long> result = new CompletableFuture<>();
    private final long startNanos = System.nanoTime();
    private volatile int active; // threads with an index below this search, the rest wait (see setActive)

    /** How much of the CPU a search may use. Search threads always run at the lowest OS priority as well. */
    public enum Cpu {
        LOW("Low"), BALANCED("Balanced"), MAX("Max");

        public final String label;

        Cpu(String label) { this.label = label; }

        /** Search threads on a machine with {@code cores} logical cores: a quarter, half, or all but one. */
        public int threads(int cores) {
            return Math.max(1, switch (this) {
                case LOW -> cores / 4;
                case BALANCED -> cores / 2;
                case MAX -> cores - 1;
            });
        }

        public Cpu next() { return values()[(ordinal() + 1) % values().length]; }

        public static Cpu load(Path file) {
            try {
                return valueOf(Files.readString(file).strip());
            } catch (Exception e) { // missing or edited by hand
                return BALANCED;
            }
        }

        public void save(Path file) {
            try {
                Files.writeString(file, name());
            } catch (Exception ignored) { // only a preference
            }
        }
    }

    /** Starts {@code threads} search threads, all of them active. */
    public Searcher(Native.Query q, int threads) {
        Arena arena = Arena.ofAuto(); // ponytail: GC frees it once all threads exit
        filter = Native.toSegment(arena, q);
        nativeStop = arena.allocate(ValueLayout.JAVA_INT);
        active = threads;
        for (int i = 0; i < threads; i++) {
            int index = i;
            // lowest priority: other programs (and the game) get the CPU first
            Thread.ofPlatform().daemon().priority(Thread.MIN_PRIORITY).name("seedfilter-" + i).start(() -> run(index));
        }
    }

    /** Changes how many of the started threads search; takes effect within one batch. */
    public void setActive(int threads) { active = threads; }

    private void run(int index) {
        while (!stop.get()) {
            if (index >= active) { // paused by a lower CPU setting
                LockSupport.parkNanos(50_000_000);
                continue;
            }
            OptionalLong hit = Native.search(filter, next.getAndAdd(BATCH), BATCH, nativeStop);
            checked.addAndGet(BATCH);
            if (hit.isPresent()) offer(hit.getAsLong());
        }
    }

    /** Delivers a hit unless the search was already stopped (cancelled or another thread won). */
    void offer(long seed) {
        if (!stop.getAndSet(true)) result.complete(seed);
        stopNative();
    }

    public CompletableFuture<Long> result() { return result; }

    public long checked() { return checked.get(); }

    public long seedsPerSecond() {
        double s = (System.nanoTime() - startNanos) / 1e9;
        return s < 0.1 ? 0 : (long) (checked.get() / s);
    }

    /**
     * With no match after {@code checked} seeds, matches are rarer than about 1 in the returned number
     * (95% "rule of three"); 0 while too few seeds were checked to say anything.
     */
    static long rarityBound(long checked) {
        return checked < 20_000 ? 0 : checked / 3;
    }

    public long rarityBound() { return rarityBound(checked.get()); }

    public long elapsedSeconds() { return (System.nanoTime() - startNanos) / 1_000_000_000L; }

    public void cancel() {
        stop.set(true);
        stopNative();
    }

    private void stopNative() {
        STOP.setVolatile(nativeStop, 0L, 1);
    }

    private static final VarHandle STOP = ValueLayout.JAVA_INT.varHandle();
}
