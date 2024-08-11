package me.cortex.voxy.client.mixin.sodium;

import me.jellysquid.mods.sodium.client.gl.shader.ShaderParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.List;

@Mixin(value = ShaderParser.class, remap = false)
public class MixinShaderParser {
    /*
    @Redirect(method = "parseShader(Ljava/lang/String;)Ljava/util/List;", at = @At(value = "INVOKE", target = "Ljava/util/List;addAll(Ljava/util/Collection;)Z"))
    private static boolean injectLineNumbers(List<String> lines, Collection<? extends String> add) {
        lines.add("#line 1");
        int cc = lines.size();
        lines.addAll(add);
        lines.add("#line " + cc);
        return true;
    }
    */
}
