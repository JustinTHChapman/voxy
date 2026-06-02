package me.cortex.voxy.common.config;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.nio.ByteBuffer;

/** Lightweight in-memory IMappingStorage used by the server-side Mapper (no persistence needed). */
public class InMemoryMappingStorage implements IMappingStorage {
    private final Int2ObjectOpenHashMap<byte[]> data = new Int2ObjectOpenHashMap<>();

    @Override
    public synchronized void putIdMapping(int id, ByteBuffer buf) {
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        data.put(id, bytes);
    }

    @Override
    public synchronized Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return new Int2ObjectOpenHashMap<>(data);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
