package me.cortex.voxy.client.core.rendering.hierachical2;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.VoxelCore;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.section.AbstractSectionGeometryManager;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.ExpandingObjectAllocationList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.util.MathUtil;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.rendering.hierachical2.NodeStore.NODE_ID_MSK;

public class NodeManager2 {
    //Assumptions:
    // all nodes have children (i.e. all nodes have at least one child existence bit set at all times)
    // leaf nodes always contain geometry (empty geometry counts as geometry (it just doesnt take any memory to store))
    // All nodes except top nodes have parents

    //NOTE:
    // For the queue processing, will need a redirect node-value type
    //      since for inner node child resize gpu could take N frames to update


    private final ExpandingObjectAllocationList<NodeChildRequest> childRequests = new ExpandingObjectAllocationList<>(NodeChildRequest[]::new);
    private final IntOpenHashSet nodeUpdates = new IntOpenHashSet();
    private final AbstractSectionGeometryManager geometryManager;
    private final SectionUpdateRouter updateRouter;
    private final Long2IntOpenHashMap activeSectionMap = new Long2IntOpenHashMap();
    private final NodeStore nodeData;
    public final int maxNodeCount;
    public NodeManager2(int maxNodeCount, AbstractSectionGeometryManager geometryManager, SectionUpdateRouter updateRouter) {
        if (!MathUtil.isPowerOfTwo(maxNodeCount)) {
            throw new IllegalArgumentException("Max node count must be a power of 2");
        }
        if (maxNodeCount>(1<<24)) {
            throw new IllegalArgumentException("Max node count cannot exceed 2^24");
        }
        this.activeSectionMap.defaultReturnValue(-1);
        this.updateRouter = updateRouter;
        this.maxNodeCount = maxNodeCount;
        this.nodeData = new NodeStore(maxNodeCount);
        this.geometryManager = geometryManager;
    }

    public void insertTopLevelNode(long pos) {
    
    }

    public void removeTopLevelNode(long pos) {

    }

    public void processGeometryResult(BuiltSection sectionResult) {

    }

    //============================================================================================================================================
    public void processRequestQueue(int count, long ptr) {
        for (int requestIndex = 0; requestIndex < count; requestIndex++) {
            int op = MemoryUtil.memGetInt(ptr + (requestIndex * 4L));
            this.processRequest(op);
        }
    }

    private void processRequest(int op) {
        int node = op & NODE_ID_MSK;
        if (!this.nodeData.nodeExists(node)) {
            throw new IllegalStateException("Tried processing a node that doesnt exist: " + node);
        }
        if (this.nodeData.isNodeRequestInFlight(node)) {
            Logger.warn("Tried processing a node that already has a request in flight: " + node + " pos: " + WorldEngine.pprintPos(this.nodeData.nodePosition(node)));
            return;
        }
        this.nodeData.markRequestInFlight(node);

    }


    public void processChildChange(long pos, byte childExistence) {

    }

    public boolean writeChanges(GlBuffer nodeBuffer) {
        //TODO: use like compute based copy system or something
        // since microcopies are bad
        if (this.nodeUpdates.isEmpty()) {
            return false;
        }
        for (int i : this.nodeUpdates) {
            this.nodeData.writeNode(UploadStream.INSTANCE.upload(nodeBuffer, i*16L, 16L), i);
        }
        this.nodeUpdates.clear();
        return true;
    }
}
