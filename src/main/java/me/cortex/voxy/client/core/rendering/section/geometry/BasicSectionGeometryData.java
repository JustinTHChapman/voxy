package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.Logger;

import static org.lwjgl.opengl.ARBSparseBuffer.GL_SPARSE_STORAGE_BIT_ARB;
import static org.lwjgl.opengl.ARBSparseBuffer.glNamedBufferPageCommitmentARB;
import static org.lwjgl.opengl.GL11C.*;

public class BasicSectionGeometryData implements IGeometryData {
    public static final int SECTION_METADATA_SIZE = 32;
    private final GlBuffer sectionMetadataBuffer;
    private final GlBuffer geometryBuffer;

    private final int maxSectionCount;
    private int currentSectionCount;

    public BasicSectionGeometryData(int maxSectionCount, long geometryCapacity) {
        this.maxSectionCount = maxSectionCount;
        this.sectionMetadataBuffer = new GlBuffer((long) maxSectionCount * SECTION_METADATA_SIZE);
        //8 Cause a quad is 8 bytes
        if ((geometryCapacity%8)!=0) {
            throw new IllegalStateException();
        }
        long start = System.currentTimeMillis();
        String msg = "Creating and zeroing " + (geometryCapacity/(1024*1024)) + "MB geometry buffer";
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            msg += " driver states " + (Capabilities.INSTANCE.getFreeDedicatedGpuMemory()/(1024*1024)) + "MB of free memory";
        }
        Logger.info(msg);
        Logger.info("if your game crashes/exits here without any other log message, try manually decreasing the geometry capacity");
        glGetError();//Clear any errors
        var buffer = new GlBuffer(geometryCapacity);
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            if (error == GL_OUT_OF_MEMORY && Capabilities.INSTANCE.sparseBuffer) {
                Logger.error("Failed to allocate geometry buffer, attempting workaround with sparse buffers");
                buffer.free();
                buffer = new GlBuffer(geometryCapacity, GL_SPARSE_STORAGE_BIT_ARB);
                glNamedBufferPageCommitmentARB(buffer.id, 0, geometryCapacity, true);
                buffer.zero();
                error = glGetError();
                if (error != GL_NO_ERROR) {
                    buffer.free();
                    throw new IllegalStateException("Unable to allocate geometry buffer using workaround, got gl error " + error);
                }
            } else {
                throw new IllegalStateException("Unable to allocate geometry buffer, got gl error " + error);
            }
        }
        this.geometryBuffer = buffer;
        long delta = System.currentTimeMillis() - start;
        Logger.info("Successfully allocated and zeroed the geometry buffer in " + delta + "ms");
    }

    public GlBuffer getGeometryBuffer() {
        return this.geometryBuffer;
    }

    public GlBuffer getMetadataBuffer() {
        return this.sectionMetadataBuffer;
    }

    public int getSectionCount() {
        return this.currentSectionCount;
    }

    public void setSectionCount(int count) {
        this.currentSectionCount = count;
    }

    public int getMaxSectionCount() {
        return this.maxSectionCount;
    }

    public long getGeometryCapacityBytes() {//In bytes
        return this.geometryBuffer.size();
    }

    @Override
    public void free() {
        this.sectionMetadataBuffer.free();

        long gpuMemory = 0;
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            glFinish();
            gpuMemory = Capabilities.INSTANCE.getFreeDedicatedGpuMemory();
        }
        glFinish();
        this.geometryBuffer.free();
        glFinish();
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            long releaseSize = (long) (this.geometryBuffer.size()*0.75);//if gpu memory usage drops by 75% of the expected value assume we freed it
            if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory()-gpuMemory<=releaseSize) {
                Logger.info("Attempting to wait for gpu memory to release");
                long start = System.currentTimeMillis();

                long TIMEOUT = 2500;

                while (System.currentTimeMillis() - start > TIMEOUT) {//Wait up to 2.5 seconds for memory to release
                    glFinish();
                    if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory > releaseSize) break;
                }
                if (Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - gpuMemory <= releaseSize) {
                    Logger.warn("Failed to wait for gpu memory to be freed, this could indicate an issue with the driver");
                }
            }
        }
    }
}
