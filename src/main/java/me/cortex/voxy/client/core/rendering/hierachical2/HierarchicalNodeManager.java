package me.cortex.voxy.client.core.rendering.hierachical2;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.SectionPositionUpdateFilterer;
import me.cortex.voxy.client.core.rendering.building.SectionUpdate;
import me.cortex.voxy.client.core.rendering.section.AbstractSectionGeometryManager;
import me.cortex.voxy.client.core.util.ExpandingObjectAllocationList;
import me.cortex.voxy.common.world.WorldEngine;
import me.jellysquid.mods.sodium.client.util.MathUtil;
import org.lwjgl.system.MemoryUtil;

//Contains no logic to interface with the gpu, nor does it contain any gpu buffers
public class HierarchicalNodeManager {
    public static final int NODE_MSK = ((1<<24)-1);
    private static final int NO_NODE = -1;
    private static final int SENTINAL_TOP_NODE_INFLIGHT = -2;

    private static final int ID_TYPE_MSK = (3<<30);
    private static final int ID_TYPE_NONE = 0;
    private static final int ID_TYPE_LEAF = (2<<30);
    private static final int ID_TYPE_TOP = (1<<30);

    public final int maxNodeCount;
    private final IntOpenHashSet nodeUpdates = new IntOpenHashSet();
    private final NodeStore nodeData;

    //Map from position->id, the top 2 bits contains specifies the type of id
    private final Long2IntOpenHashMap activeSectionMap = new Long2IntOpenHashMap();

    private final ExpandingObjectAllocationList<NodeChildRequest> requests = new ExpandingObjectAllocationList<>(NodeChildRequest[]::new);

    private final AbstractSectionGeometryManager geometryManager;
    private final SectionPositionUpdateFilterer updateFilterer;

    public HierarchicalNodeManager(int maxNodeCount, AbstractSectionGeometryManager geometryManager, SectionPositionUpdateFilterer updateFilterer) {
        if (!MathUtil.isPowerOfTwo(maxNodeCount)) {
            throw new IllegalArgumentException("Max node count must be a power of 2");
        }
        if (maxNodeCount>(1<<24)) {
            throw new IllegalArgumentException("Max node count cannot exceed 2^24");
        }
        this.activeSectionMap.defaultReturnValue(NO_NODE);
        this.updateFilterer = updateFilterer;
        this.maxNodeCount = maxNodeCount;
        this.nodeData = new NodeStore(maxNodeCount);
        this.geometryManager = geometryManager;
    }

    public void insertTopLevelNode(long position) {
        if (this.activeSectionMap.containsKey(position)) {
            throw new IllegalArgumentException("Position already in node set: " + WorldEngine.pprintPos(position));
        }
        this.activeSectionMap.put(position, SENTINAL_TOP_NODE_INFLIGHT);
        this.updateFilterer.watch(position);
    }

    public void removeTopLevelNode(long position) {
        if (!this.activeSectionMap.containsKey(position)) {
            throw new IllegalArgumentException("Position not in node set: " + WorldEngine.pprintPos(position));
        }


    }

    public void processRequestQueue(int count, long ptr) {
        for (int requestIndex = 0; requestIndex < count; requestIndex++) {
            int op = MemoryUtil.memGetInt(ptr + (requestIndex * 4L));
            this.processRequest(op);
        }
    }

    private void processRequest(int op) {
        int node = op&NODE_MSK;
        if (!this.nodeData.nodeExists(node)) {
            throw new IllegalStateException("Tried processing a node that doesnt exist: " + node);
        }
        if (this.nodeData.isNodeRequestInFlight(node)) {
            throw new IllegalStateException("Tried processing a node that already has a request in flight: " + node + " pos: " + WorldEngine.pprintPos(this.nodeData.nodePosition(node)));
        }
        this.nodeData.markRequestInFlight(node);


        //2 branches, either its a leaf node -> emit a leaf request
        // or the nodes geometry must be empty (i.e. culled from the graph/tree) so add to tracker and watch
        if (this.nodeData.isLeafNode(node)) {
            this.makeLeafRequest(node, this.nodeData.getNodeChildExistence(node));
        } else {
            //Verify that the node section is not in the section store. if it is then it is a state desynchonization
            // Note that a section can be "empty" but some of its children might not be
        }
    }

