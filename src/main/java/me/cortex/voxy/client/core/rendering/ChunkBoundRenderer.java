package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
public class ChunkBoundRenderer {
    public static final int MAX_CHUNK_COUNT = 10_000;
    private final GlBuffer chunkPosBuffer = new GlBuffer(MAX_CHUNK_COUNT*8);//Stored as ivec2
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(MAX_CHUNK_COUNT);
    private final long[] idx2chunk = new long[MAX_CHUNK_COUNT];

    public ChunkBoundRenderer() {
        this.chunk2idx.defaultReturnValue(-1);
    }

    public void addChunk(long pos) {
        if (this.chunk2idx.containsKey(pos)) {
            throw new IllegalArgumentException("Chunk already in map");
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
            throw new IllegalArgumentException("Chunk pos not in map");
        }
        if (idx == this.chunk2idx.size()-1) {
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
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        viewport.MVP.getToAddress(ptr); ptr += 4*4*4;
        viewport.section.getToAddress(ptr); ptr += 4*4;
        viewport.innerTranslation.getToAddress(ptr); ptr += 4*4;
        UploadStream.INSTANCE.commit();

        //TODO: NOTE: need to reverse the winding order since we want the back faces of the AABB, not the front
        //this.cullShader.bind();
        glBindVertexArray(RenderService.STATIC_VAO);
        glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniformBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, this.chunkPosBuffer.id);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE.id());
        glDrawElementsInstanced(GL_TRIANGLES, 6*2*3, GL_UNSIGNED_BYTE, SharedIndexBuffer.CUBE_INDEX_OFFSET, this.chunk2idx.size());
    }

    public void free() {
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }
}
