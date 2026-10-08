package seedfilter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    public Searcher(Native.Query q, int threads) {
        Arena arena = Arena.ofAuto(); // ponytail: GC frees it once all threads exit
        filter = Native.toSegment(arena, q);
        nativeStop = arena.allocate(ValueLayout.JAVA_INT);
        for (int i = 0; i < threads; i++)
            Thread.ofPlatform().daemon().name("seedfilter-" + i).start(this::run);
    }

    private void run() {
        while (!stop.get()) {
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
