package com.zoosool.utils;

import java.util.Arrays;
import java.util.HashSet;

/**
 * Fixed-size FIFO ring buffer for long values.
 * Keeps the last N values; evicts the oldest when full.
 *
 * add: O(1)
 * clear: O(1)
 * uniqueCount: O(n) average
 * range: O(n)
 */
public final class LongRingBuffer {

    private final long[] buf;
    private final int capacity;

    // Index of the oldest element
    private int head = 0;

    // Current number of elements (0..capacity)
    private int size = 0;

    public LongRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.buf = new long[capacity];
    }

    /** Configured maximum size (N). */
    public int capacity() {
        return capacity;
    }

    /** Current number of stored elements (<= N). */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public boolean isFull() {
        return size == capacity;
    }

    /**
     * Adds a value. If buffer is full, evicts the oldest value.
     */
    public void add(long value) {
        if (size < capacity) {
            int tail = (head + size) % capacity;
            buf[tail] = value;
            size++;
        } else {
            // overwrite oldest, then move head forward
            buf[head] = value;
            head = (head + 1) % capacity;
        }
    }

    /** Clears buffer content (O(1)). */
    public void clear() {
        head = 0;
        size = 0;
    }

    /** Gets i-th element from oldest to newest. (0 = oldest) */
    public long get(int indexFromOldest) {
        if (indexFromOldest < 0 || indexFromOldest >= size) {
            throw new IndexOutOfBoundsException("index=" + indexFromOldest + ", size=" + size);
        }
        int idx = (head + indexFromOldest) % capacity;
        return buf[idx];
    }

    /** Snapshot ordered from oldest -> newest. */
    public long[] toArray() {
        long[] out = new long[size];
        for (int i = 0; i < size; i++) {
            out[i] = get(i);
        }
        return out;
    }

    /** Counts unique values. */
    public int uniqueCount() {
        if (size == 0) return 0;

        HashSet<Long> set = new HashSet<>(Math.max(16, size * 2));
        for (int i = 0; i < size; i++) {
            set.add(get(i));
        }
        return set.size();
    }

    /**
     * Returns (max - min).
     * Empty or single element buffer => 0.
     */
    public long range() {
        if (size <= 1) return 0L;

        long min = get(0);
        long max = min;

        for (int i = 1; i < size; i++) {
            long v = get(i);
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return max - min;
    }

    @Override
    public String toString() {
        return "LongRingBuffer{capacity=" + capacity +
                ", size=" + size +
                ", values=" + Arrays.toString(toArray()) +
                '}';
    }
}
