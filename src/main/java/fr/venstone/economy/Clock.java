package fr.venstone.economy;

/** Time source for the rolling window. */
public interface Clock {
    /** Milliseconds on a wall-anchored, monotonic timeline. */
    long now();
}
