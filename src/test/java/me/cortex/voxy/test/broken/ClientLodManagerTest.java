package me.cortex.voxy.test;

import me.cortex.voxy.client.ClientCacheManager;
import me.cortex.voxy.client.ClientLodManager;
import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClientLodManager} — specifically the in-memory live-sections map and
 * client cache interactions that are reachable without an active network connection.
 *
 * <p>Uses the package-private {@code ClientLodManager(ClientCacheManager)} constructor to
 * inject a test-scoped cache that does not require {@link net.minecraft.client.Minecraft}.
 */
class ClientLodManagerTest {

    @TempDir Path tempDir;

    private ClientCacheManager cache;
    private ClientLodManager   mgr;
    private static final ResourceLocation DIM = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

    @BeforeEach
    void setUp() {
        cache = new ClientCacheManager(tempDir);
        mgr   = new ClientLodManager(cache);
    }

    @AfterEach
    void tearDown() {
        mgr.close();
        cache.close();
    }

    // -------------------------------------------------------------------------

    @Test
    void onSectionDataReceivedPutsInLiveSections() {
        LodSection section = makeSection(0, 1, 1, 5);
        deliver(section);

        assertTrue(mgr.getLiveSections().containsKey(section.key),
                "Received section must appear in live sections map");
        assertEquals(section, mgr.getLiveSections().get(section.key));
    }

    @Test
    void onSectionDataReceivedStoresInCache() {
        LodSection section = makeSection(0, 2, 3, 99);
        deliver(section);

        assertTrue(cache.load(DIM, section.key).isPresent(),
                "Received section must be persisted to client cache");
        assertEquals(section, cache.load(DIM, section.key).get());
    }

    @Test
    void onSectionDataReceivedAccumulatesReceivedBytes() {
        LodSection s1 = makeSection(0, 0, 0, 1);
        LodSection s2 = makeSection(0, 1, 0, 2);
        byte[] data1 = s1.toBytes();
        byte[] data2 = s2.toBytes();

        deliver(s1);
        deliver(s2);

        assertEquals(data1.length + data2.length, mgr.getReceivedBytes(),
                "getReceivedBytes must sum all delivered payload sizes");
    }

    @Test
    void onSectionDataReceivedDecrementsPendingRequests() {
        // Starting value is 0; each delivery decrements by 1
        long before = mgr.getPendingRequests();
        deliver(makeSection(0, 5, 5, 7));
        assertEquals(before - 1, mgr.getPendingRequests(),
                "Each delivery must decrement pendingRequests");
    }

    @Test
    void onSectionRemovedClearsLiveSections() {
        LodSection section = makeSection(0, 3, 3, 8);
        deliver(section);
        mgr.onSectionRemoved(DIM, section.key);

        assertFalse(mgr.getLiveSections().containsKey(section.key),
                "Removed section must be absent from live sections");
    }

    @Test
    void onSectionRemovedClearsCache() {
        LodSection section = makeSection(0, 4, 4, 11);
        deliver(section);
        mgr.onSectionRemoved(DIM, section.key);

        assertTrue(cache.load(DIM, section.key).isEmpty(),
                "Removed section must be absent from client cache");
    }

    @Test
    void closeEmptiesLiveSections() {
        deliver(makeSection(0, 0, 0, 1));
        deliver(makeSection(0, 1, 0, 2));
        assertFalse(mgr.getLiveSections().isEmpty());

        mgr.close();
        assertTrue(mgr.getLiveSections().isEmpty(), "close() must clear the live sections map");
    }

    @Test
    void multipleSectionsAllStoredInLiveMap() {
        for (int i = 0; i < 10; i++) deliver(makeSection(0, i, 0, i + 1));
        assertEquals(10, mgr.getLiveSections().size(),
                "All 10 delivered sections must appear in live sections map");
    }

    @Test
    void deliveringSameSectionTwiceUpdatesNotDuplicates() {
        LodSection original = makeSection(0, 0, 0, 1);
        LodSection updated  = makeSection(0, 0, 0, 99); // same position, different data

        deliver(original);
        deliver(updated);

        assertEquals(1, mgr.getLiveSections().size(),
                "Live map must have exactly one entry for same section key");
        assertEquals(updated, mgr.getLiveSections().get(updated.key),
                "Live map must hold the newest version");
    }

    // -------------------------------------------------------------------------

    /** Simulates a {@link me.cortex.voxy.network.payload.LodSectionDataPayload} arriving. */
    private void deliver(LodSection section) {
        mgr.onSectionDataReceived(DIM, section.key, section.hash, section.toBytes());
    }

    private static LodSection makeSection(int lod, int x, int z, int blockFill) {
        int cells = 16 * 16;
        int[]   bs = new int[cells];
        short[] ht = new short[cells];
        byte[]  lt = new byte[cells];
        int[]   bi = new int[cells];
        for (int i = 0; i < cells; i++) bs[i] = blockFill;
        return new LodSection(SectionKey.encode(lod, x, z), bs, ht, lt, bi);
    }
}
