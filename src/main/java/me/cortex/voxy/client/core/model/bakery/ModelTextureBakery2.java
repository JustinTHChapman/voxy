package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.core.model.BudgetBufferRenderer;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.LocalRandom;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glMemoryBarrier;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_DEPTH;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfv;

public class ModelTextureBakery2 {
    //Note: the first bit of metadata is if alpha discard is enabled
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    private final GlViewCapture capture;
    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();
    private static final int FORMAT_STRIDE = 24;
    private static final class ReuseVertexConsumer implements VertexConsumer {
        private MemoryBuffer buffer = new MemoryBuffer(8192);
        private long ptr;
        private int count;

        public ReuseVertexConsumer() {
            this.reset();
        }

        @Override
        public ReuseVertexConsumer vertex(float x, float y, float z) {
            this.ensureCanPut();
            this.ptr += FORMAT_STRIDE; this.count++; //Goto next vertex
            MemoryUtil.memPutFloat(this.ptr, x);
            MemoryUtil.memPutFloat(this.ptr + 4, y);
            MemoryUtil.memPutFloat(this.ptr + 8, z);
            return this;
        }

        public ReuseVertexConsumer meta(int metadata) {
            MemoryUtil.memPutInt(this.ptr + 12, metadata);
            return this;
        }

        @Override
        public ReuseVertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public ReuseVertexConsumer texture(float u, float v) {
            MemoryUtil.memPutFloat(this.ptr + 16, u);
            MemoryUtil.memPutFloat(this.ptr + 20, v);
            return this;
        }

        @Override
        public ReuseVertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public ReuseVertexConsumer light(int u, int v) {
            return this;
        }

        @Override
        public ReuseVertexConsumer normal(float x, float y, float z) {
            return this;
        }

        public ReuseVertexConsumer quad(BakedQuad quad, int metadata) {
            int[] data = quad.vertexData();
            for (int i = 0; i < 4; i++) {
                float x = Float.intBitsToFloat(data[i * 8]);
                float y = Float.intBitsToFloat(data[i * 8 + 1]);
                float z = Float.intBitsToFloat(data[i * 8 + 2]);
                this.vertex(x,y,z);
                float u = Float.intBitsToFloat(data[i * 8 + 4]);
                float v = Float.intBitsToFloat(data[i * 8 + 5]);
                this.texture(u,v);

                this.meta(metadata);
            }
            return this;
        }

        private void ensureCanPut() {
            if ((long) (this.count + 1) * FORMAT_STRIDE < this.buffer.size) {
                return;
            }
            long offset = this.buffer.address-this.ptr;
            //1.5x the size
            var newBuffer = new MemoryBuffer((((int)(this.buffer.size*1.5)+FORMAT_STRIDE-1)/FORMAT_STRIDE)*FORMAT_STRIDE);
            this.buffer.cpyTo(newBuffer.address);
            this.buffer.free();
            this.buffer = newBuffer;
            this.ptr = offset + newBuffer.address;
        }

        public ReuseVertexConsumer reset() {
            this.count = 0;
            this.ptr = this.buffer.address - FORMAT_STRIDE;//the thing is first time this gets incremented by FORMAT_STRIDE
            return this;
        }

        public void free() {
            this.buffer.free();
            this.buffer = null;
        }
    }

    private final int width;
    private final int height;
    public ModelTextureBakery2(int width, int height) {
        this.capture = new GlViewCapture(width, height);
        this.width = width;
        this.height = height;
    }

