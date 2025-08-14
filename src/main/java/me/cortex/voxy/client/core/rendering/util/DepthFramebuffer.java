package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedFramebufferfv;
import static org.lwjgl.opengl.GL11C.GL_DEPTH;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.*;

public class DepthFramebuffer {
    private final int depthType;
    private GlTexture depthBuffer;
    public final GlFramebuffer framebuffer = new GlFramebuffer();

    public DepthFramebuffer() {
        this(GL_DEPTH_COMPONENT24);
    }

    public DepthFramebuffer(int depthType) {
        this.depthType = depthType;
    }

    public void resize(int width, int height) {
        if (this.depthBuffer == null || this.depthBuffer.getWidth() != width || this.depthBuffer.getHeight() != height) {
            if (this.depthBuffer != null) {
                this.depthBuffer.free();
            }
            this.depthBuffer = new GlTexture().store(this.depthType, 1, width, height);
            this.framebuffer.bind(this.depthType == GL_DEPTH24_STENCIL8?GL_DEPTH_STENCIL_ATTACHMENT: GL_DEPTH_ATTACHMENT, this.depthBuffer).verify();
        }
    }

    public void clear() {
        this.clear(1.0f);
    }

    public void clear(float depth) {
        try (var stack = MemoryStack.stackPush()) {
            nglClearNamedFramebufferfv(this.framebuffer.id, GL_DEPTH, 0, stack.nfloat(depth));
        }
    }

    public GlTexture getDepthTex() {
        return this.depthBuffer;
    }

    public void free() {
        this.framebuffer.free();
        if (this.depthBuffer != null) {
            this.depthBuffer.free();
        }
    }

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
    }
}
