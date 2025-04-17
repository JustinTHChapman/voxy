package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.client.core.util.Mesher2D;
import me.cortex.voxy.client.core.util.ScanMesher2D;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.Map;


public class RenderDataFactory45 {
    private static final boolean VERIFY_MESHING = VoxyCommon.isVerificationFlagOn("verifyMeshing");

    //TODO: MAKE a render cache that caches each WorldSection directional face generation, cause then can just pull that directly
    // instead of needing to regen the entire thing

    //Ok so the idea for fluid rendering is to make it use a seperate mesher and use a different code path for it
    // since fluid states are explicitly overlays over the base block
    // can do funny stuff like double rendering


    private final WorldEngine world;
    private final ModelFactory modelMan;

    //private final long[] sectionData = new long[32*32*32*2];
    private final long[] sectionData = new long[32*32*32*2];
    private final long[] neighboringFaces = new long[32*32*6];
    //private final int[] neighboringOpaqueMasks = new int[32*6];

    private final int[] opaqueMasks = new int[32*32];
    private final int[] nonOpaqueMasks = new int[32*32];
    private final int[] fluidMasks = new int[32*32];//Used to separately mesh fluids, allowing for fluid + blockstate


    //TODO: emit directly to memory buffer instead of long arrays

    //Each axis gets a max quad count of 2^16 (65536 quads) since that is the max the basic geometry manager can handle
    private final MemoryBuffer quadBuffer = new MemoryBuffer(8*(8*(1<<16)));//6 faces + dual direction + translucents
    private final long quadBufferPtr = this.quadBuffer.address;
    private final int[] quadCounters = new int[8];


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
        public boolean doAuxiliaryFaceOffset = true;
        public int axis = 0;//Y,Z,X

