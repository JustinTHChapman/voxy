package me.cortex.voxy.client.core.rendering.section.geometry;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.Logger;

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
        Logger.info("Creating and zeroing " + (geometryCapacity/(1024*1024)) + "MB geometry buffer");
        Logger.info("if your game crashes/exits here without any other log message, try manually decreasing the geometry capacity");
        this.geometryBuffer = new GlBuffer(geometryCapacity);
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
        this.geometryBuffer.free();
    }
}
