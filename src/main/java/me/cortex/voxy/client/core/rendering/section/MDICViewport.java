package me.cortex.voxy.client.core.rendering.section;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;

public class MDICViewport extends Viewport<MDICViewport> {
    public final GlBuffer indirectLookupBuffer = new GlBuffer(100_000*4+4);
    public final GlBuffer visibilityBuffer;

    public MDICViewport(int maxSectionCount) {
        this.visibilityBuffer = new GlBuffer(maxSectionCount*4L);
    }

    @Override
    protected void delete0() {
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
    }
}
