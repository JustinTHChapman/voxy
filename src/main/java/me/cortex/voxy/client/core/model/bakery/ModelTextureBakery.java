package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.cortex.voxy.client.core.model.BakedBlockEntityModel;
import me.cortex.voxy.client.core.model.BudgetBufferRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.LocalRandom;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.ARBShaderImageLoadStore.GL_FRAMEBUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.ARBShaderImageLoadStore.glMemoryBarrier;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL45.glBlitNamedFramebuffer;

//Builds a texture for each face of a model
public class ModelTextureBakery {
    private static final List<MatrixStack> FACE_VIEWS = new ArrayList<>();
    private final int width;
    private final int height;
    private final GlViewCapture capture;

    public ModelTextureBakery(int width, int height) {
        this.width = width;
        this.height = height;
        this.capture = new GlViewCapture(width, height);

        //This is done to help make debugging easier
        FACE_VIEWS.clear();
        AddViews();
    }

    private static void AddViews() {
        //TODO: FIXME: need to bake in the correct orientation, HOWEVER some orientations require a flipped winding order!!!!

        addView(-90,0, 0, false);//Direction.DOWN
        addView(90,0, 0, false);//Direction.UP
        addView(0,180, 0, true);//Direction.NORTH
        addView(0,0, 0, false);//Direction.SOUTH
        //TODO: check these arnt the wrong way round
        addView(0,90, 270, false);//Direction.EAST
        addView(0,270, 270, false);//Direction.WEST
    }

