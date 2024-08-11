package me.cortex.voxy.client.core.rendering.hierachical2;

import me.cortex.voxy.common.util.HierarchicalBitSet;

public final class NodeStore {
    private static final int LONGS_PER_NODE = 4;
    private static final int INCREMENT_SIZE = 1<<16;
    private final HierarchicalBitSet allocationSet;
    private long[] localNodeData;
    public NodeStore(int maxNodeCount) {
        //Initial count is 1024
        this.localNodeData = new long[INCREMENT_SIZE*LONGS_PER_NODE];
        this.allocationSet = new HierarchicalBitSet(maxNodeCount);
    }

    public int allocate() {
        int id = this.allocationSet.allocateNext();
        if (id < 0) {
            throw new IllegalStateException("Failed to allocate node slot!");
        }
        this.ensureSized(id);
        return id;
    }

    public int allocate(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Count cannot be <= 0");
        }
        int id = this.allocationSet.allocateNextConsecutiveCounted(count);
        if (id < 0) {
            throw new IllegalStateException("Failed to allocate " + count + " consecutive nodes!!");
        }
        this.ensureSized(id + (count-1));
        return id;
    }

    //Ensures that index is within the array, if not, resizes to contain it + buffer zone
    private void ensureSized(int index) {
        if (index*LONGS_PER_NODE > this.localNodeData.length) {
            int newSize = Math.min((index+INCREMENT_SIZE), this.allocationSet.getLimit());

            long[] newStore = new long[newSize * LONGS_PER_NODE];
            System.arraycopy(this.localNodeData, 0, newStore, 0, this.localNodeData.length);
            this.localNodeData = newStore;
        }
    }




    public long nodePosition(int nodeId) {
        return this.localNodeData[nodeId<<2];
    }

    public boolean nodeExists(int nodeId) {
        return false;
    }


    public boolean hasGeometry(int node) {
        return false;
    }
    public int getNodeGeometry(int node) {
        return 0;
    }
    public void setNodeGeometry(int node, int geometryId) {

    }


    public void markRequestInFlight(int nodeId) {

    }

    public boolean nodeRequestInFlight(int nodeId) {
        return false;
    }

    public boolean isLeafNode(int nodeId) {
        return false;
    }

    public byte getNodeChildExistence(int nodeId) {return 0;}


    //Writes out a nodes data to the ptr in the compacted/reduced format
    public void writeNode(long ptr, int nodeId) {

    }
}
