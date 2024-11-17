package me.cortex.voxy.client.core.gl.shader;


import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;

public class ShaderLoader {
    public static String parse(String id) {
        return ShaderParser.parseShader("#import <" + id + ">", ShaderConstants.builder().build());
        //return me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader.getShaderSource(new Identifier(id));
    }
}
