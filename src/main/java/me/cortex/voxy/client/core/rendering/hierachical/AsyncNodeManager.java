package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldSection;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

//An "async host" for a NodeManager, has specific synchonius entry and exit points
// this is done off thread to reduce the amount of work done on the render thread, improving frame stability and reducing runtime overhead
public class AsyncNodeManager {
    private static final VarHandle RESULT_HANDLE;
    static {
        try {
            RESULT_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "results", SyncResults.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final Thread thread;
    private final StampedLock lock = new StampedLock();
    private volatile boolean running = true;

    private final NodeManager manager;

    private final AtomicInteger workCounter = new AtomicInteger();

    private volatile SyncResults results = null;

    public AsyncNodeManager(int maxNodeCount, ISectionWatcher watcher) {//Note the current implmentation of ISectionWatcher is threadsafe
        this.thread = new Thread(()->{
            while (this.running) {
                this.run();
            }
            //TODO: cleanup here? maybe?
        });
        this.thread.setName("Async Node Manager");
        //TODO: modify BasicSectionGeometryManager to support async updates
        this.manager = new NodeManager(maxNodeCount, null, watcher);
    }

    private void run() {
        if (this.workCounter.get() == 0) {
            LockSupport.park();
            if (this.workCounter.get() == 0) {//No work
                return;
            }
        }

        if (!this.running) {
            return;
        }

        //TODO: limit the number of jobs based on if the amount of updates to be submitted to the render thread gets to large

        int workDone = 0;
        do {
            var job = this.childUpdateQueue.poll();
            if (job == null)
                break;
            workDone++;
            this.manager.processChildChange(job.key, job.getNonEmptyChildren());
            job.release();
        } while (true);

        do {
            var job = this.geometryUpdateQueue.poll();
            if (job == null)
                break;
            workDone++;
            this.manager.processGeometryResult(job);
        } while (true);

        do {
            var job = this.requestBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            int count = MemoryUtil.memGetInt(ptr); ptr+=4;
            if (job.size < count * 8L + 4) {
                throw new IllegalStateException();
            }
            for (int i = 0; i < count; i++) {
                long pos = ((long)MemoryUtil.memGetInt(ptr))<<32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;
                this.manager.processRequest(pos);
            }
            job.free();
        } while (true);



        do {
            var job = this.removeBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            int count = MemoryUtil.memGetInt(ptr); ptr+=4;
            if (job.size < count * 8L + 4) {
                throw new IllegalStateException();
            }
            for (int i = 0; i < count; i++) {
                long pos = ((long)MemoryUtil.memGetInt(ptr))<<32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;
                this.manager.removeNodeGeometry(pos);
            }
            job.free();
        } while (true);


        if (this.workCounter.addAndGet(-workDone)<0) {
            throw new IllegalStateException("Work counter less than zero");
        }

        //=====================
        //process output events and atomically sync to results



        //Events into manager
        //manager.insertTopLevelNode();
        //manager.removeTopLevelNode();

        //manager.removeNodeGeometry();

        //manager.processRequest();
        //manager.processChildChange();
        //manager.processGeometryResult();


        //Outputs from manager
        //manager.setClear();
        //manager.setTLNCallbacks();

        //manager.writeChanges()



        //Run in a loop, process all the input events, collect the output events merge with previous and publish
        // note: inner event processing is a loop, is.. should be synced to attomic/volatile variable that is being watched
        // when frametime comes around, want to exit out as quick as possible, or make the event publishing
        // "effectivly immediately", that is, atomicly swap out the render side event updates

        //like
        // var current = <new events>
        // var old = getAndSet(this.events, null);
        // if (old != null) {current = merge(old, current);}
        // getAndSet(this.events, current);
        // if (old == null) {cleanAllEventsUpToThisPoint();}//(i.e. clear any buffers or maps containing data revolving around uncommited render thread data events)

        // this creates a lock free event update loop, allowing the render thread to never stall on waiting

        //TODO: NOTE: THIS MUST BE A SINGLE OBJECT THAT IS EXCHANGED
        // for it to be effectivly synchonized all outgoing events/effects _MUST_ happen at the same time
        // for this to be lock free an entire object containing ALL the events that must be synced must be exchanged


        //TODO: also note! this can be done for the processing of rendered out block models!!
        // (it might be able to also be put in this thread, maybe? but is proabably worth putting in own thread for latency reasons)


        var prev = RESULT_HANDLE.getAndSet(this, null);
        //TODO: merge results
        SyncResults results = null;
        if (!RESULT_HANDLE.compareAndSet(this, null, results)) {
            throw new IllegalArgumentException("Should always have null");
        }
        if (prev == null) {
            //Clear
        }
    }

    //==================================================================================================================
    //Incoming events
    //TODO: add atomic counters for each event type probably
    private final ConcurrentLinkedDeque<MemoryBuffer> requestBatchQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<WorldSection> childUpdateQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<BuiltSection> geometryUpdateQueue = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<MemoryBuffer> removeBatchQueue = new ConcurrentLinkedDeque<>();

    private void addWork() {
        if (!this.running) throw new IllegalStateException("Not running");
        this.workCounter.incrementAndGet();
        LockSupport.unpark(this.thread);
    }

    public void submitRequestBatch(MemoryBuffer batch) {
        this.requestBatchQueue.add(batch);
        this.addWork();
    }

    public void submitChildChange(WorldSection section) {
        section.acquire();//We must acquire the section before putting in the queue
        this.childUpdateQueue.add(section);
        this.addWork();
    }

    public void submitGeometryResult(BuiltSection geometry) {
        this.geometryUpdateQueue.add(geometry);
        this.addWork();
    }

    public void submitRemoveBatch(MemoryBuffer batch) {
        this.removeBatchQueue.add(batch);
        this.addWork();
    }

    public void addTopLevel(long section) {

    }

    public void removeTopLevel(long section) {

    }
    //==================================================================================================================

    public void start() {
        this.thread.start();
    }

    public void stop() {
        if (!this.running) {
            throw new IllegalStateException();
        }
        this.running = false;
        LockSupport.unpark(this.thread);
        try {
            while (this.thread.isAlive()) {
                LockSupport.unpark(this.thread);
                this.thread.join(1000);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        //TODO CLEAN
    }

    //Primary synchronization
    public void tick() {
        var results = RESULT_HANDLE.getAndSet(this, null);//Acquire the results
        if (results == null) {//There are no new results to process, return
            return;
        }

    }

    //Results object, which is to be synced between the render thread and worker thread
    private static final class SyncResults {
        //Contains
        // geometry uploads and id invalidations and the data
        // node ids to invalidate/update and its data
        // top level node ids to add/remove
        // cleaner move operations

    }
}
