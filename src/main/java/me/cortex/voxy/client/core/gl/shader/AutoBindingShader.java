package me.cortex.voxy.client.core.gl.shader;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlDebug;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL30.glBindBufferRange;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;


//TODO: rewrite the entire shader builder system
public class AutoBindingShader extends Shader {

    private record BufferBinding(int target, int index, GlBuffer buffer, long offset, long size) {}
    private record TextureBinding(int unit, int sampler, GlTexture texture) {}

    private final Map<String, String> defines;
    private final List<BufferBinding> bindings = new ArrayList<>();
    private final List<TextureBinding> textureBindings = new ArrayList<>();

    AutoBindingShader(Shader.Builder<AutoBindingShader> builder, int program) {
        super(program);
        this.defines = builder.defines;
    }

    public AutoBindingShader name(String name) {
        return GlDebug.name(name, this);
    }

    public AutoBindingShader ssboIf(String define, GlBuffer buffer) {
        if (this.defines.containsKey(define)) {
            return this.ssbo(define, buffer);
        }
        return this;
    }

    public AutoBindingShader ssbo(int index, GlBuffer binding) {
        return this.ssbo(index, binding, 0);
    }

    public AutoBindingShader ssbo(String define, GlBuffer binding) {
        return this.ssbo(Integer.parseInt(this.defines.get(define)), binding, 0);
    }

    public AutoBindingShader ssbo(int index, GlBuffer buffer, long offset) {
        this.bindings.add(new BufferBinding(GL_SHADER_STORAGE_BUFFER, index, buffer, offset, -1));
        return this;
    }


    public AutoBindingShader ubo(String define, GlBuffer buffer) {
        return this.ubo(Integer.parseInt(this.defines.get(define)), buffer);
    }

    public AutoBindingShader ubo(int index, GlBuffer buffer) {
        return this.ubo(index, buffer, 0);
    }

    public AutoBindingShader ubo(int index, GlBuffer buffer, long offset) {
        this.bindings.add(new BufferBinding(GL_UNIFORM_BUFFER, index, buffer, offset, -1));
        return this;
    }


    public AutoBindingShader texture(String define, GlTexture texture) {
        return this.texture(define, -1, texture);
    }

    public AutoBindingShader texture(String define, int sampler, GlTexture texture) {
        return this.texture(Integer.parseInt(this.defines.get(define)), sampler, texture);
    }

    public AutoBindingShader texture(int unit, int sampler, GlTexture texture) {
        this.textureBindings.add(new TextureBinding(unit, sampler, texture));
        return this;
    }

    @Override
    public void bind() {
        super.bind();
        if (!this.bindings.isEmpty()) {
            for (var binding : this.bindings) {
                if (binding.offset == 0 && binding.size == -1) {
                    glBindBufferBase(binding.target, binding.index, binding.buffer.id);
                } else {
                    glBindBufferRange(binding.target, binding.index, binding.buffer.id, binding.offset, binding.size);
                }
            }
        }
        if (!this.textureBindings.isEmpty()) {
            for (var binding : this.textureBindings) {
                if (binding.texture != null) {
                    GlStateManager._activeTexture(GlConst.GL_TEXTURE0+binding.unit);
                    GlStateManager._bindTexture(0);
                    glBindTextureUnit(binding.unit, binding.texture.id);
                }
                if (binding.sampler != -1) {
                    glBindSampler(binding.unit, binding.sampler);
                }
            }
        }
    }
}
