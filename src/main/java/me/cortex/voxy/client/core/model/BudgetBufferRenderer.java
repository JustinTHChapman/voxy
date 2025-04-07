package me.cortex.voxy.client.core.model;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

public class BudgetBufferRenderer {
    public static final RenderPipeline RENDERER_THING = RenderPipeline.builder()
            .withLocation(Identifier.of("voxy","bakery/position_tex"))
            .withVertexShader(Identifier.of("voxy","bakery/position_tex"))
            .withFragmentShader(Identifier.of("voxy","bakery/position_tex"))
            .withUniform("transform", UniformType.MATRIX4X4)
            .withSampler("tex")
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .build();

    public static void draw(BuiltBuffer buffer, GpuTexture tex, Matrix4f matrix) {
        //Fuz the gpu sampler state
        GlStateManager._activeTexture(GlConst.GL_TEXTURE0);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(GlConst.GL_TEXTURE2);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(GlConst.GL_TEXTURE2+1);
        GlStateManager._bindTexture(0);

        RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(buffer.getDrawParameters().mode());
        GpuBuffer gpuBuffer = buffer.getDrawParameters().format().uploadImmediateVertexBuffer(buffer.getBuffer());

        var res = (GlResourceManager)RenderSystem.getDevice()
                .createCommandEncoder();
        res.currentProgram = null;
        res.currentPipeline = null;
        try (RenderPass renderPass = new RenderPassImpl(res, false)) {
            renderPass.setPipeline(RENDERER_THING);
            renderPass.setVertexBuffer(0, gpuBuffer);

            renderPass.bindSampler("tex", tex);
            renderPass.setUniform("transform", matrix);

            renderPass.setIndexBuffer(shapeIndexBuffer.getIndexBuffer(buffer.getDrawParameters().indexCount()), shapeIndexBuffer.getIndexType());
            renderPass.drawIndexed(0, buffer.getDrawParameters().indexCount());
        }
        //gpuBuffer.close();
        buffer.close();
        res.currentProgram = null;
        res.currentPipeline = null;
    }
}
