package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfv;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public class ChunkBoundRenderer {
    public static final int MAX_CHUNK_COUNT = 10_000;
    private final GlBuffer chunkPosBuffer = new GlBuffer(MAX_CHUNK_COUNT*8);//Stored as ivec2
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(MAX_CHUNK_COUNT);
    private final long[] idx2chunk = new long[MAX_CHUNK_COUNT];
    private final Shader rasterShader = Shader.makeAuto()
            .add(ShaderType.VERTEX, "voxy:chunkoutline/outline.vsh")
            .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
            .compile()
            .ssbo(0, this.uniformBuffer)
            .ssbo(1, this.chunkPosBuffer);

    private GlTexture depthBuffer = new GlTexture().store(GL_DEPTH_COMPONENT24, 1, 128, 128);
    private final GlFramebuffer frameBuffer = new GlFramebuffer().bind(GL_DEPTH_ATTACHMENT, this.depthBuffer).verify();

    public ChunkBoundRenderer() {
        this.chunk2idx.defaultReturnValue(-1);
    }

    public void addChunk(long pos) {
        if (this.chunk2idx.size() >= MAX_CHUNK_COUNT) {
            throw new IllegalStateException("At capacity");
        }
        if (this.chunk2idx.containsKey(pos)) {
            Logger.warn("Chunk already in map: " + new ChunkPos(pos));
            return;
        }
        int idx = this.chunk2idx.size();
        this.chunk2idx.put(pos, idx);
        this.idx2chunk[idx] = pos;

        long ptr = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L*idx, 8);
        //Need to do it in 2 parts because ivec2 is 2 parts
        MemoryUtil.memPutInt(ptr, (int)(pos&0xFFFFFFFFL)); ptr += 4;
        MemoryUtil.memPutInt(ptr, (int)((pos>>>32)&0xFFFFFFFFL));
        UploadStream.INSTANCE.commit();
    }

    public void removeChunk(long pos) {
        int idx = this.chunk2idx.remove(pos);
        if (idx == -1) {
            Logger.warn("Chunk not in map: " + new ChunkPos(pos));
            return;
        }
        if (idx == this.chunk2idx.size()) {
            //Dont need to do anything as heap is already compact
            return;
        }
        if (this.idx2chunk[idx] != pos) {
            throw new IllegalStateException();
        }

        //Move last entry on heap to this index
        long ePos = this.idx2chunk[this.chunk2idx.size()];// since is already removed size is correct end idx
        if (this.chunk2idx.put(ePos, idx) == -1) {
            throw new IllegalStateException();
        }
        this.idx2chunk[idx] = ePos;

        //Put the end pos into the new idx
        long ptr = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L*idx, 8);
        //Need to do it in 2 parts because ivec2 is 2 parts
        MemoryUtil.memPutInt(ptr, (int)(ePos&0xFFFFFFFFL)); ptr += 4;
        MemoryUtil.memPutInt(ptr, (int)((ePos>>>32)&0xFFFFFFFFL));
        UploadStream.INSTANCE.commit();
    }

    //Bind and render, changing as little gl state as possible so that the caller may configure how it wants to render
    public void render(Viewport<?> viewport) {
        if (this.chunk2idx.isEmpty()) return;

        if (this.depthBuffer.getWidth() != viewport.width || this.depthBuffer.getHeight() != viewport.height) {
            this.depthBuffer.free();
            this.depthBuffer = new GlTexture().store(GL_DEPTH_COMPONENT24, 1, viewport.width, viewport.height);
            this.frameBuffer.bind(GL_DEPTH_ATTACHMENT, this.depthBuffer).verify();
        }

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4*4*4;
        {//This is recomputed to be in chunk section space not worldsection
            int sx = MathHelper.floor(viewport.cameraX) >> 4;
            int sy = MathHelper.floor(viewport.cameraY) >> 4;
            int sz = MathHelper.floor(viewport.cameraZ) >> 4;
            new Vector3i(sx, sy, sz).getToAddress(ptr); ptr += 4*4;

            viewport.MVP.translate(
                    -(float) (viewport.cameraX - (sx << 4)),
                    -(float) (viewport.cameraY - (sy << 4)),
                    -(float) (viewport.cameraZ - (sz << 4)),
                    new Matrix4f()).getToAddress(matPtr);
        }
        UploadStream.INSTANCE.commit();


        //TODO: NOTE: need to reverse the winding order since we want the back faces of the AABB, not the front
        {
            glFrontFace(GL_CW);//Reverse winding order

            //"reverse depth buffer" it goes from 0->1 where 1 is far away
            glClearNamedFramebufferfv(this.frameBuffer.id, GL_DEPTH, 0, new float[]{0});
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_GREATER);
        }

        glBindVertexArray(RenderService.STATIC_VAO);
        glBindFramebuffer(GL_FRAMEBUFFER, this.frameBuffer.id);
        this.rasterShader.bind();
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniformBuffer.id);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        //TODO: BATCH with multiple cubes per instance, this helps fill the pipe and should greatly improve performance of this

        glDrawElementsInstanced(GL_TRIANGLES, 6*2*3, GL_UNSIGNED_BYTE, SharedIndexBuffer.CUBE_INDEX_OFFSET, this.chunk2idx.size());

        {
            glFrontFace(GL_CCW);//Restore winding order

            glDepthFunc(GL_LEQUAL);

            //TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }
    }

    public void reset() {
        this.chunk2idx.clear();
    }

    public void free() {
        this.depthBuffer.free();
        this.frameBuffer.free();

        this.rasterShader.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }

    public GlTexture getDepthBoundTexture() {
        return this.depthBuffer;
    }
}