        //Note x, z are in top right
        @Override
        protected void emitQuad(int x, int z, int length, int width, long data) {
            if (VERIFY_MESHING) {
                if (length<1||length>16) {
                    throw new IllegalStateException("length out of bounds: " + length);
                }
                if (width<1||width>16) {
                    throw new IllegalStateException("width out of bounds: " + width);
                }
                if (x<0||x>31) {
                    throw new IllegalStateException("x out of bounds: " + x);
                }
                if (z<0||z>31) {
                    throw new IllegalStateException("z out of bounds: " + z);
                }
                if (x-(length-1)<0 || z-(width-1)<0) {
                    throw new IllegalStateException("dim out of bounds: " + (x-(length-1))+", " + (z-(width-1)));
                }
            }

            RenderDataFactory45.this.quadCount++;

            x -= length-1;
            z -= width-1;

            if (this.axis == 2) {
                //Need to swizzle the data if on x axis
                int tmp = x;
                x = z;
                z = tmp;

                tmp = length;
                length = width;
                width = tmp;
            }

            //Lower 26 bits can be auxiliary data since that is where quad position information goes;
            int auxData = (int) (data&((1<<26)-1));
            data &= ~((1<<26)-1);

            int axisSide = auxData&1;
            int type = (auxData>>1)&3;//Translucent, double side, directional

            if (VERIFY_MESHING) {
                if (type == 3) {
                    throw new IllegalStateException();
                }
            }

            //Shift up if is negative axis
            int auxPos = this.auxiliaryPosition;
            auxPos += 1-(this.doAuxiliaryFaceOffset?axisSide:1);//Shift

            if (VERIFY_MESHING) {
                if (auxPos > 31) {
                    throw new IllegalStateException("OOB face: " + auxPos + ", " + axisSide);
                }
            }

            final int axis = this.axis;
            int face = (axis<<1)|axisSide;

            int encodedPosition = face;
            encodedPosition |= ((width - 1) << 7) | ((length - 1) << 3);
            encodedPosition |= x << (axis==2?16:21);
            encodedPosition |= z << (axis==1?16:11);
            int shiftAmount = axis==0?16:(axis==1?11:21);
            //shiftAmount += ;
            encodedPosition |= auxPos << (shiftAmount);

            long quad = data | Integer.toUnsignedLong(encodedPosition);


            int bufferIdx = type+(type==2?face:0);//Translucent, double side, directional
            long bufferOffset = (RenderDataFactory45.this.quadCounters[bufferIdx]++)*8L + bufferIdx*8L*(1<<16);
            MemoryUtil.memPutLong(RenderDataFactory45.this.quadBufferPtr + bufferOffset, quad);


            //Update AABB bounds
            if (axis == 0) {//Y
                RenderDataFactory45.this.minY = Math.min(RenderDataFactory45.this.minY, auxPos);
                RenderDataFactory45.this.maxY = Math.max(RenderDataFactory45.this.maxY, auxPos);

                RenderDataFactory45.this.minX = Math.min(RenderDataFactory45.this.minX, x);
                RenderDataFactory45.this.maxX = Math.max(RenderDataFactory45.this.maxX, x + length);

                RenderDataFactory45.this.minZ = Math.min(RenderDataFactory45.this.minZ, z);
                RenderDataFactory45.this.maxZ = Math.max(RenderDataFactory45.this.maxZ, z + width);
            } else if (axis == 1) {//Z
                RenderDataFactory45.this.minZ = Math.min(RenderDataFactory45.this.minZ, auxPos);
                RenderDataFactory45.this.maxZ = Math.max(RenderDataFactory45.this.maxZ, auxPos);

                RenderDataFactory45.this.minX = Math.min(RenderDataFactory45.this.minX, x);
                RenderDataFactory45.this.maxX = Math.max(RenderDataFactory45.this.maxX, x + length);

                RenderDataFactory45.this.minY = Math.min(RenderDataFactory45.this.minY, z);
                RenderDataFactory45.this.maxY = Math.max(RenderDataFactory45.this.maxY, z + width);
            } else {//X
                RenderDataFactory45.this.minX = Math.min(RenderDataFactory45.this.minX, auxPos);
                RenderDataFactory45.this.maxX = Math.max(RenderDataFactory45.this.maxX, auxPos);

                RenderDataFactory45.this.minY = Math.min(RenderDataFactory45.this.minY, x);
                RenderDataFactory45.this.maxY = Math.max(RenderDataFactory45.this.maxY, x + length);

                RenderDataFactory45.this.minZ = Math.min(RenderDataFactory45.this.minZ, z);
                RenderDataFactory45.this.maxZ = Math.max(RenderDataFactory45.this.maxZ, z + width);
            }
        }
    }


    private final Mesher blockMesher = new Mesher();
    private final Mesher seondaryblockMesher = new Mesher();//Used for dual non-opaque geometry

    public RenderDataFactory45(WorldEngine world, ModelFactory modelManager, boolean emitMeshlets) {
        this.world = world;
        this.modelMan = modelManager;
    }

    private static long packPartialQuadData(int modelId, long state, long metaData) {
        //This uses hardcoded data to shuffle things
        long lightAndBiome =  (state&((0x1FFL<<47)|(0xFFL<<56)))>>1;
        lightAndBiome &= ModelQueries.isBiomeColoured(metaData)?-1:~(0x1FFL<<47);
        lightAndBiome &= ModelQueries.isFullyOpaque(metaData)?~(0xFFL<<55):-1;//If its fully opaque it always uses neighbor light?
        int type = 0;
        {
            boolean a = ModelQueries.isTranslucent(metaData);
            boolean b = ModelQueries.isDoubleSided(metaData);
            type = a|b?0:2;
            type |= b?1:0;
        }
        long quadData = lightAndBiome;
        quadData |= Integer.toUnsignedLong(modelId)<<26;
        quadData |= type << 1;
        return quadData;
    }

    private int prepareSectionData() {
        final var sectionData = this.sectionData;
        int opaque = 0;
        int notEmpty = 0;
        int pureFluid = 0;
        int partialFluid = 0;

        int neighborAcquireMsk = 0;//-+x, -+y, -+Z
        for (int i = 0; i < 32*32*32;) {
            long block = sectionData[i + 32 * 32 * 32];//Get the block mapping
            if (Mapper.isAir(block)) {//If it is air, just emit lighting
                sectionData[i * 2] = (block&(0xFFL<<56))>>1;
                sectionData[i * 2 + 1] = 0;
            } else {
                int modelId = this.modelMan.getModelId(Mapper.getBlockId(block));
                long modelMetadata = this.modelMan.getModelMetadataFromClientId(modelId);

                sectionData[i * 2] = packPartialQuadData(modelId, block, modelMetadata);
                sectionData[i * 2 + 1] = modelMetadata;

                int msk = 1 << (i & 31);
                opaque |= ModelQueries.isFullyOpaque(modelMetadata) ? msk : 0;
                notEmpty |= modelId != 0 ? msk : 0;
                pureFluid |= ModelQueries.isFluid(modelMetadata) ? msk : 0;
                partialFluid |= ModelQueries.containsFluid(modelMetadata) ? msk : 0;
            }

            //TODO: here also do bitmask of what neighboring sections are needed to compute (may be getting rid of this in future)

            //Do increment here
            i++;

            if ((i & 31) == 0 && notEmpty != 0) {
                this.opaqueMasks[(i >> 5) - 1] = opaque;
                this.nonOpaqueMasks[(i >> 5) - 1] = (notEmpty^opaque)&~pureFluid;
                this.fluidMasks[(i >> 5) - 1] = pureFluid|partialFluid;

                int neighborMsk = 0;
                //-+x
                neighborMsk |= notEmpty&1;//-x
                neighborMsk |= (notEmpty>>>30)&0b10;//+x

                //notEmpty = (notEmpty != 0)?1:0;
                neighborMsk |= (((i - 1) >> 10) == 0) ? 0b100 : 0;//-y
                neighborMsk |= (((i - 1) >> 10) == 31) ? 0b1000 : 0;//+y
                neighborMsk |= ((((i - 1) >> 5) & 0x1F) == 0) ? 0b10000 : 0;//-z
                neighborMsk |= ((((i - 1) >> 5) & 0x1F) == 31) ? 0b100000 : 0;//+z

                neighborAcquireMsk |= neighborMsk;


                opaque = 0;
                notEmpty = 0;
                pureFluid = 0;
                partialFluid = 0;
            }
        }
        return neighborAcquireMsk;
    }

    private void acquireNeighborData(WorldSection section, int msk) {
        //TODO: fixme!!! its probably more efficent to just access the raw section array on demand instead of copying it
        if ((msk&1)!=0) {//-x
            var sec = this.world.acquire(section.lvl, section.x - 1, section.y, section.z);
            //Note this is not thread safe! (but eh, fk it)
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i] = raw[(i<<5)+31];//pull the +x faces from the section
            }
            sec.release();
        }
        if ((msk&2)!=0) {//+x
            var sec = this.world.acquire(section.lvl, section.x + 1, section.y, section.z);
            //Note this is not thread safe! (but eh, fk it)
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32] = raw[(i<<5)];//pull the -x faces from the section
            }
            sec.release();
        }

        if ((msk&4)!=0) {//-y
            var sec = this.world.acquire(section.lvl, section.x, section.y - 1, section.z);
            //Note this is not thread safe! (but eh, fk it)
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32*2] = raw[i|(0x1F<<10)];//pull the +y faces from the section
            }
            sec.release();
        }
        if ((msk&8)!=0) {//+y
            var sec = this.world.acquire(section.lvl, section.x, section.y + 1, section.z);
            //Note this is not thread safe! (but eh, fk it)
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32*3] = raw[i];//pull the -y faces from the section
            }
            sec.release();
        }

        if ((msk&16)!=0) {//-z
            var sec = this.world.acquire(section.lvl, section.x, section.y, section.z - 1);
            //Note this is not thread safe! (but eh, fk it)
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32*4] = raw[Integer.expand(i,0b11111_00000_11111)|(0x1F<<5)];//pull the +z faces from the section
            }
            sec.release();
        }
        if ((msk&32)!=0) {//+z
            var sec = this.world.acquire(section.lvl, section.x, section.y, section.z + 1);
            //Note this is not thread safe! (but eh, fk it)
            var raw = sec._unsafeGetRawDataArray();
            for (int i = 0; i < 32*32; i++) {
                this.neighboringFaces[i+32*32*5] = raw[Integer.expand(i,0b11111_00000_11111)];//pull the -z faces from the section
            }
            sec.release();
        }
    }

    private void generateYZOpaqueInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                int current = this.opaqueMasks[pidx];
                int next = this.opaqueMasks[pidx + skipAmount];

                int msk = current ^ next;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = msk & current;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);

                        //TODO: swap this out for something not getting the next entry
                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[(idx + skipAmount * 32) * 2];

                        //Flip data with respect to facing direction
                        long selfModel = facingForward == 1 ? A : B;
                        long nextModel = facingForward == 1 ? B : A;


                        this.blockMesher.putNext(((long) facingForward) |//Facing
                                selfModel |
                                (nextModel&(0xFFL<<55))//Apply lighting
                        );
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZOpaqueOuterGeometry(int axis) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        //Hacky generate section side faces (without check neighbor section)
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.opaqueMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        if (Mapper.getBlockId(neighborId) != 0) {//Not air
                            long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                            if (ModelQueries.isFullyOpaque(meta)) {//Dont mesh this face
                                this.blockMesher.skip(1);
                                continue;
                            }

                            //This very funnily causes issues when not combined with meshing non full opaque geometry
                            //TODO:FIXME, when non opaque geometry is added
                            if (false) {
                                if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }
                        }


                        long A = this.sectionData[idx * 2];

                        this.blockMesher.putNext((side == 0 ? 0L : 1L) |
                                A |
                                ((neighborId&(0xFFL<<56))>>1)
                        );
                    }
                }
                this.blockMesher.endRow();
            }

            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZFluidInnerGeometry(int axis) {
        for (int layer = 0; layer < 31; layer++) {
            this.blockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis==0 ?(layer*32+other):(other*32+layer);
                int skipAmount = axis==0?32:1;

                //TODO: this needs to take into account opaqueMasks to not mesh any faces with it set
                int current = this.fluidMasks[pidx];
                int next = this.fluidMasks[pidx + skipAmount];

                int msk = (current | this.opaqueMasks[pidx]) ^ (next | this.opaqueMasks[pidx + skipAmount]);
                msk &= current|next;
                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                cSkip = 0;

                int faceForwardMsk = msk & current;
                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    int facingForward = ((faceForwardMsk >> index) & 1);

                    {
                        int idx = index + (pidx*32);

                        int a = idx*2;
                        int b = (idx + skipAmount * 32) * 2;

                        //Flip data with respect to facing direction
                        int ai = facingForward == 1 ? a : b;
                        int bi = facingForward == 1 ? b : a;

                        //TODO: check if must cull against next entries face

                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];
                        //If it isnt a fluid but contains one,
                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                        }

                        long lighter = A;
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        this.blockMesher.putNext(((long) facingForward) |//Facing
                                A |
                                (lighter&(0xFFL<<55))//Apply lighting
                        );
                    }
                }

                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
    }

    private void generateYZFluidOuterGeometry(int axis) {
        this.blockMesher.doAuxiliaryFaceOffset = false;
        //Hacky generate section side faces (without check neighbor section)
        for (int side = 0; side < 2; side++) {//-, +
            int layer = side == 0 ? 0 : 31;
            this.blockMesher.auxiliaryPosition = layer;
            int cSkips = 0;
            for (int other = 0; other < 32; other++) {
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int msk = this.fluidMasks[pidx];
                if (msk == 0) {
                    cSkips += 32;
                    continue;
                }

                this.blockMesher.skip(cSkips);
                cSkips = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) this.blockMesher.skip(delta);
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);


                        int neighborIdx = ((axis+1)*32*32 * 2)+(side)*32*32;
                        long neighborId = this.neighboringFaces[neighborIdx + (other*32) + index];

                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[idx * 2 + 1];

                        if (ModelQueries.containsFluid(B)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            B = this.modelMan.getModelMetadataFromClientId(fluidId);
                        }

                        //Check and test if can cull W.R.T neighbor
                        if (Mapper.getBlockId(neighborId) != 0) {//Not air
                            int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                            long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                            if (ModelQueries.containsFluid(meta)) {
                                modelId = this.modelMan.getFluidClientStateId(modelId);
                            }
                            if (ModelQueries.cullsSame(B)) {
                                if (modelId == ((A>>26)&0xFFFF)) {
                                    this.blockMesher.skip(1);
                                    continue;
                                }
                            }

                            if (ModelQueries.faceOccludes(meta, (axis<<1)|(1-side))) {
                                this.blockMesher.skip(1);
                                continue;
                            }
                        }

                        this.blockMesher.putNext((side == 0 ? 0L : 1L) |
                                A |
                                ((neighborId&(0xFFL<<56))>>1)
                        );
                    }
                }
                this.blockMesher.endRow();
            }
            this.blockMesher.finish();
        }
        this.blockMesher.doAuxiliaryFaceOffset = true;
    }

    private void generateYZNonOpaqueInnerGeometry(int axis) {
        //Note: think is ok to just reuse.. blockMesher
        this.seondaryblockMesher.doAuxiliaryFaceOffset = false;
        this.blockMesher.axis = axis;
        this.seondaryblockMesher.axis = axis;
        for (int layer = 1; layer < 31; layer++) {//(should be 1->31, then have outer face mesher)
            this.blockMesher.auxiliaryPosition = layer;
            this.seondaryblockMesher.auxiliaryPosition = layer;
            int cSkip = 0;
            for (int other = 0; other < 32; other++) {//TODO: need to do the faces that border sections
                int pidx = axis == 0 ? (layer * 32 + other) : (other * 32 + layer);
                int skipAmount = axis==0?32*32:32;

                int msk = this.nonOpaqueMasks[pidx];

                if (msk == 0) {
                    cSkip += 32;
                    continue;
                }

                this.blockMesher.skip(cSkip);
                this.seondaryblockMesher.skip(cSkip);
                cSkip = 0;

                int cIdx = -1;
                while (msk != 0) {
                    int index = Integer.numberOfTrailingZeros(msk);//Is also the x-axis index
                    int delta = index - cIdx - 1;
                    cIdx = index; //index--;
                    if (delta != 0) {
                        this.blockMesher.skip(delta);
                        this.seondaryblockMesher.skip(delta);
                    }
                    msk &= ~Integer.lowestOneBit(msk);

                    {
                        int idx = index + (pidx * 32);

                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[idx * 2+1];
                        //TODO: filtering

                        //Example thing thats just wrong but as example
                        this.blockMesher.putNext((long) (false ? 0L : 1L) |
                                A |
                                (((0xFFL) & 0xFF) << 55)
                        );
                        this.seondaryblockMesher.putNext((long) (true ? 0L : 1L) |
                                A |
                                (((0xFFL) & 0xFF) << 55)
                        );
                    }
                }
                this.blockMesher.endRow();
                this.seondaryblockMesher.endRow();
            }
            this.blockMesher.finish();
            this.seondaryblockMesher.finish();
        }
    }

    private void generateYZFaces() {
        for (int axis = 0; axis < 2; axis++) {//Y then Z
            this.blockMesher.axis = axis;

            this.generateYZOpaqueInnerGeometry(axis);
            this.generateYZOpaqueOuterGeometry(axis);

            this.generateYZFluidInnerGeometry(axis);
            this.generateYZFluidOuterGeometry(axis);

            //this.generateYZNonOpaqueInnerGeometry(axis);
        }
    }


    private final Mesher[] xAxisMeshers = new Mesher[32];
    {
        for (int i = 0; i < 32; i++) {
            var mesher = new Mesher();
            mesher.auxiliaryPosition = i;
            mesher.axis = 2;//X axis
            this.xAxisMeshers[i] = mesher;
        }
    }

    private static final long X_I_MSK = 0x4210842108421L;

    private void generateXOpaqueInnerGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int lMsk = this.opaqueMasks[y*32+z];
                msk = (lMsk^(lMsk>>>1));
                //TODO: fixme? doesnt this generate extra geometry??
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        //TODO: fixme! check this is correct or if should be 30
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                /*
                {//Dont need this as can just increment everything then -1 in mask
                    //Compute and increment skips for indexes
                    long imsk = Integer.toUnsignedLong(~msk);// we only want to increment where there isnt a face
                    sumA += Long.expand(imsk, X_I_MSK);
                    sumB += Long.expand(imsk>>11, X_I_MSK);
                    sumC += Long.expand(imsk>>22, X_I_MSK);
                }*/

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);
                        //TODO: swap this out for something not getting the next entry
                        long A = this.sectionData[idx * 2];
                        long B = this.sectionData[(idx + 1) * 2];

                        //Flip data with respect to facing direction
                        long selfModel = facingForward==1?A:B;
                        long nextModel = facingForward==1?B:A;

                        //Example thing thats just wrong but as example
                        mesher.putNext(((long) facingForward) |//Facing
                                selfModel |
                                (nextModel&(0xFFL<<55))
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterOpaqueGeometry() {
        //Generate the side faces, hackily, using 0 and 31 mesher

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.opaqueMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }
                        //TODO: check neibor face
                        //else if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                        //    oki = false;
                        //}
                    }
                    if (oki) {
                        ma.skip(skipA); skipA = 0;
                        long A = this.sectionData[(i<<5) * 2];
                        ma.putNext(0L |
                                A |
                                ((neighborId&(0xFFL<<56))>>1)
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;
                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        long meta = this.modelMan.getModelMetadataFromClientId(this.modelMan.getModelId(Mapper.getBlockId(neighborId)));
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }
                        //TODO: check neibor face
                        //else if (ModelQueries.faceOccludes(meta, (axis << 1) | (1 - side))) {
                        //    oki = false;
                        //}
                    }
                    if (oki) {
                        mb.skip(skipB); skipB = 0;
                        long A = this.sectionData[(i*32+31) * 2];
                        mb.putNext(1L |
                                A |
                                ((neighborId&(0xFFL<<56))>>1)
                        );
                    } else {skipB++;}
                } else {skipB++;}
            }
            ma.skip(skipA);
            mb.skip(skipB);
        }

        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = true;
        mb.doAuxiliaryFaceOffset = true;
    }

    private void generateXInnerFluidGeometry() {
        for (int y = 0; y < 32; y++) {
            long sumA = 0;
            long sumB = 0;
            long sumC = 0;
            int partialHasCount = -1;
            int msk = 0;
            for (int z = 0; z < 32; z++) {
                int oMsk = this.opaqueMasks[y*32+z];
                int fMsk = this.fluidMasks[y*32+z];
                int lMsk = oMsk|fMsk;
                msk = (lMsk^(lMsk>>>1));
                //TODO: fixme? doesnt this generate extra geometry??
                msk &= -1>>>1;//Remove top bit as we dont actually know/have the data for that slice

                //Dont generate geometry for opaque faces
                msk &= fMsk|(fMsk>>1);

                //Always increment cause can do funny trick (i.e. -1 on skip amount)
                sumA += X_I_MSK;
                sumB += X_I_MSK;
                sumC += X_I_MSK;

                partialHasCount &= ~msk;

                if (z == 30 && partialHasCount != 0) {//Hackfix for incremental count overflow issue
                    int cmsk = partialHasCount;
                    while (cmsk!=0) {
                        int index = Integer.numberOfTrailingZeros(cmsk);
                        cmsk &= ~Integer.lowestOneBit(cmsk);
                        //TODO: fixme! check this is correct or if should be 30
                        this.xAxisMeshers[index].skip(31);
                    }
                    //Clear the sum
                    sumA &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount), X_I_MSK)*0x1F);
                    sumB &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>11, X_I_MSK)*0x1F);
                    sumC &= ~(Long.expand(Integer.toUnsignedLong(partialHasCount)>>22, X_I_MSK)*0x1F);
                }

                if (msk == 0) {
                    continue;
                }

                int faceForwardMsk = msk&lMsk;
                int iter = msk;
                while (iter!=0) {
                    int index = Integer.numberOfTrailingZeros(iter);
                    iter &= ~Integer.lowestOneBit(iter);

                    var mesher = this.xAxisMeshers[index];

                    int skipCount;//Compute the skip count
                    {//TODO: Branch-less
                        //Compute skip and clear
                        if (index<11) {
                            skipCount = (int) (sumA>>(index*5));
                            sumA &= ~(0x1FL<<(index*5));
                        } else if (index<22) {
                            skipCount = (int) (sumB>>((index-11)*5));
                            sumB &= ~(0x1FL<<((index-11)*5));
                        } else {
                            skipCount = (int) (sumC>>((index-22)*5));
                            sumC &= ~(0x1FL<<((index-22)*5));
                        }
                        skipCount &= 0x1F;
                        skipCount--;
                    }

                    if (skipCount != 0) {
                        mesher.skip(skipCount);
                    }

                    int facingForward = ((faceForwardMsk>>index)&1);
                    {
                        int idx = index + (z * 32) + (y * 32 * 32);

                        //The facingForward thing is to get next entry automajicly
                        int ai = (idx+(1-facingForward))*2;
                        int bi = (idx+facingForward)*2;


                        long A = this.sectionData[ai];
                        long Am = this.sectionData[ai+1];

                        //TODO: check if must cull against next entries face
                        if (ModelQueries.containsFluid(Am)) {
                            int modelId = (int) ((A>>26)&0xFFFF);
                            A &= ~(0xFFFFL<<26);
                            int fluidId = this.modelMan.getFluidClientStateId(modelId);
                            A |= Integer.toUnsignedLong(fluidId)<<26;
                            Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                        }

                        long lighter = A;
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        //Example thing thats just wrong but as example
                        mesher.putNext(((long) facingForward) |//Facing
                                A |
                                (lighter&(0xFFL<<55))//Lighting
                        );
                    }
                }
            }

            //Need to skip the remaining entries in the skip array
            {
                msk = ~msk;//Invert the mask as we only need to set stuff that isnt 0
                while (msk!=0) {
                    int index = Integer.numberOfTrailingZeros(msk);
                    msk &= ~Integer.lowestOneBit(msk);
                    int skipCount;
                    if (index < 11) {
                        skipCount = (int) (sumA>>(index*5));
                    } else if (index<22) {
                        skipCount = (int) (sumB>>((index-11)*5));
                    } else {
                        skipCount = (int) (sumC>>((index-22)*5));
                    }
                    skipCount &= 0x1F;

                    if (skipCount != 0) {
                        this.xAxisMeshers[index].skip(skipCount);
                    }
                }
            }
        }
    }

    private void generateXOuterFluidGeometry() {
        //Generate the side faces, hackily, using 0 and 31 mesher

        var ma = this.xAxisMeshers[0];
        var mb = this.xAxisMeshers[31];
        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = false;
        mb.doAuxiliaryFaceOffset = false;

        for (int y = 0; y < 32; y++) {
            int skipA = 0;
            int skipB = 0;
            for (int z = 0; z < 32; z++) {
                int i = y*32+z;
                int msk = this.fluidMasks[i];
                if ((msk & 1) != 0) {//-x
                    long neighborId = this.neighboringFaces[i];
                    boolean oki = true;

                    int sidx = (i<<5) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                    }


                    if (Mapper.getBlockId(neighborId) != 0) {//Not air

                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        /*
                        //TODO: check neibor face
                        if (ModelQueries.faceOccludes(meta, (axis<<1)|(1-side))) {
                            oki = false;
                        }
                        */

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                oki = false;
                            }
                        }
                    }

                    if (oki) {
                        ma.skip(skipA); skipA = 0;

                        long lightData = ((neighborId&(0xFFL<<56))>>1);//A;
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        ma.putNext(0L |
                                A |
                                lightData
                        );
                    } else {skipA++;}
                } else {skipA++;}

                if ((msk & (1<<31)) != 0) {//+x
                    long neighborId = this.neighboringFaces[i+32*32];
                    boolean oki = true;


                    int sidx = (i*32+31) * 2;
                    long A = this.sectionData[sidx];
                    long Am = this.sectionData[sidx + 1];

                    //TODO: check if must cull against next entries face
                    if (ModelQueries.containsFluid(Am)) {
                        int modelId = (int) ((A>>26)&0xFFFF);
                        A &= ~(0xFFFFL<<26);
                        int fluidId = this.modelMan.getFluidClientStateId(modelId);
                        A |= Integer.toUnsignedLong(fluidId)<<26;
                        Am = this.modelMan.getModelMetadataFromClientId(fluidId);
                    }



                    if (Mapper.getBlockId(neighborId) != 0) {//Not air
                        int modelId = this.modelMan.getModelId(Mapper.getBlockId(neighborId));
                        long meta = this.modelMan.getModelMetadataFromClientId(modelId);
                        if (ModelQueries.isFullyOpaque(meta)) {
                            oki = false;
                        }

                        /*
                        //TODO: check neibor face
                        if (ModelQueries.faceOccludes(meta, (axis<<1)|(1-side))) {
                            oki = false;
                        }
                        */

                        if (ModelQueries.containsFluid(meta)) {
                            modelId = this.modelMan.getFluidClientStateId(modelId);
                        }

                        if (ModelQueries.cullsSame(Am)) {
                            if (modelId == ((A>>26)&0xFFFF)) {
                                oki = false;
                            }
                        }
                    }

                    if (oki) {
                        mb.skip(skipB); skipB = 0;

                        long lightData = ((neighborId&(0xFFL<<56))>>1);//A;
                        //if (!ModelQueries.faceUsesSelfLighting(Am, facingForward|(axis*2))) {//TODO: check this is right
                        //    lighter = this.sectionData[bi];
                        //}

                        mb.putNext(1L |
                                A |
                                lightData
                        );
                    } else {skipB++;}
                } else {skipB++;}
            }
            ma.skip(skipA);
            mb.skip(skipB);
        }

        ma.finish();
        mb.finish();
        ma.doAuxiliaryFaceOffset = true;
        mb.doAuxiliaryFaceOffset = true;
    }

    private void generateXNonOpaqueInnerGeometry() {

    }

    private void generateXFaces() {
        this.generateXOpaqueInnerGeometry();
        this.generateXOuterOpaqueGeometry();

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }

        this.generateXInnerFluidGeometry();
        this.generateXOuterFluidGeometry();

        for (var mesher : this.xAxisMeshers) {
            mesher.finish();
        }
    }

    //section is already acquired and gets released by the parent
    public BuiltSection generateMesh(WorldSection section) {
        //TODO: FIXME: because of the exceptions that are thrown when aquiring modelId
        // this can result in the state of all block meshes and well _everything_ from being incorrect
        //THE EXCEPTION THAT THIS THROWS CAUSES MAJOR ISSUES

        //Copy section data to end of array so that can mutate array while reading safely
        section.copyDataTo(this.sectionData, 32*32*32);

        //We must reset _everything_ that could have changed as we dont exactly know the state due to how the model id exception
        // throwing system works
        this.quadCount = 0;

        {//Reset all the block meshes
            this.blockMesher.reset();
            this.blockMesher.doAuxiliaryFaceOffset = true;
            this.seondaryblockMesher.reset();
            this.seondaryblockMesher.doAuxiliaryFaceOffset = true;
            for (var mesher : this.xAxisMeshers) {
                mesher.reset();
                mesher.doAuxiliaryFaceOffset = true;
            }
        }

        this.minX = Integer.MAX_VALUE;
        this.minY = Integer.MAX_VALUE;
        this.minZ = Integer.MAX_VALUE;
        this.maxX = Integer.MIN_VALUE;
        this.maxY = Integer.MIN_VALUE;
        this.maxZ = Integer.MIN_VALUE;

        Arrays.fill(this.quadCounters,0);
        Arrays.fill(this.opaqueMasks, 0);
        Arrays.fill(this.nonOpaqueMasks, 0);
        Arrays.fill(this.fluidMasks, 0);

        //Prepare everything
        int neighborMsk = this.prepareSectionData();
        this.acquireNeighborData(section, neighborMsk);

        this.generateYZFaces();
        this.generateXFaces();


        //TODO:NOTE! when doing face culling of translucent blocks,
        // if the connecting type of the translucent block is the same AND the face is full, discard it
        // this stops e.g. multiple layers of glass (and ocean) from having 3000 layers of quads etc
        if (this.quadCount == 0) {
            return BuiltSection.emptyWithChildren(section.key, section.getNonEmptyChildren());
        }

        if (this.minX<0 || this.minY<0 || this.minZ<0 || 32<this.maxX || 32<this.maxY || 32<this.maxZ) {
            throw new IllegalStateException();
        }

        int[] offsets = new int[8];
        var buff = new MemoryBuffer(this.quadCount * 8L);
        long ptr = buff.address;
        int coff = 0;
        for (int buffer = 0; buffer < 8; buffer++) {// translucent, double sided quads, 6 faces
            offsets[buffer] = coff;
            int size = this.quadCounters[buffer];
            UnsafeUtil.memcpy(this.quadBufferPtr + (buffer*(8*(1<<16))), ptr + coff*8L, (size* 8L));
            coff += size;
        }

        int aabb = 0;
        aabb |= this.minX;
        aabb |= this.minY<<5;
        aabb |= this.minZ<<10;
        aabb |= (this.maxX-this.minX-1)<<15;
        aabb |= (this.maxY-this.minY-1)<<20;
        aabb |= (this.maxZ-this.minZ-1)<<25;

        return new BuiltSection(section.key, section.getNonEmptyChildren(), aabb, buff, offsets);
    }

    public void free() {
        this.quadBuffer.free();
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