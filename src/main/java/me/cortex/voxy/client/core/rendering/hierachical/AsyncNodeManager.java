package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.ISectionWatcher;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldSection;
import org.lwjgl.system.MemoryUtil;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.StampedLock;

import static me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData.SECTION_METADATA_SIZE;

//An "async host" for a NodeManager, has specific synchonius entry and exit points
// this is done off thread to reduce the amount of work done on the render thread, improving frame stability and reducing runtime overhead
public class AsyncNodeManager {
    private static final VarHandle RESULT_HANDLE;
    private static final VarHandle RESULT_CACHE_1_HANDLE;
    private static final VarHandle RESULT_CACHE_2_HANDLE;
    static {
        try {
            RESULT_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "results", SyncResults.class);
            RESULT_CACHE_1_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache1", SyncResults.class);
            RESULT_CACHE_2_HANDLE = MethodHandles.lookup().findVarHandle(AsyncNodeManager.class, "resultCache2", SyncResults.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private final Thread thread;
    public final int maxNodeCount;
    private volatile boolean running = true;

    private final NodeManager manager;
    private final BasicAsyncGeometryManager geometryManager;
    private final IGeometryData geometryData;

    private final AtomicInteger workCounter = new AtomicInteger();

    private volatile SyncResults results = null;
    private volatile SyncResults resultCache1 = new SyncResults();
    private volatile SyncResults resultCache2 = new SyncResults();



    //locals for during iteration
    private final IntOpenHashSet tlnIdChange = new IntOpenHashSet();//"Encoded" add/remove id, first bit indicates if its add or remove, 1 is add
    private boolean needsWaitForSync = false;

    public AsyncNodeManager(int maxNodeCount, ISectionWatcher watcher, IGeometryData geometryData) {
        //Note the current implmentation of ISectionWatcher is threadsafe
        //Note: geometry data is the data store/source, not the management, it is just a raw store of data
        // it MUST ONLY be accessed on the render thread
        // AsyncNodeManager will use an AsyncGeometryManager as the manager for the data store, and sync the results on the render thread
        this.geometryData = geometryData;

        this.maxNodeCount = maxNodeCount;
        this.thread = new Thread(()->{
            try {
                while (this.running) {
                    this.run();
                }
            } catch (Exception e) {
                Logger.error("Critical error occurred in async processor, things will be broken", e);
            }
        });
        this.thread.setName("Async Node Manager");

        this.geometryManager = new BasicAsyncGeometryManager(((BasicSectionGeometryData)geometryData).getMaxSectionCount(), ((BasicSectionGeometryData)geometryData).getGeometryCapacity());
        this.manager = new NodeManager(maxNodeCount, this.geometryManager, watcher);
        this.manager.setClear(new NodeManager.ICleaner() {
            @Override
            public void alloc(int id) {

            }

            @Override
            public void move(int from, int to) {

            }

            @Override
            public void free(int id) {

            }
        });
        this.manager.setTLNCallbacks(id->{
            if (!this.tlnIdChange.remove(id)) {
                if (!this.tlnIdChange.add(id|(1<<31))) {
                    throw new IllegalStateException();
                }
            }
        }, id -> {
            if (!this.tlnIdChange.remove(id|(1<<31))) {
                if (!this.tlnIdChange.add(id)) {
                    throw new IllegalStateException();
                }
            }
        });
    }

    private SyncResults getMakeResultObject() {
        SyncResults resultSet = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
        if (resultSet == null) {//Not in the first object
            resultSet = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
        }
        if (resultSet == null) {
            throw new IllegalStateException("There should always be an object in the result set cache pair");
        }
        //Reset everything to default
        resultSet.reset();
        return resultSet;
    }

    private void run() {
        if (this.workCounter.get() == 0) {
            LockSupport.park();
            if (this.workCounter.get() == 0 || !this.running) {//No work
                return;
            }
            //This is a funny thing, wait a bit, this allows for better batching, but this thread is independent of everything else so waiting a bit should be mostly ok
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (!this.running) {
            return;
        }


        int workDone = 0;

        {
            LongOpenHashSet add = null;
            LongOpenHashSet rem = null;
            long stamp = this.tlnLock.writeLock();

            if (!this.tlnAdd.isEmpty()) {
                add = new LongOpenHashSet(this.tlnAdd);
                this.tlnAdd.clear();
            }
            if (!this.tlnRem.isEmpty()) {
                rem = new LongOpenHashSet(this.tlnRem);
                this.tlnRem.clear();
            }

            this.tlnLock.unlockWrite(stamp);
            int work = 0;
            if (rem != null) {
                var iter = rem.longIterator();
                while (iter.hasNext()) {
                    this.manager.removeTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            if (add != null) {
                var iter = add.longIterator();
                while (iter.hasNext()) {
                    this.manager.insertTopLevelNode(iter.nextLong());
                    work++;
                }
            }

            workDone += work;
        }

        do {
            var job = this.childUpdateQueue.poll();
            if (job == null)
                break;
            workDone++;
            this.manager.processChildChange(job.key, job.getNonEmptyChildren());
            job.release();
        } while (true);

        for (int limit = 0; limit < 200; limit++) {//Limit uploading
            var job = this.geometryUpdateQueue.poll();
            if (job == null)
                break;
            workDone++;
            this.manager.processGeometryResult(job);
        }

        for (int limit = 0; limit < 2; limit++) {
            var job = this.requestBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            int count = MemoryUtil.memGetInt(ptr);
            ptr += 8;//Its 8 to keep alignment
            if (job.size < count * 8L + 8) {
                throw new IllegalStateException();
            }
            for (int i = 0; i < count; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;
                this.manager.processRequest(pos);
            }
            job.free();
        }


        do {
            var job = this.removeBatchQueue.poll();
            if (job == null)
                break;
            workDone++;
            long ptr = job.address;
            for (int i = 0; i < NodeCleaner.OUTPUT_COUNT; i++) {
                long pos = ((long) MemoryUtil.memGetInt(ptr)) << 32; ptr += 4;
                pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr)); ptr += 4;

                if (pos == -1) {
                    //TODO: investigate how or what this happens
                    continue;
                }

                this.manager.removeNodeGeometry(pos);
            }
            job.free();
        } while (true);

        if (this.workCounter.addAndGet(-workDone) < 0) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            //Due to synchronization "issues", wait a millis (give up this time slice)
            if (this.workCounter.get() < 0) {
                throw new IllegalStateException("Work counter less than zero");
            }
        }

        if (workDone == 0) {//Nothing happened, which is odd, but just return
            return;
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
        if (this.needsWaitForSync) {
            while (RESULT_HANDLE.get(this) != null && this.running) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }


        var prev = (SyncResults) RESULT_HANDLE.getAndSet(this, null);
        SyncResults results = null;
        if (prev == null) {
            this.needsWaitForSync = false;
            results = this.getMakeResultObject();
            //Clear old data (if it exists), create a new result set
            results.tlnDelta.addAll(this.tlnIdChange);
            this.tlnIdChange.clear();

            results.geometryUploads.putAll(this.geometryManager.getUploads());
            this.geometryManager.getUploads().clear();//Put in new data into sync set
            this.geometryManager.getHeapRemovals().clear();//We dont do removals on new data (as there is "none")
        } else {
            results = prev;
            // merge with the previous result set

            if (!this.tlnIdChange.isEmpty()) {//Merge top level node id changes
                var iter = this.tlnIdChange.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    if (!results.tlnDelta.remove(val ^ (1 << 31))) {//Remove opposite
                        results.tlnDelta.add(val);//Add this if not added
                    }
                }
                this.tlnIdChange.clear();
            }

            if (!this.geometryManager.getHeapRemovals().isEmpty()) {//Remove and free all the removed geometry uploads
                var rem = this.geometryManager.getHeapRemovals();
                var iter = rem.intIterator();
                while (iter.hasNext()) {
                    var buffer = results.geometryUploads.remove(iter.nextInt());
                    if (buffer != null) {
                        buffer.free();
                    }
                }
                rem.clear();
            }

            if (!this.geometryManager.getUploads().isEmpty()) {//Add all the new uploads to the result set
                var add = this.geometryManager.getUploads();
                var iter = add.int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    var prevBuffer = results.geometryUploads.put(val.getIntKey(), val.getValue());
                    if (prevBuffer != null) {
                        prevBuffer.free();
                    }
                }
                add.clear();
            }
        }

        {//This is the same regardless of if is a merge or new result
            //Geometry id metadata updates
            if (!this.geometryManager.getUpdateIds().isEmpty()) {
                var ids = this.geometryManager.getUpdateIds();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    int placeId = results.geometryIdUpdateMap.putIfAbsent(val, results.geometryIdUpdateMap.size());
                    placeId = placeId==-1?results.geometryIdUpdateMap.size()-1:placeId;
                    if (results.geometryIdUpdateData.size<=placeId*32L) {
                        //We need to expand the buffer :(
                        var old = results.geometryIdUpdateData;
                        var newBuffer = new MemoryBuffer((long) (old.size*1.5));
                        Logger.info("Expanding geometry update buffer to " + newBuffer.size);
                        old.cpyTo(newBuffer.address);
                        old.free();
                        results.geometryIdUpdateData = newBuffer;
                    }
                    //Write updated data
                    this.geometryManager.writeMetadata(val, placeId*32L + results.geometryIdUpdateData.address);
                }
                ids.clear();
                this.needsWaitForSync |= results.geometryIdUpdateMap.size()>250;
            }

            //Node updates
            if (!this.manager.getNodeUpdates().isEmpty()) {
                var ids = this.manager.getNodeUpdates();
                var iter = ids.intIterator();
                while (iter.hasNext()) {
                    int val = iter.nextInt();
                    int placeId = results.nodeIdUpdateMap.putIfAbsent(val, results.nodeIdUpdateMap.size());
                    placeId = placeId==-1?results.nodeIdUpdateMap.size()-1:placeId;
                    if (results.nodeIdUpdateData.size<=placeId*16L) {
                        //We need to expand the buffer :(
                        var old = results.nodeIdUpdateData;
                        var newBuffer = new MemoryBuffer((long) (old.size*1.5));
                        Logger.info("Expanding node update buffer to " + newBuffer.size);
                        old.cpyTo(newBuffer.address);
                        old.free();
                        results.nodeIdUpdateData = newBuffer;
                    }
                    //Write updated data
                    this.manager.writeNode(val, placeId*16L + results.nodeIdUpdateData.address);
                }
                ids.clear();
                this.needsWaitForSync |= results.nodeIdUpdateMap.size()>=512;
            }
        }

        results.geometrySectionCount = this.geometryManager.getSectionCount();
        results.currentMaxNodeId = this.manager.getCurrentMaxNodeId();

        if (!RESULT_HANDLE.compareAndSet(this, null, results)) {
            throw new IllegalArgumentException("Should always have null");
        }
    }

