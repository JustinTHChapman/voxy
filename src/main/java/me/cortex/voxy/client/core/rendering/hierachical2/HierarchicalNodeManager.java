package me.cortex.voxy.client.core.rendering.hierachical2;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.section.AbstractSectionGeometryManager;
import me.cortex.voxy.client.core.util.ExpandingObjectAllocationList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.jellysquid.mods.sodium.client.util.MathUtil;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.rendering.hierachical2.NodeStore.EMPTY_GEOMETRY_ID;
import static me.cortex.voxy.client.core.rendering.hierachical2.NodeStore.NODE_ID_MSK;

//Contains no logic to interface with the gpu, nor does it contain any gpu buffers
public class HierarchicalNodeManager {
    /**
     * Rough explanation to help with confusion,
     * All leaf nodes (i.e. nodes that have no children currently existing), all have a geometry node attached (if its non empty)
     *  note! on the gpu, it does not need the child ptr unless it tries to go down, that is, when creating/uploading to gpu
     *  dont strictly need all child nodes
     * Internal nodes are nodes that have children and parents, they themself may or may not have geometry attached
     * Top level nodes, dont have parents but other than that inherit all the properties of internal nodes
     *
     * The way the traversal works is as follows
     *  Top level nodes are source nodes, they are the inital queue
     *  * For each node in the queue, compute the screenspace size of the node, check against the hiz buffer
     *  * If the node is < rendering screensize
     *      * If the node has geometry and not empty
     *          * Queue the geometry for rendering
     *      * If the node is missing geometry and is marked as not empty
     *          * Attempt to put node ID into request queue
     *          * If node has children
     *              * put children into traversal queue
     *          * Else
     *              * Technically an error state, as we should _always_ either have children or geometry
     *      * Else
     *          * Dont do anything as section is explicit empty render data
     *  * Else if node is > rendering screensize, need to subdivide
     *      * If Child ptr is not empty (i.e. is an internal node)
     *          * Enqueue children into traversal queue
     *      * If child ptr is empty (i.e. is leaf node) and is not base LoD level
     *          * Attempt to put node ID into request queue
     *          * If node has geometry, or is explicitly marked as empty render data
     *              * Queue own geometry (if non empty)
     *          * Else
     *              * Technically an error state, as a leaf node should always have geometry (or marked as empty geometry data)
     */

    //There are a few properties of the class that can be used for verification
    // a position cannot be both in a request and have an associated node, it must be none, in a request or a node
    // leaf nodes cannot have a childptr
    // updateRouter tracking child events should be the same set as activeSectionMap
    // any allocated indices in requests are not finished/are in flight
    // for all requests there must be an associated and valid node in the activeSectionMap
    // for all requests the parent node must exist and cannot be ID_TYPE_REQUEST
    // for all inner nodes, if there are no requests the children nodes must match the nodes childExistence bitset
    //  if there is a request, it should be the delta to get from the old children to the new children
    // no node can have an empty childExistence (except for top level nodes)

    private static final int NO_NODE = -1;

    private static final int ID_TYPE_MSK = (3<<30);
    private static final int ID_TYPE_LEAF = (3<<30);
    private static final int ID_TYPE_INNER = 0;
    private static final int ID_TYPE_REQUEST = (2<<30);
    //private static final int ID_TYPE_TOP = (1<<30);

    public final int maxNodeCount;
    private final IntOpenHashSet nodeUpdates = new IntOpenHashSet();
    private final NodeStore nodeData;

    //Map from position->id, the top 2 bits contains specifies the type of id
    private final Long2IntOpenHashMap activeSectionMap = new Long2IntOpenHashMap();

    private final ExpandingObjectAllocationList<NodeChildRequest> requests = new ExpandingObjectAllocationList<>(NodeChildRequest[]::new);

    private final AbstractSectionGeometryManager geometryManager;
    private final SectionUpdateRouter updateRouter;

    public HierarchicalNodeManager(int maxNodeCount, AbstractSectionGeometryManager geometryManager, SectionUpdateRouter updateRouter) {
        if (!MathUtil.isPowerOfTwo(maxNodeCount)) {
            throw new IllegalArgumentException("Max node count must be a power of 2");
        }
        if (maxNodeCount>(1<<24)) {
            throw new IllegalArgumentException("Max node count cannot exceed 2^24");
        }
        this.activeSectionMap.defaultReturnValue(NO_NODE);
        this.updateRouter = updateRouter;
        this.maxNodeCount = maxNodeCount;
        this.nodeData = new NodeStore(maxNodeCount);
        this.geometryManager = geometryManager;
    }

    public void insertTopLevelNode(long position) {
        if (this.activeSectionMap.containsKey(position)) {
            throw new IllegalArgumentException("Position already in node set: " + WorldEngine.pprintPos(position));
        }

        int id = this.nodeData.allocate();
        this.nodeData.setNodePosition(id, position);
        this.activeSectionMap.put(position, id|ID_TYPE_LEAF);//ID_TYPE_TOP
        this.updateRouter.watch(position, WorldEngine.UPDATE_FLAGS);
    }

