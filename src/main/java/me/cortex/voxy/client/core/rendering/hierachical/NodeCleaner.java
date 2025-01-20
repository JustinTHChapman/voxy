package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL20.glUniform1i;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;

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

    private final Shader sorter = Shader.make()
            .define("OUTPUT_SIZE", OUTPUT_COUNT)
            .define("VISIBILITY_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/sort_visibility.comp")
            .compile();

    private final AutoBindingShader batchClear = Shader.makeAuto()
            .define("VISIBILITY_BUFFER_BINDING", 0)
            .define("LIST_BUFFER_BINDING", 1)
            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/cleaner/batch_visibility_set.comp")
            .compile();

    final GlBuffer visibilityBuffer;
    private final GlBuffer outputBuffer = new GlBuffer(OUTPUT_COUNT*4);
    private final GlBuffer scratchBuffer = new GlBuffer(BATCH_SET_SIZE*4);//Scratch buffer for setting ids with

    private final IntArrayFIFOQueue idsToClear = new IntArrayFIFOQueue();

    private final NodeManager nodeManager;
    int visibilityId = 0;


    public NodeCleaner(NodeManager nodeManager) {
        this.nodeManager = nodeManager;
        this.visibilityBuffer = new GlBuffer(nodeManager.maxNodeCount*4L).zero();

        this.batchClear
                .ssbo("VISIBILITY_BUFFER_BINDING", this.visibilityBuffer)
                .ssbo("LIST_BUFFER_BINDING", this.scratchBuffer);
    }

    public void clearId(int id) {
        this.idsToClear.enqueue(id);
    }

    public void tick() {
        this.clearIds();

        if (false) {
            this.outputBuffer.zero();//TODO: maybe dont set to zero??

            this.sorter.bind();
            //TODO: choose whether this is in nodeSpace or section/geometryId space
            //glDispatchCompute(this.nodeManager.getCurrentMaxNodeId()/, 1, 1);

            //DownloadStream.INSTANCE.download(this.outputBuffer, this::onDownload);
        }
    }

    private void onDownload(long ptr, long size) {

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
    }
}