    private IntConsumer tlnAddCallback; private IntConsumer tlnRemoveCallback;
    //Render thread synchronization
    public void tick(GlBuffer nodeBuffer) {//TODO: dont pass nodeBuffer here??, do something else thats better
        var results = (SyncResults)RESULT_HANDLE.getAndSet(this, null);//Acquire the results
        if (results == null) {//There are no new results to process, return
            return;
        }

        //top level node add/remove
        if (!results.tlnDelta.isEmpty()) {
            var iter = results.tlnDelta.intIterator();
            while (iter.hasNext()) {
                int val = iter.nextInt();
                if ((val&(1<<31))!=0) {//Add node
                    this.tlnAddCallback.accept(val&(-1>>>1));
                } else {
                    this.tlnRemoveCallback.accept(val);
                }
            }
            //Dont need to clear as is not used again
        }

        boolean doCommit = false;
        {//Update basic geometry data
            var store = (BasicSectionGeometryData)this.geometryData;
            store.setSectionCount(results.geometrySectionCount);

            //Do geometry uploads
            if (!results.geometryUploads.isEmpty()) {
                var iter = results.geometryUploads.int2ObjectEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    var buffer = val.getValue();
                    UploadStream.INSTANCE.upload(store.getGeometryBuffer(), Integer.toUnsignedLong(val.getIntKey()) * 8L, buffer);
                    buffer.free();//Free the buffer was uploading
                }
                doCommit = true;
            }

            //Do geometry id updates
            if (!results.geometryIdUpdateMap.isEmpty()) {
                var iter = results.geometryIdUpdateMap.int2IntEntrySet().fastIterator();
                while (iter.hasNext()) {
                    var val = iter.next();
                    long ptr = UploadStream.INSTANCE.upload(store.getMetadataBuffer(), Integer.toUnsignedLong(val.getIntKey()) * SECTION_METADATA_SIZE, SECTION_METADATA_SIZE);
                    MemoryUtil.memCopy(results.geometryIdUpdateData.address + Integer.toUnsignedLong(val.getIntValue()) * SECTION_METADATA_SIZE, ptr, SECTION_METADATA_SIZE);
                }
                doCommit = true;
            }
        }

