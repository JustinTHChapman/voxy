package me.cortex.voxy.client.iris;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.function.Function;
import java.util.function.IntSupplier;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL40.glBlendFuncSeparatei;

public class IrisShaderPatch {
    public static final int VERSION = ((IntSupplier)()->1).getAsInt();

    public static final boolean IMPERSONATE_DISTANT_HORIZONS = System.getProperty("voxy.impersonateDHShader", "false").equalsIgnoreCase("true");


    private static final class SSBODeserializer implements JsonDeserializer<Int2ObjectOpenHashMap<String>> {
        @Override
        public Int2ObjectOpenHashMap<String> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            Int2ObjectOpenHashMap<String> ret = new Int2ObjectOpenHashMap<>();
            if (json==null) return null;
            try {
                for (var entry : json.getAsJsonObject().entrySet()) {
                    ret.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
                }
            } catch (Exception e) {
                Logger.error(e);
            }
            return ret;
        }
    }
    private static final class SamplerDeserializer implements JsonDeserializer<Object2ObjectLinkedOpenHashMap<String, String>> {
        @Override
        public Object2ObjectLinkedOpenHashMap<String, String> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            Object2ObjectLinkedOpenHashMap<String, String> ret = new Object2ObjectLinkedOpenHashMap<>();
            if (json==null) return null;
            try {
                if (json.isJsonArray()) {
                    for (var entry : json.getAsJsonArray()) {
                        var name = entry.getAsString();
                        var type = "sampler2D";
                        if (name.matches("shadowtex")) {
                            type = "sampler2DShadow";
                        }
                        ret.put(name, type);
                    }
                } else {
                    for (var entry : json.getAsJsonObject().entrySet()) {
                        String type = "sampler2D";
                        if (entry.getValue().isJsonNull()) {
                            if (entry.getKey().matches("shadowtex")) {
                                type = "sampler2DShadow";
                            }
                        } else {
                            type = entry.getValue().getAsString();
                        }
                        ret.put(entry.getKey(), type);
                    }
                }
            } catch (Exception e) {
                Logger.error(e);
            }
            return ret;
        }
    }

    public record BlendState(int buffer, boolean off, int sRBG, int dRGb, int sA, int dA) {
        public static BlendState ALL_OFF = new BlendState(-1, true, 0,0,0,0);
    }


    private static final class BlendStateDeserializer implements JsonDeserializer<Int2ObjectMap<BlendState>> {
        private static int parseType(String type) {
            type = type.toUpperCase();
            return switch (type) {
                case "GL_ZERO" -> GL_ZERO;
                case "GL_ONE" -> GL_ONE;
                case "GL_SRC_COLOR" -> GL_SRC_COLOR;
                case "GL_ONE_MINUS_SRC_COLOR" -> GL_ONE_MINUS_SRC_COLOR;
                case "GL_SRC_ALPHA" -> GL_SRC_ALPHA;
                case "GL_ONE_MINUS_SRC_ALPHA" -> GL_ONE_MINUS_SRC_ALPHA;
                case "GL_DST_ALPHA" -> GL_DST_ALPHA;
                case "GL_ONE_MINUS_DST_ALPHA" -> GL_ONE_MINUS_DST_ALPHA;
                case "GL_DST_COLOR" -> GL_DST_COLOR;
                case "GL_ONE_MINUS_DST_COLOR" -> GL_ONE_MINUS_DST_COLOR;
                case "GL_SRC_ALPHA_SATURATE" -> GL_SRC_ALPHA_SATURATE;
                case "GL_SRC1_COLOR" -> GL_SRC1_COLOR;
                case "GL_ONE_MINUS_SRC1_COLOR" -> GL_ONE_MINUS_SRC1_COLOR;
                case "GL_ONE_MINUS_SRC1_ALPHA" -> GL_ONE_MINUS_SRC1_ALPHA;
                default -> -1;
            };
        }
        @Override
        public Int2ObjectMap<BlendState> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json==null) return null;
            Int2ObjectMap<BlendState> ret = new Int2ObjectOpenHashMap<>();
            try {
                if (json.isJsonPrimitive()) {
                    if (json.getAsString().equalsIgnoreCase("off")) {
                        ret.put(-1, BlendState.ALL_OFF);
                        return ret;
                    }
                } else if (json.isJsonObject()) {
                    for (var entry : json.getAsJsonObject().entrySet()) {
                        int buffer = Integer.parseInt(entry.getKey());
                        BlendState state;
                        var val = entry.getValue();
                        if (val.isJsonArray()) {
                            int[] v = val.getAsJsonArray().asList().stream().mapToInt(a->parseType(a.getAsString())).toArray();
                            state = new BlendState(buffer, false, v[0], v[1], v[2], v[3]);
                        } else if (val.isJsonPrimitive()) {
                            if (val.getAsString().equalsIgnoreCase("off")) {
                                state = new BlendState(buffer, true, 0,0,0,0);
                            } else {
                                state = new BlendState(buffer, true, -1,-1,-1,-1);
                            }
                        } else {
                            state = null;
                        }
                        ret.put(buffer, state);
                    }
                    return ret;
                }
            } catch (Exception e) {
                Logger.error(e);
            }
            Logger.error("Failed to parse blend state: " + json);
            return ret;
        }
    }

    private static class PatchGson {
        public int version;//TODO maybe replace with semver?
        public int[] opaqueDrawBuffers;
        public int[] translucentDrawBuffers;
        public String[] uniforms;
        @JsonAdapter(SamplerDeserializer.class)
        public Object2ObjectLinkedOpenHashMap<String, String> samplers;
        public String[] opaquePatchData;
        public String[] translucentPatchData;
        @JsonAdapter(SSBODeserializer.class)
        public Int2ObjectOpenHashMap<String> ssbos;
        @JsonAdapter(BlendStateDeserializer.class)
        public Int2ObjectOpenHashMap<BlendState> blending;
        public String taaOffset;
        public boolean excludeLodsFromVanillaDepth;
        public boolean checkValid() {
            return this.opaqueDrawBuffers != null && this.translucentDrawBuffers != null && this.uniforms != null && this.opaquePatchData != null;
        }
    }



    private final PatchGson patchData;
    private final ShaderPack pack;
    private final Int2ObjectMap<String> ssbos;
    private IrisShaderPatch(PatchGson patchData, ShaderPack pack) {
        this.patchData = patchData;
        this.pack = pack;

        if (patchData.ssbos == null) {
            this.ssbos = new Int2ObjectOpenHashMap<>();
        } else {
            this.ssbos = patchData.ssbos;
        }
    }

    public Int2ObjectMap<String> getSSBOs() {
        return new Int2ObjectLinkedOpenHashMap<>(this.ssbos);
    }
    public String getPatchOpaqueSource() {
        return String.join("\n", this.patchData.opaquePatchData);
    }
    public String getPatchTranslucentSource() {
        return this.patchData.translucentPatchData!=null?String.join("\n", this.patchData.translucentPatchData):null;
    }
    public String getTAAShift() {
        return this.patchData.taaOffset == null?"{return vec2(0.0);}":this.patchData.taaOffset;
    }
    public String[] getUniformList() {
        return this.patchData.uniforms;
    }
    public Object2ObjectLinkedOpenHashMap<String, String> getSamplerSet() {
        return this.patchData.samplers;
    }


    public int[] getOpqaueTargets() {
        return this.patchData.opaqueDrawBuffers;
    }

    public int[] getTranslucentTargets() {
        return this.patchData.translucentDrawBuffers;
    }

    public boolean emitToVanillaDepth() {
        return !this.patchData.excludeLodsFromVanillaDepth;
    }

    public Runnable createBlendSetup() {
        if (this.patchData.blending == null || this.patchData.blending.isEmpty()) {
            return ()->{};//No blending change
        }
        return ()->{
            final var BS = this.patchData.blending;
            //Set inital state
            var init = BS.getOrDefault(-1, null);
            if (init != null) {
                if (init.off) {
                    glDisable(GL_BLEND);
                } else {
                    glEnable(GL_BLEND);
                    glBlendFuncSeparate(init.sRBG, init.dRGb, init.sA, init.dA);
                }
            }
            for (var entry:BS.int2ObjectEntrySet()) {
                if (entry.getIntKey() == -1) continue;
                final var s = entry.getValue();
                if (s.off) {
                    glDisablei(GL_BLEND, s.buffer);
                } else {
                    glEnablei(GL_BLEND, s.buffer);
                    glBlendFuncSeparatei(s.buffer, s.sRBG, s.dRGb, s.sA, s.dA);
                }
            }
        };
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
            {
                StringBuilder builder = new StringBuilder(voxyPatchData.length());
                //Rebuild the patch, replacing commented out " with \"
                for (var line : voxyPatchData.split("\n")) {
                    int idx = line.indexOf("//");
                    if (idx != -1) {
                        builder.append(line, 0, idx);
                        builder.append(line.substring(idx).replace("\"","\\\""));
                    } else {
                        builder.append(line);
                    }
                    builder.append("\n");
                }
                voxyPatchData = builder.toString();
            }
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
