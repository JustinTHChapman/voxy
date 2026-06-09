package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.loading.FMLPaths;

import com.google.gson.JsonObject;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    /**
     * Draw Voxy's stored LOD geometry. When false, Voxy still {@link #generateChunks
     * ingests and stores} LOD data but never renders it — e.g. for background/headless
     * world pre-generation. Config key: {@code draw_lods}.
     */
    public boolean drawLods = true;

    /**
     * Capture and voxelize the chunks the player loads into Voxy's LOD store. When false,
     * Voxy still {@link #drawLods draws} existing LODs but stops writing new data, so the
     * stored world is effectively frozen. Config key: {@code generate_chunks}.
     *
     * <p>Note: this is the passive "capture what you load" path. The proactive background
     * generation that fills in chunks you have not visited is the separate
     * {@code auto_generation_enabled} NeoForge setting.
     */
    public boolean generateChunks = true;

    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public boolean useEnvironmentalFog = true;
    public boolean dontUseSodiumBuilderThreads = false;
    public String ssaoMode;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return SSAO.SSAOMode.AUTO; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }


    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    // Load as JsonObject so we can fill in defaults for any fields
                    // that are absent in the file (e.g. newly-added fields).  Gson
                    // may use Unsafe allocation, which bypasses field-initialiser
                    // defaults; merging against a freshly-constructed default config
                    // ensures every field gets its intended value.
                    JsonObject fileJson = GSON.fromJson(reader, JsonObject.class);
                    if (fileJson != null) {
                        // Migrate keys renamed in newer versions, then drop removed ones.
                        migrateKey(fileJson, "enable_rendering", "draw_lods");
                        migrateKey(fileJson, "ingest_enabled", "generate_chunks");
                        fileJson.remove("enabled"); // removed: master toggle — disable Voxy via the mod loader instead

                        JsonObject merged = GSON.toJsonTree(new VoxyConfig()).getAsJsonObject();
                        for (var entry : fileJson.entrySet()) {
                            merged.add(entry.getKey(), entry.getValue());
                        }
                        var conf = GSON.fromJson(merged, VoxyConfig.class);
                        if (conf != null) {
                            conf.save();
                            return conf;
                        }
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not load config", e);
                } catch (JsonParseException e) {
                    Logger.error("Could not parse config", e);
                }
            }
            Logger.info("Error during config loading, creating new");
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.drawLods = false;
            return config;
        }
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    /** Copy {@code oldKey}'s value to {@code newKey} (unless already present), then drop {@code oldKey}. */
    private static void migrateKey(JsonObject json, String oldKey, String newKey) {
        if (json.has(oldKey)) {
            if (!json.has(newKey)) {
                json.add(newKey, json.get(oldKey));
            }
            json.remove(oldKey);
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.drawLods;
    }
}
