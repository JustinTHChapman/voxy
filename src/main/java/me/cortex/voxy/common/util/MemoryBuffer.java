package me.cortex.voxy.common.util;

import org.lwjgl.system.MemoryUtil;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MemoryBuffer extends TrackedObject {
    public final long address;
    public final long size;
    private final boolean freeable;

    private static final AtomicInteger COUNT =  new AtomicInteger(0);
    private static final AtomicLong TOTAL_SIZE = new AtomicLong(0);


    public MemoryBuffer(long size) {
        this(true, MemoryUtil.nmemAlloc(size), size, true);
    }

    private MemoryBuffer(boolean track, long address, long size, boolean freeable) {
        super(track);
        this.size = size;
        this.address = address;
        this.freeable = freeable;

        COUNT.incrementAndGet();
        if (freeable) {
            TOTAL_SIZE.addAndGet(size);
        }
    }

    public void cpyTo(long dst) {
        super.assertNotFreed();
        UnsafeUtil.memcpy(this.address, dst, this.size);
    }

    @Override
    public void free() {
        super.free0();

        COUNT.decrementAndGet();
        if (this.freeable) {
            MemoryUtil.nmemFree(this.address);
            TOTAL_SIZE.addAndGet(-this.size);
        } else {
            throw new IllegalArgumentException("Tried to free unfreeable buffer");
        }
    }

    public MemoryBuffer copy() {
        var copy = new MemoryBuffer(false, this.size, size, freeable);
        this.cpyTo(copy.address);
        return copy;
    }

    //Creates a new MemoryBuffer, defunking this buffer and sets the size to be a subsize of the current size
    public MemoryBuffer subSize(long size) {
        if (size > this.size) {
            throw new IllegalArgumentException("Requested size larger than current size");
        }

        //Free the current object, but not the memory associated with it
        this.free0();
        COUNT.decrementAndGet();
        if (this.freeable) {
            TOTAL_SIZE.addAndGet(-this.size);
        }

        return new MemoryBuffer(true, this.address, size, this.freeable);
    }


    //TODO: create like Long(offset) -> value at offset
    // methods for get and set, that way can have a single unifed system to ensure memory access bounds


    public static MemoryBuffer createUntrackedRawFrom(long address, long size) {
        return new MemoryBuffer(false, address, size, true);
    }
    public static MemoryBuffer createUntrackedUnfreeableRawFrom(long address, long size) {
        return new MemoryBuffer(false, address, size, false);
    }

    public static int getCount() {
        return COUNT.get();
    }

    public static long getTotalSize() {
        return TOTAL_SIZE.get();
    }
}
