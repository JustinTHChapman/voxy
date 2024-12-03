package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.Capabilities;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.util.Mesher2D;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;


public class RenderDataFactory4 {
    private final WorldEngine world;
    private final ModelFactory modelMan;

    //private final long[] sectionData = new long[32*32*32*2];
    private final long[] sectionData = new long[32*32*32*2];

    private final int[] opaqueMasks = new int[32*32];


    //TODO: emit directly to memory buffer instead of long arrays
    private final LongArrayList[] directionalQuadCollectors = new LongArrayList[]{new LongArrayList(), new LongArrayList(), new LongArrayList(), new LongArrayList(), new LongArrayList(), new LongArrayList()};


    private int minX;
    private int minY;
    private int minZ;
    private int maxX;
    private int maxY;
    private int maxZ;

    private int quadCount = 0;

    //Wont work for double sided quads
    private final class Mesher extends ScanMesher2D {
        public int auxiliaryPosition = 0;
        public int axis = 0;

        //Note x, z are in top right
        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            RenderDataFactory4.this.quadCount++;

            x -= length-1;
            z -= width-1;

            //Lower 26 bits can be auxiliary data since that is where quad position information goes;
            int auxData = (int) (data&((1<<26)-1));
            data &= ~(data&((1<<26)-1));

            final int axis = this.axis;
            int face = (auxData&1)|(axis<<1);
            int encodedPosition = (auxData&1)|(axis<<1);

            //Shift up if is negative axis
            int auxPos = this.auxiliaryPosition;
            auxPos += (~auxData)&1;

            encodedPosition |= ((width - 1) << 7) | ((length - 1) << 3);

            encodedPosition |= x << (axis==2?16:21);
            encodedPosition |= z << (axis==1?16:11);
            encodedPosition |= auxPos << (axis==0?16:(axis==1?11:21));

            long quad = data | encodedPosition;


