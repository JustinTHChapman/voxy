package me.cortex.voxy.client.core.model;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.GlGpuBuffer;
import net.minecraft.client.render.BuiltBuffer;

import static org.lwjgl.opengl.GL15C.*;

public class BudgetBufferRenderer {
    public static void draw(BuiltBuffer buffer) {
        var params = buffer.getDrawParameters();
        try (var gpuBuf = params.format().uploadImmediateIndexBuffer(buffer.getBuffer())) {
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ((GlGpuBuffer)RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS).getIndexBuffer(params.indexCount())).id);
        }

    }
}
