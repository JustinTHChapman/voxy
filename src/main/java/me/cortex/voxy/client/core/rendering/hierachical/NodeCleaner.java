package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL30C.glBindBufferRange;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

//Uses compute shaders to compute the last 256 rendered section (64x64 workgroup size maybe)
// done via warp level sort, then workgroup sort (shared memory), (/w sorting network)
// then use bubble sort (/w fast path going to middle or 2 subdivisions deep) the bubble it up
// can do incremental sorting pass aswell, so only scan and sort a rolling sector of sections
// (over a few frames to not cause lag, maybe)


//TODO : USE THIS IN HierarchicalOcclusionTraverser instead of other shit
public class NodeCleaner {
    //TODO: use batch_visibility_set to clear visibility data when nodes are removed!! (TODO: nodeManager will need to forward info to this)

    private static final int OUTPUT_COUNT = 64;

    private static final int BATCH_SET_SIZE = 2048;

    private final AutoBindingShader sorter = Shader.makeAuto()
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("VISIBILITY_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/sort_visibility.comp")
            .compile();

    private final AutoBindingShader resultTransformer = Shader.makeAuto()
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("MIN_ID_BUFFER_BINDING", 0)
            .define("NODE_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .define("QQQQQQ", 3)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/result_transformer.comp")
            .compile();

    private final AutoBindingShader batchClear = Shader.makeAuto()
            .define("VISIBILITY_BUFFER_BINDING", 0)
            .define("LIST_BUFFER_BINDING", 1)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/batch_visibility_set.comp")
            .compile();


    final GlBuffer visibilityBuffer;
    private final GlBuffer outputBuffer = new GlBuffer(OUTPUT_COUNT*4+OUTPUT_COUNT*8);//Scratch + output
    private final GlBuffer scratchBuffer = new GlBuffer(BATCH_SET_SIZE*4);//Scratch buffer for setting ids with

    private final IntArrayFIFOQueue idsToClear = new IntArrayFIFOQueue();

    private final NodeManager nodeManager;
    int visibilityId = 0;


    public NodeCleaner(NodeManager nodeManager) {
        this.nodeManager = nodeManager;
        this.visibilityBuffer = new GlBuffer(nodeManager.maxNodeCount*4L).zero();
        this.visibilityBuffer.fill(-1);

        this.batchClear
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer)
                .ssbo("LIST_BUFFER_BINDING", this.scratchBuffer);

        this.sorter
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer)
                .ssbo("OUTPUT_BUFFER_BINDING", this.outputBuffer);
    }

    public void clearId(int id) {
        this.idsToClear.enqueue(id);
    }

    public void tick(GlBuffer nodeDataBuffer) {
        this.visibilityId++;
        this.clearIds();

        if (this.shouldCleanGeometry() & false) {
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
            this.outputBuffer.fill(this.nodeManager.maxNodeCount-2);//TODO: maybe dont set to zero??

            this.sorter.bind();
            //TODO: choose whether this is in nodeSpace or section/geometryId space
            //this.nodeManager.getCurrentMaxNodeId()
            glDispatchCompute((200_000+127)/128, 1, 1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);

            this.resultTransformer.bind();
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, this.outputBuffer.id, 0, 4*OUTPUT_COUNT);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, nodeDataBuffer.id);
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 2, this.outputBuffer.id, 4*OUTPUT_COUNT, 8*OUTPUT_COUNT);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, this.visibilityBuffer.id);

            //this.outputBuffer.fill(0);//TODO: maybe dont set to zero??
            glDispatchCompute(1,1,1);

            DownloadStream.INSTANCE.download(this.outputBuffer, 4*OUTPUT_COUNT, 8*OUTPUT_COUNT, this::onDownload);


            this.visibilityBuffer.fill(-1);

        }
    }

    private boolean shouldCleanGeometry() {
        // if there is less than 200mb of space, clean
        return this.nodeManager.getGeometryManager().getRemainingCapacity() < 200_000_000L;
    }

    private void onDownload(long ptr, long size) {
        //StringBuilder b = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            long pos = Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr + 8 * i))<<32;
            pos |= Integer.toUnsignedLong(MemoryUtil.memGetInt(ptr + 8 * i + 4));
            this.nodeManager.removeNodeGeometry(pos);
            //b.append(", ").append(WorldEngine.pprintPos(pos));//.append(((int)((pos>>32)&0xFFFFFFFFL)));//
        }
        //System.out.println(b);
    }

    private void clearIds() {
        if (!this.idsToClear.isEmpty()) {
            this.batchClear.bind();

            while (!this.idsToClear.isEmpty()) {
                int cnt = Math.min(this.idsToClear.size(), BATCH_SET_SIZE);
                long ptr = UploadStream.INSTANCE.upload(this.scratchBuffer, 0, cnt * 4L);
                for (int i = 0; i < cnt; i++) {
                    MemoryUtil.memPutInt(ptr + cnt * 4, this.idsToClear.dequeueInt());
                }
                UploadStream.INSTANCE.commit();
                glUniform1i(0, cnt);
                glDispatchCompute((cnt+127)/128, 1, 1);
            }
        }
    }

    public void free() {
        this.sorter.free();
        this.visibilityBuffer.free();
        this.outputBuffer.free();
        this.scratchBuffer.free();
        this.batchClear.free();
        this.resultTransformer.free();
    }
}
