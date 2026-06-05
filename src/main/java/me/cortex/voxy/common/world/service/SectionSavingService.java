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
public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;
    private record SaveEntry(WorldEngine engine, WorldSection section) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    public SectionSavingService(ServiceManager sm) {
        this.service = sm.createServiceNoCleanup(() -> this::processJob, 100, "Section saving service");
    }

    private void processJob() {
        var task = this.saveQueue.pop();
        var section = task.section;
        section.assertNotFree();
        try {
            //Unmark it dirty here (if it wasnt or w/e) so that it doesnt pointlessly resave (in theory this should be safe to do)
            section.setNotDirty();
            if (section.exchangeIsInSaveQueue(false)) {
                task.engine.storage.saveSection(section);
            }
        } catch (Exception e) {
            Logger.error("Voxy saver had an exception while executing please check logs and report error", e);
        }
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

    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        //If its not enqueued for saving then enqueue it
        if (section.exchangeIsInSaveQueue(true)) {
            if (!sectionAlreadyAcquired) {
                section.acquire(); //Acquire the section for use
            }

            //Hard limit the save count to prevent OOM
            if ((!nonBlocking) && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                //wait a bit
                Thread.yield();
                /*
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }*/
                //If we are still full, process entries in the queue ourselves instead of waiting for the service
                while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                    if (!this.service.steal()) {
                        break;
                    }
                    this.processJob();
                }
            }

            this.saveQueue.add(new SaveEntry(in, section));
            this.service.execute();
            return true;
        }
        return false;
    }

    /** Milliseconds to drain the save queue before aborting on shutdown. */
    private static final long SHUTDOWN_DRAIN_MS = 3_000;

    public void shutdown() {
        int pending = this.service.numJobs();
        if (pending != 0) {
            Logger.info("Voxy section saving: flushing " + pending + " pending sections (3 s budget)…");
            long deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_MS;
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
        this.service.shutdown();
        // Release any sections still in the queue so their refcounts reach zero.
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
