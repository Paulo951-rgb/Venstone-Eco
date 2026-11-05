package fr.venstone.economy;

/**
 * Wall-anchored monotonic clock.
 *
 * <p>Sale timestamps must be comparable across restarts and immune to wall-clock jumps
 * (NTP corrections, manual changes). The monotonic reading is the authority; the wall
 * anchor is only re-derived when the wall clock disagrees by more than
 * {@link #RESYNC_TOLERANCE_MILLIS}. A backward jump therefore cannot expire lots early and
 * a forward jump cannot keep them alive too long, while a restart still lands on real time.
 */
public final class SystemClock implements Clock {
    /** Wall-clock drift tolerated before the anchor moves to keep timestamps truthful. */
    private static final long RESYNC_TOLERANCE_MILLIS = 5_000L;

    private static final SystemClock INSTANCE = new SystemClock();

    private final long monotonicOrigin = System.nanoTime() / 1_000_000L;
    private final long wallOrigin = System.currentTimeMillis();

    private long anchorOffset;

    private SystemClock() { }

    public static SystemClock get() { return INSTANCE; }

    @Override public synchronized long now() {
        long wall = wallOrigin + (System.nanoTime() / 1_000_000L - monotonicOrigin);
        long drift = System.currentTimeMillis() - wall;
        if (Math.abs(drift) > RESYNC_TOLERANCE_MILLIS) anchorOffset += drift;
        return wall + anchorOffset;
    }
}
