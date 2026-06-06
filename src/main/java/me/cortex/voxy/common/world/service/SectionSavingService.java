package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.ConcurrentLinkedDeque;

//TODO: add an option for having synced saving, that is when call enqueueSave, that will instead, instantly
// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?

/**
 * Asynchronous LOD section save queue.
 *
 * Design overview
 * ───────────────
 * WorldSection instances are reference-counted: every caller that holds a reference must call
 * release() when done. When a section is enqueued for saving, this service calls acquire()
 * to pin the section until the disk write completes, then calls release() in processJob().
 *
 * The queue is a ConcurrentLinkedDeque so any thread can enqueue without locking.
 * Drain is handled by a single background Service thread; back-pressure is applied when
 * the queue exceeds SOFT_MAX_QUEUE_SIZE so producers slow down rather than OOM.
 *
 * Shutdown safety
 * ───────────────
 * On shutdown we give the background thread 3 seconds to drain, then forcibly release
 * any remaining enqueued sections.  Without that force-release, the world.isWorldUsed()
 * check in VoxyInstance.shutdown() would spin forever because the pinned sections keep
 * their refcounts above zero.
 */
public class SectionSavingService {

    // If the queue grows past this many jobs, producers yield and help drain it themselves.
    // Prevents unbounded memory growth when the disk is slower than generation.
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;

    // Carries both the WorldEngine (needed for storage.saveSection()) and the section itself.
    // The section was acquire()d at enqueue time so this record owns a ref until release().
    private record SaveEntry(WorldEngine engine, WorldSection section) {}

    // Concurrent deque: any thread enqueues, the service thread pops from the front.
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    public SectionSavingService(ServiceManager sm) {
        // createServiceNoCleanup: the background thread loops on processJob() with a 100 ms
        // idle sleep between batches. "NoCleanup" means it does not run a teardown task on stop.
        this.service = sm.createServiceNoCleanup(() -> this::processJob, 100, "Section saving service");
    }

    /**
     * Background-thread work item: persist one section to the SQLite backend.
     *
     * Flow:
     *  1. Pop from the front of the queue (throws if empty — the Service won't call us unless
     *     a job is available).
     *  2. Call setNotDirty() BEFORE the write so that if another dirty-flag set happens
     *     concurrently, the section is correctly re-queued rather than silently lost.
     *  3. exchangeIsInSaveQueue(false) clears the "am I queued?" flag and returns the old value.
     *     If true, the section has changed since we dequeued it and we must write. If somehow
     *     false (shouldn't happen in normal flow), skip the write to avoid a no-op disk access.
     *  4. release() drops our acquired ref. If this is the last ref, the section is freed.
     */
    private void processJob() {
        var task = this.saveQueue.pop();
        var section = task.section;
        section.assertNotFree(); // sanity: section must still be alive
        try {
            // Clear dirty first — a concurrent re-dirty will re-enqueue, not be swallowed here.
            section.setNotDirty();
            if (section.exchangeIsInSaveQueue(false)) {
                task.engine.storage.saveSection(section);
            }
        } catch (Exception e) {
            Logger.error("Voxy saver had an exception while executing please check logs and report error", e);
        }
        // Release the ref we acquired in enqueueSave(). Must happen even on exception.
        section.release();
    }

    /*
    public void enqueueSave(WorldSection section) {
        if (section._getSectionTracker() != null && section._getSectionTracker().engine != null) {
            this.enqueueSave(section._getSectionTracker().engine, section);
        } else {
            Logger.error("Tried saving world section, but did not have world associated");
        }
    }*/

    /**
     * Enqueue a section for asynchronous saving.
     *
     * @param in                  The WorldEngine that owns the section's storage backend.
     * @param section             The section to save.
     * @param nonBlocking         If true, skip the back-pressure wait even when the queue is full.
     *                            Use when called from the render thread (blocking would stutter).
     * @param sectionAlreadyAcquired  If true, the caller has already acquired the section on our
     *                            behalf so we must not call acquire() again (would double-ref).
     * @return true if the section was newly enqueued; false if it was already in the queue.
     */
    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        // exchangeIsInSaveQueue(true) atomically sets the flag and returns the old value.
        // If the old value was already true, the section is already queued — nothing to do.
        // This prevents duplicate queue entries for the same section under rapid re-dirtying.
        if (section.exchangeIsInSaveQueue(true)) {
            if (!sectionAlreadyAcquired) {
                // Acquire a ref so the section stays alive until processJob() releases it.
                // Without this, the section could be freed between here and the disk write.
                section.acquire();
            }

            // Back-pressure: if the queue is very deep, stall the producer to avoid OOM.
            // Yield first (gives the background thread a scheduling slice), then steal and
            // run jobs ourselves if the queue is still full.  This cooperative drain avoids
            // the producer needing to sleep and keeps throughput high.
            if ((!nonBlocking) && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                Thread.yield();
                /*
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }*/
                // Steal pending work from the service thread and run it on the caller's thread.
                // service.steal() transfers one pending job token to us; processJob() executes it.
                while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                    if (!this.service.steal()) {
                        break;
                    }
                    this.processJob();
                }
            }

            this.saveQueue.add(new SaveEntry(in, section));
            // Signal the service thread that work is available so it wakes from its idle sleep.
            this.service.execute();
            return true;
        }
        return false;
    }

    /** Milliseconds to drain the save queue before aborting on shutdown. */
    private static final long SHUTDOWN_DRAIN_MS = 3_000;

    /**
     * Drain remaining saves and shut down the background thread.
     *
     * Why the force-release loop matters
     * ────────────────────────────────────
     * enqueueSave() calls section.acquire() for every enqueued section.  If the 3-second
     * drain timeout fires while sections are still pending, those acquire() calls are never
     * matched by a release().  WorldSection refcounts remain > 0, so world.isWorldUsed()
     * returns true and VoxyInstance.shutdown() spins in an infinite loop.
     *
     * The saveQueue.poll() loop below releases every remaining acquired ref, allowing the
     * refcounts to reach zero and the shutdown loop to terminate cleanly.  The unsaved
     * sections are not lost permanently — Voxy regenerates them on the next session.
     */
    public void shutdown() {
        int pending = this.service.numJobs();
        if (pending != 0) {
            Logger.info("Voxy section saving: flushing " + pending + " pending sections (3 s budget)…");
            long deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_MS;
            // Poll every 10 ms until the queue is empty or the deadline expires.
            while (this.service.numJobs() > 0 && this.service.isLive()
                    && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            }
            int dropped = this.service.numJobs();
            if (dropped > 0) {
                Logger.warn("Voxy section saving timed out; " + dropped
                        + " sections not persisted (will regenerate next session).");
            }
        }
        // Shut down the background thread (no new jobs will be processed after this).
        this.service.shutdown();

        // Force-release sections still in the queue to unpin their refcounts.
        // This prevents the world.isWorldUsed() loop in VoxyInstance.shutdown() from
        // spinning forever when the 3 s drain deadline was hit with pending saves.
        // Unsaved sections regenerate automatically next session.
        SaveEntry entry;
        int released = 0;
        while ((entry = this.saveQueue.poll()) != null) {
            entry.section().release();
            released++;
        }
        if (released > 0) {
            Logger.warn("Voxy section saving: force-released " + released + " unreleased section refs to allow clean shutdown.");
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
