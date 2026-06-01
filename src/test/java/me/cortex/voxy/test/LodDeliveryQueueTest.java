package me.cortex.voxy.test;

import me.cortex.voxy.common.lod.SectionKey;
import me.cortex.voxy.server.LodDeliveryQueue;
import me.cortex.voxy.server.ServerLodManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LodDeliveryQueue} — queue management and tick-delivery behaviour.
 *
 * <p>Uses the package-private {@code enqueue(UUID, …)} and
 * {@code enqueueAdditional(UUID, …)} overloads so no {@link net.minecraft.server.level.ServerPlayer}
 * instance is needed.
 *
 * <p><b>Important:</b> {@link LodDeliveryQueue#TASKS} is static, so each test starts with a
 * {@code @BeforeEach} clear to prevent cross-test contamination.
 */
class LodDeliveryQueueTest {

    @TempDir Path tempDir;

    private ServerLodManager  lodManager;
    private LodDeliveryQueue  queue;

    private static final ResourceLocation DIM  = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final ResourceLocation DIM2 = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

    @BeforeEach
    void setUp() {
        LodDeliveryQueue.TASKS.clear(); // TASKS is static — always reset before each test
        lodManager = new ServerLodManager(tempDir);
        queue      = lodManager.getDeliveryQueue();
    }

    @AfterEach
    void tearDown() {
        lodManager.close();
        LodDeliveryQueue.TASKS.clear();
    }

    // -------------------------------------------------------------------------
    // enqueue
    // -------------------------------------------------------------------------

    @Test
    void enqueueCreatesTask() {
        UUID id = UUID.randomUUID();
        queue.enqueue(id, "Alice", DIM, keysOf(1, 2, 3));

        assertEquals(3, queue.getRemaining(id), "Queue must hold all 3 enqueued keys");
        assertTrue(LodDeliveryQueue.TASKS.containsKey(id), "TASKS map must contain the player entry");
    }

    @Test
    void enqueueReplacesExistingQueue() {
        UUID id = UUID.randomUUID();
        queue.enqueue(id, "Bob", DIM, keysOf(1, 2, 3, 4, 5));
        queue.enqueue(id, "Bob", DIM, keysOf(10, 20));

        assertEquals(2, queue.getRemaining(id),
                "Second enqueue must replace the first — only 2 keys remain");
    }

    @Test
    void enqueueTwoDifferentPlayersAreIndependent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        queue.enqueue(a, "Alice", DIM, keysOf(1));
        queue.enqueue(b, "Bob",   DIM, keysOf(2, 3, 4));

        assertEquals(1, queue.getRemaining(a));
        assertEquals(3, queue.getRemaining(b));
    }

    // -------------------------------------------------------------------------
    // enqueueAdditional
    // -------------------------------------------------------------------------

    @Test
    void enqueueAdditionalCreatesNewIfNone() {
        UUID id = UUID.randomUUID();
        queue.enqueueAdditional(id, "Carol", DIM, keysOf(5, 6));

        assertEquals(2, queue.getRemaining(id), "Must create new queue when none exists");
    }

    @Test
    void enqueueAdditionalAppendsToExistingQueue() {
        UUID id = UUID.randomUUID();
        queue.enqueue(id, "Dave", DIM, keysOf(1, 2));
        queue.enqueueAdditional(id, "Dave", DIM, keysOf(3, 4, 5));

        assertEquals(5, queue.getRemaining(id), "Must append to existing queue — total 5 keys");
    }

    @Test
    void enqueueAdditionalReplacesOnDimensionChange() {
        UUID id = UUID.randomUUID();
        queue.enqueue(id, "Eve", DIM, keysOf(1, 2, 3));
        // Different dimension triggers replacement
        queue.enqueueAdditional(id, "Eve", DIM2, keysOf(10));

        assertEquals(1, queue.getRemaining(id),
                "enqueueAdditional with different dimension must replace the queue (only 1 key)");
        assertEquals(DIM2, LodDeliveryQueue.TASKS.get(id).dimension,
                "Task dimension must be updated to the new dimension");
    }

    // -------------------------------------------------------------------------
    // clear / getRemaining
    // -------------------------------------------------------------------------

    @Test
    void clearRemovesTask() {
        UUID id = UUID.randomUUID();
        queue.enqueue(id, "Frank", DIM, keysOf(1, 2, 3));
        queue.clear(id);

        assertFalse(LodDeliveryQueue.TASKS.containsKey(id), "Task must be removed after clear");
        assertEquals(0, queue.getRemaining(id), "getRemaining must return 0 for cleared player");
    }

    @Test
    void getRemainingReturnsZeroForUnknownPlayer() {
        UUID id = UUID.randomUUID();
        assertEquals(0, queue.getRemaining(id), "getRemaining for unknown UUID must return 0, not throw");
    }

    // -------------------------------------------------------------------------
    // tickDeliver — offline-player removal
    // -------------------------------------------------------------------------

    @Test
    void tickDeliverRemovesTaskWhenPlayerOffline() {
        UUID id = UUID.randomUUID();
        queue.enqueue(id, "Grace", DIM, keysOf(1, 2, 3));

        // playerLookup returns null → player is offline
        queue.tickDeliver(uuid -> null, 10);

        assertFalse(LodDeliveryQueue.TASKS.containsKey(id),
                "Offline player task must be removed during tickDeliver");
    }

    @Test
    void tickDeliverRemovesMultipleOfflinePlayers() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        queue.enqueue(a, "Hank", DIM, keysOf(1));
        queue.enqueue(b, "Iris", DIM, keysOf(2, 3));

        queue.tickDeliver(uuid -> null, 10);

        assertTrue(LodDeliveryQueue.TASKS.isEmpty(),
                "All offline player tasks must be removed");
    }

    @Test
    void tickDeliverEmptyQueueDoesNotThrow() {
        // No tasks — must not throw
        assertDoesNotThrow(() -> queue.tickDeliver(uuid -> null, 10));
    }

    // -------------------------------------------------------------------------

    /** Creates a list of LOD-0 section keys from raw integers (used as proxies). */
    private static List<Long> keysOf(int... values) {
        java.util.List<Long> list = new java.util.ArrayList<>(values.length);
        for (int v : values) list.add(SectionKey.encode(0, v, 0));
        return list;
    }
}