    private void makeLeafRequest(int node, byte childExistence) {
        long pos = this.nodeData.nodePosition(node);

        //Enqueue a leaf expansion request
        var request = new NodeChildRequest(pos);
        int requestId = this.requests.put(request);

        //Only request against the childExistence mask, since the guarantee is that if childExistence bit is not set then that child is guaranteed to be empty
        for (int i = 0; i < 8; i++) {
            if ((childExistence&(1<<i))==0) {
                //Dont watch or enqueue the child node cause it doesnt exist
                continue;
            }
            long childPos = makeChildPos(pos, i);
            request.addChildRequirement(i);
            //Insert all the children into the tracking map with the node id
            if (this.activeSectionMap.put(childPos, requestId|ID_TYPE_LEAF) != NO_NODE) {
                throw new IllegalStateException("Leaf request creation failed to insert child into map as a mapping already existed for the node!");
            }

            //Watch and request the child node at the given position
            if (!this.updateFilterer.watch(childPos)) {
                throw new IllegalStateException("Failed to watch childPos");
            }
        }

        this.nodeData.setNodeRequest(node, requestId);
    }


    public void processResult(SectionUpdate update) {
        //Need to handle cases
        // geometry update, leaf node, leaf request node, internal node
        //Child emptiness update!!! this is the hard bit
        // if it is an internal node
        // if emptiness adds node, need to then send a mesh request and wait
        //  when mesh result, need to remove the old child allocation block and make a new block to fit the
        //  new count of children

        final long position = update.position();
        final var geometryData = update.geometry();
        int nodeId = this.activeSectionMap.get(position);
        if (nodeId == NO_NODE) {
            System.err.println("Received update for section " + WorldEngine.pprintPos(position) + " however section position not in active in map! discarding");
            //Not tracked or mapped to a node!, discard it, it was probably in progress when it was removed from the map
            if (geometryData != null) {
                geometryData.free();
            }
        } else {
            if (nodeId == SENTINAL_TOP_NODE_INFLIGHT) {
                //Special state for top level nodes that are in flight
                if (geometryData == null) {
                    //FIXME: this is a bug, as the child existence could change and have an update sent, resulting in a desync
                    System.err.println("Top level inflight node " + WorldEngine.pprintPos(position) + " got a child msk update but was still in flight! discarding update");
                    return;
                }

                //Allocate a new node id
                nodeId = this.nodeData.allocate();
                this.activeSectionMap.put(position, nodeId|ID_TYPE_TOP);
                int geometry = -1;
                if (!geometryData.isEmpty()) {
                    geometry = this.geometryManager.uploadSection(geometryData);
                } else {
                    geometryData.free();
                }
                this.fillNode(nodeId, position, geometry, update.childExistence());

            } else {
                int type = (nodeId & ID_TYPE_MSK);
                nodeId &= ~ID_TYPE_MSK;
                if (type == ID_TYPE_LEAF) {
                    this.leafDataUpdate(nodeId, update);
                } else if (type == ID_TYPE_NONE || type == ID_TYPE_TOP) {
                    //Not part of a request, just a node update
                } else {
                    throw new IllegalStateException("Should not reach here");
                }
            }
        }
    }

    private void fillNode(int node, long position, int geometry, byte childExistence) {
        this.nodeData.setNodePosition(node, position);
        this.nodeData.setNodeGeometry(node, geometry);
        this.nodeData.setNodeChildExistence(node, childExistence);
    }

    private void leafDataUpdate(int nodeId, SectionUpdate update) {
        var request = this.requests.get(nodeId);
    }




    private int updateNodeGeometry(int node, BuiltSection geometry) {
        int previousGeometry = this.nodeData.getNodeGeometry(node);
        int newGeometry = -1;
        if (previousGeometry != -1) {
            if (!geometry.isEmpty()) {
                newGeometry = this.geometryManager.uploadReplaceSection(previousGeometry, geometry);
            } else {
                this.geometryManager.removeSection(previousGeometry);
            }
        } else {
            if (!geometry.isEmpty()) {
                newGeometry = this.geometryManager.uploadSection(geometry);
            }
        }

        if (previousGeometry != newGeometry) {
            this.nodeData.setNodeGeometry(node, newGeometry);
            this.nodeUpdates.add(node);
        }
        if (previousGeometry == newGeometry) {
            return 0;//No change
        } else if (previousGeometry == -1) {
            return 1;//Became non-empty
        } else {
            return 2;//Became empty
        }
    }

    private void createSingleNode() {

    }

    private static int getChildIdx(long pos) {
        int x = WorldEngine.getX(pos);
        int y = WorldEngine.getY(pos);
        int z = WorldEngine.getZ(pos);
        return (x&1)|((y&1)<<1)|((z&1)<<2);
    }

    private static long makeChildPos(long basePos, int addin) {
        int lvl = WorldEngine.getLevel(basePos);
        if (lvl == 0) {
            throw new IllegalArgumentException("Cannot create a child lower than lod level 0");
        }
        return WorldEngine.getWorldSectionId(lvl-1,
                (WorldEngine.getX(basePos)<<1)|(addin&1),
                (WorldEngine.getY(basePos)<<1)|((addin>>1)&1),
                (WorldEngine.getZ(basePos)<<1)|((addin>>2)&1));
    }
}