        //Do node id updates
        if (!results.nodeIdUpdateMap.isEmpty()) {
            var iter = results.nodeIdUpdateMap.int2IntEntrySet().fastIterator();
            while (iter.hasNext()) {
                var val = iter.next();
                long ptr = UploadStream.INSTANCE.upload(nodeBuffer, Integer.toUnsignedLong(val.getIntKey()) * 16L, 16L);
                MemoryUtil.memCopy(results.nodeIdUpdateData.address + Integer.toUnsignedLong(val.getIntValue()) * 16L, ptr, 16L);
            }
            doCommit = true;
        }


        if (doCommit) {
            UploadStream.INSTANCE.commit();
        }

        //Insert the result set into the cache
        if (!RESULT_CACHE_1_HANDLE.compareAndSet(this, null, results)) {
            //Failed to insert into result set 1, insert it into result set 2
            if (!RESULT_CACHE_2_HANDLE.compareAndSet(this, null, results)) {
                throw new IllegalStateException("Could not insert result into cache");
            }
        }
    }


    public void setTLNAddRemoveCallbacks(IntConsumer add, IntConsumer remove) {
        this.tlnAddCallback = add;
        this.tlnRemoveCallback = remove;
    }

    //==================================================================================================================
    //Incoming events

    //TODO: add atomic counters for each event type probably
    private final ConcurrentLinkedDeque<MemoryBuffer> requestBatchQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<WorldSection> childUpdateQueue = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<BuiltSection> geometryUpdateQueue = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<MemoryBuffer> removeBatchQueue = new ConcurrentLinkedDeque<>();

    private final StampedLock tlnLock = new StampedLock();
    private final LongOpenHashSet tlnAdd = new LongOpenHashSet();
    private final LongOpenHashSet tlnRem = new LongOpenHashSet();

    private void addWork() {
        if (!this.running) throw new IllegalStateException("Not running");
        if (this.workCounter.getAndIncrement() == 0) {
            LockSupport.unpark(this.thread);
        }
    }

    public void submitRequestBatch(MemoryBuffer batch) {//Only called from render thread
        this.requestBatchQueue.add(batch);
        this.addWork();
    }

    public void submitChildChange(WorldSection section) {
        if (!this.running) {
            return;
        }
        section.acquire();//We must acquire the section before putting in the queue
        this.childUpdateQueue.add(section);
        this.addWork();
    }

    public void submitGeometryResult(BuiltSection geometry) {
        if (!this.running) {
            geometry.free();
            return;
        }
        this.geometryUpdateQueue.add(geometry);
        this.addWork();
    }

    public void submitRemoveBatch(MemoryBuffer batch) {//Only called from render thread
        this.removeBatchQueue.add(batch);
        this.addWork();
    }

    public void addTopLevel(long section) {//Only called from render thread
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = this.tlnAdd.add(section)?1:0;
        state -= this.tlnRem.remove(section)?1:0;
        if (state != 0) {
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
    }

    public void removeTopLevel(long section) {//Only called from render thread
        if (!this.running) throw new IllegalStateException("Not running");
        long stamp = this.tlnLock.writeLock();
        int state = this.tlnRem.add(section)?1:0;
        state -= this.tlnAdd.remove(section)?1:0;
        if (state != 0) {
            if (this.workCounter.getAndAdd(state) == 0) {
                LockSupport.unpark(this.thread);
            }
        }
        this.tlnLock.unlockWrite(stamp);
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
        while (true) {
            var buffer = this.requestBatchQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            var buffer = this.requestBatchQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        while (true) {
            var buffer = this.geometryUpdateQueue.poll();
            if (buffer == null) break;
            buffer.free();
        }

        if (RESULT_HANDLE.get(this) != null) {
            var result = (SyncResults)RESULT_HANDLE.getAndSet(this, null);
            result.geometryUploads.forEach((a,b)->b.free());
            result.nodeIdUpdateData.free();
            result.geometryIdUpdateData.free();
        }

        if (RESULT_CACHE_1_HANDLE.get(this) != null) {//Clear cache 1
            var result = (SyncResults)RESULT_CACHE_1_HANDLE.getAndSet(this, null);
            result.nodeIdUpdateData.free();
            result.geometryIdUpdateData.free();
        }
        if (RESULT_CACHE_2_HANDLE.get(this) != null) {//Clear cache 2
            var result = (SyncResults)RESULT_CACHE_2_HANDLE.getAndSet(this, null);
            result.nodeIdUpdateData.free();
            result.geometryIdUpdateData.free();
        }
    }

    //Results object, which is to be synced between the render thread and worker thread
    private static final class SyncResults {
        //Contains
        // geometry uploads and id invalidations and the data
        // node ids to invalidate/update and its data
        // top level node ids to add/remove
        // cleaner move and set operations

        //Node id updates + size
        private final Int2IntOpenHashMap nodeIdUpdateMap = new Int2IntOpenHashMap();//node id to update data location
        private MemoryBuffer nodeIdUpdateData = new MemoryBuffer(8192*2);//capacity for 1024 entries, TODO: ADD RESIZE
        private int currentMaxNodeId;// the id of the ending of the node ids

        //TLN add/rem
        private final IntOpenHashSet tlnDelta = new IntOpenHashSet();

        //Deltas for geometry store
        private int geometrySectionCount;
        private final Int2ObjectOpenHashMap<MemoryBuffer> geometryUploads = new Int2ObjectOpenHashMap<>();
        private final Int2IntOpenHashMap geometryIdUpdateMap = new Int2IntOpenHashMap();//geometry id to update data location
        private MemoryBuffer geometryIdUpdateData = new MemoryBuffer(8192*2);//capacity for 512 entries, TODO: ADD RESIZE


        public SyncResults() {
            this.nodeIdUpdateMap.defaultReturnValue(-1);
            this.geometryIdUpdateMap.defaultReturnValue(-1);
        }

        public void reset() {
            this.nodeIdUpdateMap.clear();
            this.currentMaxNodeId = 0;
            this.tlnDelta.clear();
            this.geometrySectionCount = 0;
            this.geometryUploads.clear();
            this.geometryIdUpdateMap.clear();
        }
    }
}
