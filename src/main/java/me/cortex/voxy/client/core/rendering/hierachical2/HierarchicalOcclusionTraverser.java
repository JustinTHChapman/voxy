package me.cortex.voxy.client.core.rendering.hierachical2;

import me.cortex.voxy.client.Voxy;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.rendering.PrintfDebugUtil.PRINTF_object;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_UNPACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.*;

// TODO: swap to persistent gpu threads instead of dispatching MAX_ITERATIONS of compute layers
public class HierarchicalOcclusionTraverser {
    private final HierarchicalNodeManager nodeManager;

    private final int maxRequestCount;
    private final GlBuffer requestBuffer;

    private final GlBuffer nodeBuffer;
    private final GlBuffer uniformBuffer = new GlBuffer(1024).zero();
    private final GlBuffer renderList = new GlBuffer(100_000 * 4 + 4).zero();//100k sections max to render, TODO: Maybe move to render service or somewhere else

    private final GlBuffer queueMetaBuffer = new GlBuffer(4*4*5).zero();
    private final GlBuffer scratchQueueA = new GlBuffer(10_000*4).zero();
    private final GlBuffer scratchQueueB = new GlBuffer(10_000*4).zero();

    private static final int LOCAL_WORK_SIZE_BITS = 5;
    private static final int MAX_ITERATIONS = 5;

    private static final int NODE_QUEUE_INDEX_BINDING = 1;
    private static final int NODE_QUEUE_META_BINDING = 2;
    private static final int NODE_QUEUE_SOURCE_BINDING = 3;
    private static final int NODE_QUEUE_SINK_BINDING = 4;

    private final HiZBuffer hiZBuffer = new HiZBuffer();

    private final Shader traversal = Shader.make(PRINTF_object)
            .defineIf("DEBUG", Voxy.SHADER_DEBUG)
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", LOCAL_WORK_SIZE_BITS)

            .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
            .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
            .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
            .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)

            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/traversal_dev.comp")
            .compile();


    public HierarchicalOcclusionTraverser(HierarchicalNodeManager nodeManager, int requestBufferCount) {
        this.nodeManager = nodeManager;
        this.requestBuffer = new GlBuffer(requestBufferCount*4L+1024).zero();//The 1024 is to assist with race condition issues
        this.nodeBuffer = new GlBuffer(nodeManager.maxNodeCount*16L).zero();
        this.maxRequestCount = requestBufferCount;
    }

    private void uploadUniform(Viewport<?> viewport) {

    }

    private void bindings() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_META_BINDING, this.queueMetaBuffer.id);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, this.queueMetaBuffer.id);
    }

    public void doTraversal(Viewport<?> viewport, int depthBuffer) {
        //Compute the mip chain
        this.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);

        this.uploadUniform(viewport);
        //UploadStream.INSTANCE.commit(); //Done inside traversal

        this.traversal.bind();
        this.bindings();
        PrintfDebugUtil.bind();

        this.traverseInternal(1);


        this.downloadResetRequestQueue();
    }

    private void traverseInternal(int initialQueueSize) {
        {
            //Fix mesa bug
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        }

        int firstDispatchSize = (initialQueueSize+(1<<LOCAL_WORK_SIZE_BITS)-1)>>LOCAL_WORK_SIZE_BITS;
        /*
        //prime the queue Todo: maybe move after the traversal? cause then it is more efficient work since it doesnt need to wait for this before starting?
        glClearNamedBufferData(this.queueMetaBuffer.id, GL_RGBA32UI, GL_RGBA, GL_UNSIGNED_INT, new int[]{0,1,1,0});//Prime the metadata buffer, which also contains

        //Set the first entry
        glClearNamedBufferSubData(this.queueMetaBuffer.id, GL_RGBA32UI, 0, 16, GL_RGBA, GL_UNSIGNED_INT, new int[]{firstDispatchSize,1,1,initialQueueSize});
         */
        {
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*5);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, initialQueueSize);
            for (int i = 1; i < 5; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }

            UploadStream.INSTANCE.commit();
        }

        glUniform1ui(NODE_QUEUE_INDEX_BINDING, 0);

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, this.scratchQueueA.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, this.scratchQueueB.id);

        //Dont need to use indirect to dispatch the first iteration
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);
        glDispatchCompute(firstDispatchSize, 1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);

        //Dispatch max iterations
        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            glUniform1ui(NODE_QUEUE_INDEX_BINDING, iter);

            //Flipflop buffers
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB).id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, ((iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA).id);

            //Dispatch and barrier
            glDispatchComputeIndirect(iter * 4 * 4);

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
        }
    }


    private void downloadResetRequestQueue() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        DownloadStream.INSTANCE.commit();
        nglClearNamedBufferSubData(this.requestBuffer.id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
    }

    public GlBuffer getRenderListBuffer() {
        return this.renderList;
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);
        if (count < 0 || count > 50000) {
            throw new IllegalStateException("Count unexpected extreme value: " + count);
        }
        if (count > (this.requestBuffer.size()>>2)-1) {
            throw new IllegalStateException("Count over max buffer size, desync expected, aborting");
        }
        if (count > this.maxRequestCount) {
            System.err.println("Count larger than 'maxRequestCount', overflow captured. Overflowed by " + (count-this.maxRequestCount));
        }
        if (count != 0) {
            this.nodeManager.processRequestQueue(count, ptr + 4);
        }
    }

    public void free() {
        this.traversal.free();
        this.requestBuffer.free();
        this.hiZBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.renderList.free();
        this.queueMetaBuffer.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
    }
}
