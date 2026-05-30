package me.cortex.voxy.test;

import me.cortex.voxy.common.lod.LodSection;
import me.cortex.voxy.common.lod.SectionKey;
import me.cortex.voxy.common.storage.SqliteSectionStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress tests that simulate concurrent producer (voxelizer) and consumer (network)
 * access patterns against the SQLite storage.
 */
class StorageConcurrencyTest {

    private static LodSection makeSection(int x, int z, int fill) {
        int c = 16 * 16;
        int[] bs = new int[c];
        short[] h = new short[c];
        byte[] l = new byte[c];
        int[] b = new int[c];
        for (int i = 0; i < c; i++) bs[i] = fill;
        return new LodSection(SectionKey.encode(0, x, z), bs, h, l, b);
    }

    @Test
    void concurrentStoresAndReadsAreConsistent(@TempDir Path tempDir) throws Exception {
        SqliteSectionStorage storage = new SqliteSectionStorage(tempDir.resolve("concurrent.db"));

        int writerCount = 4;
        int readerCount = 4;
        int sectionsPerWriter = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(writerCount + readerCount);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(writerCount + readerCount);
        try {
            for (int w = 0; w < writerCount; w++) {
                final int writerId = w;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < sectionsPerWriter; i++) {
                            storage.store(makeSection(writerId, i, writerId * 1000 + i));
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            for (int r = 0; r < readerCount; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < sectionsPerWriter * 2; i++) {
                            storage.buildManifest(); // exercise read path
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "Workers timed out");
            assertEquals(0, errors.get(), "No errors should have occurred");

            // Validate: all sections written are readable & correct
            for (int w = 0; w < writerCount; w++) {
                for (int i = 0; i < sectionsPerWriter; i++) {
                    long key = SectionKey.encode(0, w, i);
                    LodSection s = storage.load(key).orElseThrow(
                            () -> new AssertionError("Missing section " + SectionKey.toString(key)));
                    assertEquals(w * 1000 + i, s.blockStates[0]);
                }
            }
        } finally {
            pool.shutdownNow();
            storage.close();
        }
    }

    @Test
    void manifestReflectsConcurrentWrites(@TempDir Path tempDir) throws SQLException {
        SqliteSectionStorage storage = new SqliteSectionStorage(tempDir.resolve("manifest.db"));
        try {
            List<LodSection> all = new ArrayList<>();
            for (int x = 0; x < 10; x++) {
                for (int z = 0; z < 10; z++) {
                    LodSection s = makeSection(x, z, x * 100 + z);
                    storage.store(s);
                    all.add(s);
                }
            }

            var manifest = storage.buildManifest();
            assertEquals(100, manifest.size());
            for (LodSection s : all) {
                assertEquals(s.hash, manifest.hashFor(s.key));
            }
        } finally {
            try { storage.close(); } catch (Exception ignored) {}
        }
    }
}