    private static void addView(float pitch, float yaw, float rotation, boolean flipX) {
        var stack = new MatrixStack();
        stack.translate(0.5f,0.5f,0.5f);
        stack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(rotation));
        stack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        stack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        stack.translate(-0.5f,-0.5f,-0.5f);
        FACE_VIEWS.add(stack);
    }



    //TODO: For block entities, also somehow attempt to render the default block entity, e.g. chests and stuff
    // cause that will result in ok looking micro details in the terrain
    public void renderFacesToStream(BlockState state, long randomValue, boolean renderFluid, int streamBuffer, int streamBaseOffset) {
        var model = MinecraftClient.getInstance()
                .getBakedModelManager()
                .getBlockModels()
                .getModel(state);

        BakedBlockEntityModel entityModel = state.hasBlockEntity()?BakedBlockEntityModel.bake(state):null;

        var projection = new Matrix4f().identity().set(new float[]{
                2,0,0,0,
                0, 2,0,0,
                0,0, -1f,0,
                -1,-1,0,1,
        });



        RenderLayer renderLayer = null;
        if (!renderFluid) {
            renderLayer = RenderLayers.getBlockLayer(state);
        } else {
            renderLayer = RenderLayers.getFluidLayer(state.getFluidState());
        }


        //TODO: figure out why calling this makes minecraft render black
        //renderLayer.startDrawing();


        glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);
        glClearColor(0,0,0,0);
        glClearDepth(1);
        glClearStencil(0);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);

        glEnable(GL_STENCIL_TEST);
        //glDepthRange(0, 1);
        glDepthMask(true);
        glEnable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        //glDepthFunc(GL_LESS);


        //TODO: Find a better solution
        if (renderLayer == RenderLayer.getTranslucent()) {
            //Very hacky blend function to retain the effect of the applied alpha since we dont really want to apply alpha
            // this is because we apply the alpha again when rendering the terrain meaning the alpha is being double applied
            glBlendFuncSeparate(GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            glDisable(GL_BLEND);
        }
        boolean hasDiscard = renderLayer == RenderLayer.getCutout() ||
                             renderLayer == RenderLayer.getCutoutMipped();


        //glBlendFunc(GL_ONE, GL_ONE);

        glStencilOp(GL_KEEP, GL_KEEP, GL_INCR);
        glStencilFunc(GL_ALWAYS, 1, 0xFF);
        glStencilMask(0xFF);

        int[] viewdat = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewdat);

        var tex = MinecraftClient.getInstance().getTextureManager().getTexture(Identifier.of("minecraft", "textures/atlas/blocks.png")).getGlTexture();
        for (int i = 0; i < FACE_VIEWS.size(); i++) {
            glViewport((i%3)*this.width, (i/3)*this.height, this.width, this.height);
            glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);
            var transform = new Matrix4f(projection).mul(FACE_VIEWS.get(i).peek().getPositionMatrix());
            if (entityModel!=null&&!renderFluid) {
                entityModel.renderOut(transform, tex);
            }
            this.rasterView(state, model, transform, randomValue, i, renderFluid, tex, hasDiscard);
        }

        glViewport(viewdat[0], viewdat[1], viewdat[2], viewdat[3]);



        //renderLayer.endDrawing();

        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);

        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT);
        this.capture.emitToStream(streamBuffer, streamBaseOffset);

        //var target = DefaultTerrainRenderPasses.CUTOUT.getTarget();
        //int boundFB = ((net.minecraft.client.texture.GlTexture) target.getColorAttachment()).getOrCreateFramebuffer(((GlBackend) RenderSystem.getDevice()).getFramebufferManager(), target.getDepthAttachment());
        //glBlitNamedFramebuffer(this.capture.framebuffer.id, boundFB, 0,0,16*3, 16*2, 0,0, 16*3*4,16*2*4, GL_COLOR_BUFFER_BIT, GL_NEAREST);


        //SOMEBODY PLEASE FUCKING EXPLAIN TO ME WHY MUST CLEAR THE FRAMEBUFFER HERE WHEN IT IS LITERALLY CLEARED AT THE START OF THE FRAME
        // WITHOUT THIS, WATER DOESNT RENDER
        //TODO: FIXME, WHAT THE ACTUAL FUCK
        glBindFramebuffer(GL_FRAMEBUFFER, this.capture.framebuffer.id);
        glClearDepth(1);
        glClear(GL_DEPTH_BUFFER_BIT);


        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private final BufferAllocator allocator = new BufferAllocator(786432);
    private void rasterView(BlockState state, BlockStateModel model, Matrix4f transform, long randomValue, int face, boolean renderFluid, GpuTexture texture, boolean hasDiscard) {
        var bb = new BufferBuilder(this.allocator, VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR) {
            @Override
            public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
                int colour = color;
                colour |= hasDiscard?2:0;
                if (renderFluid) {
                    colour = ColorHelper.getArgb(0,0,1);
                }
                super.vertex(x, y, z, colour, u, v, overlay, light, normalX, normalY, normalZ);
            }

            @Override
            public VertexConsumer color(int argb) {
                return super.color(ColorHelper.getArgb(0,0,1));
            }

            @Override
            public VertexConsumer color(int red, int green, int blue, int alpha) {
                return super.color(0, 0, 1, 255);
            }
        };
        if (!renderFluid) {
            //TODO: need to do 2 variants for quads, one which have coloured, ones that dont, might be able to pull a spare bit
            // at the end whether or not a pixel should be mixed with texture
            renderQuads(bb, model, new MatrixStack(), randomValue);
        } else {
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
                    if (pos.equals(Direction.byIndex(face).getVector())) {
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
                    if (pos.equals(Direction.byIndex(face).getVector())) {
                        return Blocks.AIR.getDefaultState().getFluidState();
                    }
                    //if (pos.getY() == 1) {
                    //    return Blocks.AIR.getDefaultState().getFluidState();
                    //}
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
            }, bb, state, state.getFluidState());
        }

        var mesh = bb.endNullable();
        if (mesh != null)
            BudgetBufferRenderer.draw(mesh, texture, transform);
    }

    private static void renderQuads(BufferBuilder builder, BlockStateModel model, MatrixStack stack, long randomValue) {
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            for (var part : model.getParts(new LocalRandom(randomValue))) {
                var quads = part.getQuads(direction);
                for (var quad : quads) {
                    //TODO: mark pixels that have
                    int meta = 1;
                    builder.quad(stack.peek(), quad, ((meta >> 16) & 0xff) / 255f, ((meta >> 8) & 0xff) / 255f, (meta & 0xff) / 255f, 1.0f, 0, 0);
                }
            }
        }
    }

    public void free() {
        this.capture.free();
        this.allocator.close();
    }
}
