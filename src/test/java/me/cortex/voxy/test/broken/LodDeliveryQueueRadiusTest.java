package me.cortex.voxy.test;

import me.cortex.voxy.common.lod.SectionKey;
import me.cortex.voxy.server.LodDeliveryQueue;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LodDeliveryQueueRadiusTest {

    @Test
    void radiusKeysProducesCircularPattern() {
        List<Long> keys = LodDeliveryQueue.radiusKeys(0, 0, 3);
        // All keys must be at LOD 0 and within radius 3
        Set<Long> seen = new HashSet<>();
        for (Long k : keys) {
            assertEquals(0, SectionKey.lodLevel(k));
            int x = SectionKey.sectionX(k);
            int z = SectionKey.sectionZ(k);
            assertTrue(x * x + z * z <= 9, "key (" + x + "," + z + ") outside radius");
            assertTrue(seen.add(k), "Duplicate key " + SectionKey.toString(k));
        }
    }

    @Test
    void radiusKeysIncludesCenter() {
        List<Long> keys = LodDeliveryQueue.radiusKeys(10, 20, 5);
        long center = SectionKey.encode(0, 10, 20);
        assertTrue(keys.contains(center), "Must include the center chunk");
    }

    @Test
    void radiusKeysScalesWithRadius() {
        int r1 = LodDeliveryQueue.radiusKeys(0, 0, 1).size();
        int r5 = LodDeliveryQueue.radiusKeys(0, 0, 5).size();
        int r10 = LodDeliveryQueue.radiusKeys(0, 0, 10).size();
        assertTrue(r5 > r1, "Larger radius must include more keys");
        assertTrue(r10 > r5);
        // π*r² approximation: r=10 should be ~314 keys (well under 21x21 = 441)
        assertTrue(r10 < 21 * 21, "Circular filter must drop corners");
        assertTrue(r10 > 200, "But should still be ~π*r² = 314");
    }

    @Test
    void radiusKeysClampsToMinimumOne() {
        List<Long> keys = LodDeliveryQueue.radiusKeys(0, 0, 0);
        assertFalse(keys.isEmpty(), "Radius 0 should still include at least the center");
    }
}
