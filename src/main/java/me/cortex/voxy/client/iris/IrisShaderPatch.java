package me.cortex.voxy.client.iris;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;

import java.lang.reflect.Modifier;
import java.util.function.Function;
import java.util.function.IntSupplier;

public class IrisShaderPatch {
    public static final int VERSION = ((IntSupplier)()->1).getAsInt();


    private static class PatchGson {
        public int version;//TODO maybe replace with semver?
        public int[] opaqueDrawBuffers;
        public int[] translucentDrawBuffers;
        public String[] uniforms;
        public String[] samplers;
        public String[] opaquePatchData;
        public String[] translucentPatchData;

        public boolean checkValid() {
            return this.opaqueDrawBuffers != null && this.translucentDrawBuffers != null && this.uniforms != null && this.opaquePatchData != null;
        }
    }

    private final PatchGson patchData;
    private final ShaderPack pack;
    private IrisShaderPatch(PatchGson patchData, ShaderPack pack) {
        this.patchData = patchData;
        this.pack = pack;
    }

    public String getPatchOpaqueSource() {
        return String.join("\n", this.patchData.opaquePatchData);
    }
    public String getPatchTranslucentSource() {
        return this.patchData.translucentPatchData!=null?String.join("\n", this.patchData.translucentPatchData):null;
    }
    public String[] getUniformList() {
        return this.patchData.uniforms;
    }
    public String[] getSamplerList() {
        return this.patchData.samplers;
    }


    public int[] getOpqaueTargets() {
        return this.patchData.opaqueDrawBuffers;
    }

    public int[] getTranslucentTargets() {
        return this.patchData.translucentDrawBuffers;
    }

    private static final Gson GSON = new GsonBuilder()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .setStrictness(Strictness.LENIENT)
            .create();

    public static IrisShaderPatch makePatch(ShaderPack ipack, AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider) {
        String voxyPatchData = sourceProvider.apply(directory.resolve("voxy.json"));
        if (voxyPatchData == null) {//No voxy patch data in shaderpack
            return null;
        }

        //A more graceful exit on blank string
        if (voxyPatchData.isBlank()) {
            return null;
        }

        //Escape things
        voxyPatchData = voxyPatchData.replace("\\", "\\\\");

        PatchGson patchData = null;
        try {
            //TODO: basicly find any "commented out" quotation marks and escape them (if the line, when stripped starts with a // or /* then escape all quotation marks in that line)
            patchData = GSON.fromJson(voxyPatchData, PatchGson.class);
            if (patchData != null && !patchData.checkValid()) {
                throw new IllegalStateException("voxy json patch not valid: " + voxyPatchData);
            }
        } catch (Exception e) {
            patchData = null;
            Logger.error("Failed to parse patch data gson",e);
        }
        if (patchData == null) {
            return null;
        }
        if (patchData.version != VERSION) {
            Logger.error("Shader has voxy patch data, but patch version is incorrect. expected " + VERSION + " got "+patchData.version);
            return null;
        }
        return new IrisShaderPatch(patchData, ipack);
    }
}