    public void removeTopLevelNode(long position) {
        if (!this.activeSectionMap.containsKey(position)) {
            throw new IllegalArgumentException("Position not in node set: " + WorldEngine.pprintPos(position));
        }
    }

    private void removeSectionInternal(long position) {
        int node = this.activeSectionMap.remove(position);
        if (node == NO_NODE) {
            throw new IllegalArgumentException("Tried removing node but it didnt exist: " + WorldEngine.pprintPos(position));
        }

        int type = (node & ID_TYPE_MSK);
        node &= ~ID_TYPE_MSK;
        if (type == ID_TYPE_REQUEST) {
            //TODO: THIS

        } else if (type == ID_TYPE_INNER) {// || type == ID_TYPE_TOP
            if (!this.nodeData.nodeExists(node)) {
                throw new IllegalStateException("Section in active map but not in node data");
            }
            if (this.nodeData.isNodeRequestInFlight(node)) {
                int requestId = this.nodeData.getNodeRequest(node);
                var request = this.requests.get(requestId);
                if (request.getPosition() != position) {
                    throw new IllegalStateException("Position != request.position");
                }

                //Recurse into all child requests and remove them, free any geometry along the way
                //this.removeSectionInternal(position)
            }

            //Recurse into all allocated, children and remove
            int children = this.nodeData.getChildPtr(node);
            if (children != NO_NODE) {
                int count = Integer.bitCount(Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(node)));
                for (int i = 0; i < count; i++) {
                    int cid = children + i;
                    if (!this.nodeData.nodeExists(cid)) {
                        throw new IllegalStateException("Child node doesnt exist!");
                    }
                }
            }

            int geometry = this.nodeData.getNodeGeometry(node);
            if (geometry != EMPTY_GEOMETRY_ID) {
                this.geometryManager.removeSection(geometry);
            }
        }