            RenderDataFactory4.this.directionalQuadCollectors[face].add(quad);
        }
    }


    private final Mesher blockMesher = new Mesher();

    public RenderDataFactory4(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
        this.world = world;
        this.modelMan = modelManager;
    }


    //TODO: MAKE a render cache that caches each WorldSection directional face generation, cause then can just pull that directly
    // instead of needing to regen the entire thing


    //Ok so the idea for fluid rendering is to make it use a seperate mesher and use a different code path for it
    // since fluid states are explicitly overlays over the base block
    // can do funny stuff like double rendering

    private static final boolean USE_UINT64 = Capabilities.INSTANCE.INT64_t;
    public static final int QUADS_PER_MESHLET = 14;
    private static void writePos(long ptr, long pos) {
        if (USE_UINT64) {
            MemoryUtil.memPutLong(ptr, pos);
        } else {
            MemoryUtil.memPutInt(ptr, (int) (pos>>32));
            MemoryUtil.memPutInt(ptr + 4, (int)pos);
        }
    }

    private void prepareSectionData() {
        final var sectionData = this.sectionData;
        int opaque = 0;
        for (int i = 0; i < 32*32*32;) {
            long block = sectionData[i + 32*32*32];//Get the block mapping

            int modelId = this.modelMan.getModelId(Mapper.getBlockId(block));
            long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);

            sectionData[i*2] = modelId|((long) (Mapper.getLightId(block)) <<16)|(((long) (Mapper.getBiomeId(block)))<<24);
            sectionData[i*2+1] = modelMetadata;

            boolean isFullyOpaque = ModelQueries.isFullyOpaque(modelMetadata);
            opaque |= (isFullyOpaque ? 1 : 0) << (i&31);

            //TODO: here also do bitmask of what neighboring sections are needed to compute (may be getting rid of this in future)

            //Do increment here
            i++;

            if ((i&31)==0) {
                this.opaqueMasks[(i>>5)-1] = opaque;
                opaque = 0;
            }
        }
    }

    private void generateYFaces() {
        this.blockMesher.axis = 0;// Y axis
        for (int y = 0; y < 31; y++) {
            this.blockMesher.auxiliaryPosition = y;
            for (int z = 0; z < 32; z++) {//TODO: need to do the faces that border sections
                int current = this.opaqueMasks[(y+0)*32+z];
                int next    = this.opaqueMasks[(y+1)*32+z];

                int msk     = current ^ next;
                if (msk == 0) {
                    this.blockMesher.skip(32);
                    continue;
                }

                //TODO: For boarder sections, should NOT EMIT neighbors faces
                int faceForwardMsk = msk&current;


                int cIdx = -1;
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1; cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk>>index)&1);

                    {
                        int idx = index + (z * 32) + (y * 32 * 32);
                        //TODO: swap this out for something not getting the next entry
                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[(idx + 32*32) * 2];

                        //Flip data with respect to facing direction
                        long selfModel = facingForward==1?A:B;
                        long nextModel = facingForward==1?B:A;

                        //Example thing thats just wrong but as example
                        this.blockMesher.putNext((long) facingForward | ((selfModel&0xFFFF)<<26) | (0xFFL<<55));
                    }
                }
                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }


    private void generateZFaces() {
        this.blockMesher.axis = 1;// Z axis
        for (int z = 0; z < 31; z++) {
            this.blockMesher.auxiliaryPosition = z;
            for (int y = 0; y < 32; y++) {//TODO: need to do the faces that border sections
                int current = this.opaqueMasks[y*32+z];
                int next    = this.opaqueMasks[y*32+z+1];

                int msk     = current ^ next;
                if (msk == 0) {
                    this.blockMesher.skip(32);
                    continue;
                }

                //TODO: For boarder sections, should NOT EMIT neighbors faces
                int faceForwardMsk = msk&current;


                int cIdx = -1;
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1; cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk>>index)&1);

                    {
                        int idx = index + (z * 32) + (y * 32 * 32);
                        //TODO: swap this out for something not getting the next entry
                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[(idx + 32) * 2];

                        //Flip data with respect to facing direction
                        long selfModel = facingForward==1?A:B;
                        long nextModel = facingForward==1?B:A;

                        //Example thing thats just wrong but as example
                        this.blockMesher.putNext((long) facingForward | ((selfModel&0xFFFF)<<26) | (0xFFL<<55));
                    }
                }
                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateXFaces() {
        //TODO: actually fking accelerate this

        this.blockMesher.axis = 2;// X axis
        for (int x = 0; x < 31; x++) {//TODO: need to do the faces that border sections
            this.blockMesher.auxiliaryPosition = x;
            for (int z = 0; z < 32; z++) {
                for (int y = 0; y < 32; y++) {
                    int idx = x+z*32+y*32*32;
                    long self = this.sectionData[idx*2];
                    long next = this.sectionData[(idx+1)*2];

                    boolean so = ModelQueries.isFullyOpaque(this.sectionData[idx*2+1]);
                    boolean no = ModelQueries.isFullyOpaque(this.sectionData[(idx+1)*2+1]);
                    if (so^no) {//Not culled
                        //Flip data with respect to facing direction
                        long selfModel = so?self:next;
                        long nextModel = so?next:selfModel;

                        //Example thing thats just wrong but as example
                        this.blockMesher.putNext((long) (so?1L:0L) | ((selfModel&0xFFFF)<<26) | (0xFFL<<55));
                    } else {
                        this.blockMesher.putNext(0);
                    }
                }
                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    /*
    private static long createQuad() {
        ((long)clientModelId) | (((long) Mapper.getLightId(ModelQueries.faceUsesSelfLighting(metadata, face)?self:facingState))<<16) | ((((long) Mapper.getBiomeId(self))<<24) * (ModelQueries.isBiomeColoured(metadata)?1:0)) | otherFlags


            long data = Integer.toUnsignedLong(array[i*3+1]);
            data |= ((long) array[i*3+2])<<32;
            long encodedQuad = Integer.toUnsignedLong(QuadEncoder.encodePosition(face, otherAxis, quad)) | ((data&0xFFFF)<<26) | (((data>>16)&0xFF)<<55) | (((data>>24)&0x1FF)<<46);
    }*/

    //section is already acquired and gets released by the parent
    public BuiltSection generateMesh(WorldSection section) {
        //Copy section data to end of array so that can mutate array while reading safely
        section.copyDataTo(this.sectionData, 32*32*32);

        this.quadCount = 0;

        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.minZ = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
        this.maxZ = Integer.MIN_VALUE;

        for (var i : this.directionalQuadCollectors) {
            i.clear();
        }
        /*
        this.world.acquire(section.lvl, section.x+1, section.y, section.z).release();
        this.world.acquire(section.lvl, section.x-1, section.y, section.z).release();
        this.world.acquire(section.lvl, section.x, section.y+1, section.z).release();
        this.world.acquire(section.lvl, section.x, section.y-1, section.z).release();
        this.world.acquire(section.lvl, section.x, section.y, section.z+1).release();
        this.world.acquire(section.lvl, section.x, section.y, section.z-1).release();*/

        //Prepare everything
        this.prepareSectionData();

        this.generateYFaces();

        this.generateZFaces();

        this.generateXFaces();

        //TODO:NOTE! when doing face culling of translucent blocks,
        // if the connecting type of the translucent block is the same AND the face is full, discard it
        // this stops e.g. multiple layers of glass (and ocean) from having 3000 layers of quads etc

        if (this.quadCount == 0) {
            return BuiltSection.empty(section.key);
        }

        //TODO: FIXME AND OPTIMIZE, get rid of the stupid quad collector bullshit

        int[] offsets = new int[8];
        var buff = new MemoryBuffer(this.quadCount * 8L);
        long ptr = buff.address;
        int coff = 0;

        long size = 0;
        for (int face = 0; face < 6; face++) {
            offsets[face + 2] = coff;
            final LongArrayList faceArray = this.directionalQuadCollectors[face];
            size = faceArray.size();
            for (int i = 0; i < size; i++) {
                long data = faceArray.getLong(i);
                MemoryUtil.memPutLong(ptr + ((coff++) * 8L), data);
            }
        }


        int aabb = 0;
        aabb |= 0;
        aabb |= 0<<5;
        aabb |= 0<<10;
        aabb |= (31)<<15;
        aabb |= (31)<<20;
        aabb |= (31)<<25;

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets);

        /*
            buff = new MemoryBuffer(bufferSize * 8L);
            long ptr = buff.address;
            int coff = 0;

            //Ordering is: translucent, double sided quads, directional quads
            offsets[0] = coff;
            int size = this.translucentQuadCollector.size();
            LongArrayList arrayList = this.translucentQuadCollector;
            for (int i = 0; i < size; i++) {
                long data = arrayList.getLong(i);
                MemoryUtil.memPutLong(ptr + ((coff++) * 8L), data);
            }

            offsets[1] = coff;
            size = this.doubleSidedQuadCollector.size();
            arrayList = this.doubleSidedQuadCollector;
            for (int i = 0; i < size; i++) {
                long data = arrayList.getLong(i);
                MemoryUtil.memPutLong(ptr + ((coff++) * 8L), data);
            }

            for (int face = 0; face < 6; face++) {
                offsets[face + 2] = coff;
                final LongArrayList faceArray = this.directionalQuadCollectors[face];
                size = faceArray.size();
                for (int i = 0; i < size; i++) {
                    long data = faceArray.getLong(i);
                    MemoryUtil.memPutLong(ptr + ((coff++) * 8L), data);
                }
            }

        int aabb = 0;
        aabb |= this.minX;
        aabb |= this.minY<<5;
        aabb |= this.minZ<<10;
        aabb |= (this.maxX-this.minX)<<15;
        aabb |= (this.maxY-this.minY)<<20;
        aabb |= (this.maxZ-this.minZ)<<25;

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets);
         */
    }


















    //Returns true if a face was placed
    private boolean putFluidFaceIfCan(Mesher2D mesher, int face, int opposingFace, long self, long metadata, int selfClientModelId, int selfBlockId, long facingState, long facingMetadata, int facingClientModelId, int a, int b) {
        int selfFluidClientId = this.modelMan.getFluidClientStateId(selfClientModelId);
        long selfFluidMetadata = this.modelMan.getModelMetadataFromClientId(selfFluidClientId);

        int facingFluidClientId = -1;
        if (ModelQueries.containsFluid(facingMetadata)) {
            facingFluidClientId = this.modelMan.getFluidClientStateId(facingClientModelId);
        }

        //If both of the states are the same, then dont render the fluid face
        if (selfFluidClientId == facingFluidClientId) {
            return false;
        }

        if (facingFluidClientId != -1) {
            //TODO: OPTIMIZE
            if (this.world.getMapper().getBlockStateFromBlockId(selfBlockId).getBlock() == this.world.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(facingState)).getBlock()) {
               return false;
            }
        }


        if (ModelQueries.faceOccludes(facingMetadata, opposingFace)) {
            return false;
        }

        //if the model has a fluid state but is not a liquid need to see if the solid state had a face rendered and that face is occluding, if so, dont render the fluid state face
        if ((!ModelQueries.isFluid(metadata)) && ModelQueries.faceOccludes(metadata, face)) {
            return false;
        }



        //TODO:FIXME SOMEHOW THIS IS CRITICAL!!!!!!!!!!!!!!!!!!
        // so there is one more issue need to be fixed, if water is layered ontop of eachother, the side faces depend on the water state ontop
        // this has been hackfixed in the model texture bakery but a proper solution that doesnt explode the sides of the water textures needs to be done
        // the issue is that the fluid rendering depends on the up state aswell not just the face state which is really really painful to account for
        // e.g the sides of a full water is 8 high or something, not the full block height, this results in a gap between water layers



        long otherFlags = 0;
        otherFlags |= ModelQueries.isTranslucent(selfFluidMetadata)?1L<<33:0;
        otherFlags |= ModelQueries.isDoubleSided(selfFluidMetadata)?1L<<34:0;
        mesher.put(a, b, ((long)selfFluidClientId) | (((long) Mapper.getLightId(ModelQueries.faceUsesSelfLighting(selfFluidMetadata, face)?self:facingState))<<16) | ((((long) Mapper.getBiomeId(self))<<24) * (ModelQueries.isBiomeColoured(selfFluidMetadata)?1:0)) | otherFlags);
        return true;
    }

    //Returns true if a face was placed
    private boolean putFaceIfCan(Mesher2D mesher, int face, int opposingFace, long metadata, int clientModelId, int selfBiome, long facingModelId, long facingMetadata, int selfLight, int facingLight, int a, int b) {
        if (ModelQueries.cullsSame(metadata) && clientModelId == facingModelId) {
            //If we are facing a block, and we are both the same state, dont render that face
            return false;
        }

        //If face can be occluded and is occluded from the facing block, then dont render the face
        if (ModelQueries.faceCanBeOccluded(metadata, face) && ModelQueries.faceOccludes(facingMetadata, opposingFace)) {
            return false;
        }

        long otherFlags = 0;
        otherFlags |= ModelQueries.isTranslucent(metadata)?1L<<33:0;
        otherFlags |= ModelQueries.isDoubleSided(metadata)?1L<<34:0;
        mesher.put(a, b, ((long)clientModelId) | (((long) Mapper.getLightId(ModelQueries.faceUsesSelfLighting(metadata, face)?selfLight:facingLight))<<16) | (ModelQueries.isBiomeColoured(metadata)?(((long) Mapper.getBiomeId(selfBiome))<<24):0) | otherFlags);
        return true;
    }

    private static int getMeshletHoldingCount(int quads, int quadsPerMeshlet, int meshletSize) {
        return ((quads+(quadsPerMeshlet-1))/quadsPerMeshlet)*meshletSize;
    }

    public static int alignUp(int n, int alignment) {
        return (n + alignment - 1) & -alignment;
    }
}