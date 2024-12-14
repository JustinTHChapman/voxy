package me.cortex.voxy.common.storage;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

public abstract class StorageBackend {
    public abstract void iterateStoredSectionPositions(LongConsumer consumer);

    //Implementation may use the scratch buffer as the return value, it MUST NOT free the scratch buffer
    public abstract MemoryBuffer getSectionData(long key, MemoryBuffer scratch);

    public abstract void setSectionData(long key, MemoryBuffer data);

    public abstract void deleteSectionData(long key);

    public abstract void putIdMapping(int id, ByteBuffer data);

    public abstract Int2ObjectOpenHashMap<byte[]> getIdMappingsData();

    public abstract void flush();

    public abstract void close();

    public List<StorageBackend> getChildBackends() {
        return List.of();
    }

    public final List<StorageBackend> collectAllBackends() {
        List<StorageBackend> backends = new ArrayList<>();
        backends.add(this);
        for (var child : this.getChildBackends()) {
            backends.addAll(child.collectAllBackends());
        }
        return backends;
    }
}