    private void bakeBlockModel(BlockState state, RenderLayer layer) {
        var model = MinecraftClient.getInstance()
                .getBakedModelManager()
                .getBlockModels()
                .getModel(state);

        boolean hasDiscard = layer == RenderLayer.getCutout() ||
                layer == RenderLayer.getCutoutMipped() ||
                layer == RenderLayer.getTripwire();

        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            for (var part : model.getParts(new LocalRandom(42L))) {
                var quads = part.getQuads(direction);
                for (var quad : quads) {
                    //TODO: add meta specifiying quad has a tint

                    int meta = hasDiscard?1:0;
                    this.vc.quad(quad, meta);
                }
            }
        }
    }


    private void bakeFluidState(BlockState state, int face) {
        MinecraftClient.getInstance().getBlockRenderManager().renderFluid(BlockPos.ORIGIN, new BlockRenderView() {
            @Override
            public float getBrightness(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LightingProvider getLightingProvider() {
                return null;
            }

            @Override
            public int getLightLevel(LightType type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getColor(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.getDefaultState();
                }

                //Fixme:
                // This makes it so that the top face of water is always air, if this is commented out
                //  the up block will be a liquid state which makes the sides full
                // if this is uncommented, that issue is fixed but e.g. stacking water layers ontop of eachother
                //  doesnt fill the side of the block

                //if (pos.getY() == 1) {
                //    return Blocks.AIR.getDefaultState();
                //}
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.getDefaultState().getFluidState();
                }

                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getBottomY() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.byIndex(face).getVector();
        int dot = fv.getX()*pos.getX() + fv.getY()*pos.getY() + fv.getZ()*pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.capture.free();
        this.vc.free();
    }


    public void renderToStream(BlockState state, int streamBuffer, int streamOffset) {
        this.capture.clear();
        boolean isBlock = true;
        RenderLayer layer;
        if (state.getBlock() instanceof FluidBlock) {
            layer = RenderLayers.getFluidLayer(state.getFluidState());
            isBlock = false;
        } else {
            layer = RenderLayers.getBlockLayer(state);
        }

        //TODO: support block model entities
        //state.hasBlockEntity()

        //Setup GL state
        int[] viewdat = new int[4];
        int blockTextureId;

        {
            glEnable(GL_STENCIL_TEST);
            glEnable(GL_DEPTH_TEST);
            glEnable(GL_CULL_FACE);
            if (layer == RenderLayer.getTranslucent()) {
                glEnable(GL_BLEND);
                glBlendFuncSeparate(GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            } else {
                glDisable(GL_BLEND);
            }

            glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
            glStencilFunc(GL_ALWAYS, 1, 0xFF);
            glStencilMask(0xFF);

            glGetIntegerv(GL_VIEWPORT, viewdat);//TODO: faster way todo this, or just use main framebuffer resolution

            //Bind the capture framebuffer
            glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);

            var tex = MinecraftClient.getInstance().getTextureManager().getTexture(Identifier.of("minecraft", "textures/atlas/blocks.png")).getGlTexture();
            blockTextureId = ((net.minecraft.client.texture.GlTexture)tex).getGlId();
        }

        //TODO: fastpath for blocks
        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            if (this.vc.count != 0) {//only render if there... is shit to render
                if (this.vc.count % 4 != 0) throw new IllegalStateException();

                //Setup for continual emission
                BudgetBufferRenderer.setup(this.vc.buffer.address, this.vc.count / 4, blockTextureId);//note: this.vc.buffer.address NOT this.vc.ptr

                var mat = new Matrix4f();
                for (int i = 0; i < VIEWS.length; i++) {
                    glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                    //The projection matrix
                    mat.set(2, 0, 0, 0,
                            0, 2, 0, 0,
                            0, 0, -1f, 0,
                            -1, -1, 0, 1);

                    BudgetBufferRenderer.render(mat.mul(VIEWS[i]));
                }
            }
            glBindVertexArray(0);
        } else {//Is fluid, slow path :(
            if (!(state.getBlock() instanceof FluidBlock)) throw new IllegalStateException();

            var mat = new Matrix4f();
            for (int i = 0; i < VIEWS.length; i++) {
                this.vc.reset();
                this.bakeFluidState(state, i);
                if (this.vc.count == 0) continue;
                BudgetBufferRenderer.setup(this.vc.buffer.address, this.vc.count / 4, blockTextureId);

                glViewport((i % 3) * this.width, (i / 3) * this.height, this.width, this.height);

                //The projection matrix
                mat.set(2, 0, 0, 0,
                        0, 2, 0, 0,
                        0, 0, -1f, 0,
                        -1, -1, 0, 1);

                BudgetBufferRenderer.render(mat.mul(VIEWS[i]));
            }
            glBindVertexArray(0);
        }

        //"Restore" gl state
        glViewport(viewdat[0], viewdat[1], viewdat[2], viewdat[3]);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);

        //Finish and download
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        this.capture.emitToStream(streamBuffer, streamOffset);

        glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);
        glClearDepth(1);
        glClear(GL_DEPTH_BUFFER_BIT);
    }




    static  {
        //TODO: FIXME: need to bake in the correct orientation, HOWEVER some orientations require a flipped winding order!!!!

        addView(0, -90,0, 0, false);//Direction.DOWN
        addView(1, 90,0, 0, false);//Direction.UP
        addView(2, 0,180, 0, true);//Direction.NORTH
        addView(3, 0,0, 0, false);//Direction.SOUTH
        //TODO: check these arnt the wrong way round
        addView(4, 0,90, 270, false);//Direction.EAST
        addView(5, 0,270, 270, false);//Direction.WEST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, boolean flipX) {
        var stack = new MatrixStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotation));
        stack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        stack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        stack.translate(-0.5f,-0.5f,-0.5f);
        VIEWS[i] = new Matrix4f(stack.peek().getPositionMatrix());
    }
}