        //After its been removed, if its _not_ a top level node or inflight request but just a normal node,
        // go up to parent and remove node from the parent allocation and free node id

    }

    //============================================================================================================================================
    public void processRequestQueue(int count, long ptr) {
        for (int requestIndex = 0; requestIndex < count; requestIndex++) {
            int op = MemoryUtil.memGetInt(ptr + (requestIndex * 4L));
            this.processRequest(op);
        }
    }

    private void processRequest(int op) {
        int node = op& NODE_ID_MSK;
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
            if (this.activeSectionMap.put(childPos, requestId|ID_TYPE_REQUEST) != NO_NODE) {
                throw new IllegalStateException("Leaf request creation failed to insert child into map as a mapping already existed for the node!");
            }

            //Watch and request the child node at the given position
            if (!this.updateRouter.watch(childPos, WorldEngine.UPDATE_FLAGS)) {
                throw new IllegalStateException("Failed to watch childPos");
            }
        }

        this.nodeData.setNodeRequest(node, requestId);
    }



    //============================================================================================================================================
    public void processChildChange(long position, byte childExistence) {
        if (childExistence == 0) {
            Logger.logError("Section at " + WorldEngine.pprintPos(position) + " had empty child existence!!");
        }
        int nodeId = this.activeSectionMap.get(position);
        if (nodeId == NO_NODE) {
            System.err.println("Received child change for section " + WorldEngine.pprintPos(position) + " however section position not in active in map! discarding");
        } else {
            int type = (nodeId & ID_TYPE_MSK);
            nodeId &= ~ID_TYPE_MSK;
            if (type == ID_TYPE_REQUEST) {
                //Doesnt result in an invalidation as we must wait for geometry to create a child
                this.requests.get(nodeId).putChildResult(getChildIdx(position), childExistence);
            } else if (type == ID_TYPE_LEAF || type == ID_TYPE_INNER) {// || type == ID_TYPE_TOP
                if (this.nodeData.getNodeChildExistence(nodeId) == childExistence) {
                    //Dont need to update the internal state since it is the same
                    return;
                }

                //ALSO: TODO: HERE: if a child is removed, need to remove it and all children accociated

                //If its an inner node and doesnt have an inflight request, create an empty request, this will get autofilled by the following part
                if (type == ID_TYPE_INNER) {

                }
                //If its a top level node, it needs a request? aswell, no no it doesnt :tm: what could do tho, is if it has a request in progress, update it (already handled)
                // _or_ if the top level node has a child ptr then also handle it??
                // the issue is that top nodes probably need an extra state like "shouldMakeChildren" or something
                // cause if a top level node, that has no children, requests a decent from the gpu
                // but there are no children so no decent is made, if a child update is added to that section,
                // the update will be ignored since the cpu doesnt know the gpu still wants it

                //If its a leaf node, its fine, dont need to create a request, only need to update the node msk


                if (this.nodeData.isNodeRequestInFlight(nodeId)) {
                    int reqId = this.nodeData.getNodeRequest(nodeId);
                    var request = this.requests.get(reqId);
                    byte reqMsk = request.getMsk();
                    if (reqMsk != childExistence) {
                        //Only need to change if its not the same
                        byte toRem = (byte) ((~childExistence)&reqMsk);
                        if (toRem != 0) {
                            //Remove nodes from the request
                            for (int i = 0; i < 8; i++) {//TODO: swap out for numberOfTrailingZeros loop thing
                                if ((toRem&(i<<1))==0) continue;
                                int geometry = request.removeAndUnRequire(i);
                                long cpos = makeChildPos(position, i);
                                //Remove from update router and activeSectionMap
                                int cid = this.activeSectionMap.remove(cpos);
                                if ((cid&ID_TYPE_MSK)!=ID_TYPE_REQUEST || (cid&~ID_TYPE_MSK)!=reqId) {
                                    throw new IllegalStateException(WorldEngine.pprintPos(position)+" " + i + " " + cid + " " + reqId);
                                }
                                ///Remove all from update router
                                if (!this.updateRouter.unwatch(cpos, WorldEngine.UPDATE_FLAGS)) {
                                    throw new IllegalStateException();
                                }
                                //Release geometry if it had any
                                if (geometry != -1) {
                                    this.geometryManager.removeSection(geometry);
                                }
                            }
                        }

                        byte toAdd = (byte) ((~reqMsk)&childExistence);

                        //This also needs to be with respect to the nodes current childexistance status as some sections are already watched/have nodes, dont change those
                        toAdd &= (byte) ~this.nodeData.getNodeChildExistence(nodeId);

                        if (toAdd != 0) {
                            //Add nodes to the request that dont exist in the node already
                            for (int i = 0; i < 8; i++) {//TODO: swap out for numberOfTrailingZeros loop thing
                                if ((toAdd & (i << 1)) == 0) continue;
                                request.addChildRequirement(i);
                                long cpos = makeChildPos(position, i);
                                int prev = this.activeSectionMap.put(cpos, ID_TYPE_REQUEST|reqId);
                                if (prev!=-1) {
                                    throw new IllegalStateException("Child is already mapped to a node id " + WorldEngine.pprintPos(cpos) + " " + reqId + " " + prev);
                                }
                                if (!this.updateRouter.watch(cpos, WorldEngine.UPDATE_FLAGS)) {
                                    throw new IllegalStateException("Couldn't watch chunk section");
                                }
                            }
                        }

                        if (request.getMsk() != childExistence) {
                            throw new IllegalStateException();
                        }

                        //If the request is satisfied after changes, consume it
                        if (request.isSatisfied()) {
                            this.consumeFinishedNodeChildRequest(nodeId, request);
                        }
                    }
                }

                //Need to update the node itself, only if the node has children does it need to update the child ptr information tho
            } else {
                throw new IllegalStateException("Should not reach here");
            }
        }
    }

    public void processGeometryResult(BuiltSection section) {
        final long position = section.position;
        int nodeId = this.activeSectionMap.get(position);
        if (nodeId == NO_NODE) {
            System.err.println("Received geometry for section " + WorldEngine.pprintPos(position) + " however section position not in active in map! discarding");
            //Not tracked or mapped to a node!, discard it, it was probably in progress when it was removed from the map
            section.free();
        } else {
            int type = (nodeId & ID_TYPE_MSK);
            nodeId &= ~ID_TYPE_MSK;
            if (type == ID_TYPE_REQUEST) {
                var request = this.requests.get(nodeId);
                //Update for section part of a request, the request may be a leaf request update or an inner node update
                int child = getChildIdx(position);
                int prev = request.putChildResult(child, this.geometryManager.uploadSection(section));
                if (prev != -1) {
                    this.geometryManager.removeSection(prev);
                }
                if (request.isSatisfied()) {
                    this.consumeFinishedNodeChildRequest(nodeId, request);
                }
            } else if (type == ID_TYPE_INNER || type == ID_TYPE_LEAF) {//type == ID_TYPE_TOP ||
                //Update the node geometry, and enqueue if it changed
                if (this.updateNodeGeometry(nodeId, section) != 0) {//TODO: might need to mark the node as empty geometry or something
                    this.nodeUpdates.add(nodeId);
                }
            } else {
                throw new IllegalStateException("Should not reach here");
            }
        }
    }

    private int updateNodeGeometry(int node, BuiltSection geometry) {
        int previousGeometry = this.nodeData.getNodeGeometry(node);
        int newGeometry = EMPTY_GEOMETRY_ID;
        if (previousGeometry != EMPTY_GEOMETRY_ID) {
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
        }
        if (previousGeometry == newGeometry) {
            return 0;//No change
        } else if (previousGeometry == EMPTY_GEOMETRY_ID) {
            return 1;//Became non-empty
        } else {
            return 2;//Became empty
        }
    }

    //Process NodeChildRequest results
    private void consumeFinishedNodeChildRequest(int nodeId, NodeChildRequest request) {
        int children = this.nodeData.getChildPtr(nodeId);
        if (children != NO_NODE) {
            //There are children already part of this node, so need to reallocate all the children
            int count = Integer.bitCount(Byte.toUnsignedInt(this.nodeData.getNodeChildExistence(nodeId)));

        } else {

        }
    }

    //============================================================================================================================================

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
