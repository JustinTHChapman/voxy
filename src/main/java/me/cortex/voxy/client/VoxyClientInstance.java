package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private static Config PENDING_CONFIG;
    private static Path PENDING_BASE_PATH;

    private final Config config;
    private final Path basePath;
    private final boolean noIngestOverride;

    public VoxyClientInstance() {
        this(loadPending());
    }

    private VoxyClientInstance(Void preLoad) {
        super();
        this.noIngestOverride = false;
        this.basePath = PENDING_BASE_PATH;
        this.config = PENDING_CONFIG;
        PENDING_BASE_PATH = null;
        PENDING_CONFIG = null;
        this.updateDedicatedThreads();
    }

    private static Void loadPending() {
        PENDING_BASE_PATH = getBasePath().normalize();
        PENDING_CONFIG = StorageConfigUtil.getCreateStorageConfig(Config.class, c->c.version==2&&c.sectionStorageConfig!=null, ()->DEFAULT_STORAGE_CONFIG, PENDING_BASE_PATH);
        return null;
    }

    @Override
    protected boolean shouldCreateInstance() {
        Config cfg = this.config != null ? this.config : PENDING_CONFIG;
        return cfg != null && !cfg.disabled;
    }

    @Override
    public void updateDedicatedThreads() {
        // Sodium 0.6.13 builder-thread accessor mixin not yet ported; use full configured count.
        this.setNumThreads(VoxyConfig.CONFIG.serviceThreads);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID, Minecraft.getInstance().getUser().getProfileId().toString().replace(':','-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return (!this.noIngestOverride) && VoxyConfig.CONFIG.generateChunks;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        //Free the render resources cache since the entire instance is freed
        RenderResourceReuse.clearResources();
    }

    private static class Config {
        public int version = 2; // v2: switched default from Memory to SQLite
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = Minecraft.getInstance().getSingleplayerServer();
        if (iserver != null) {
            basePath = iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
        } else {
            var netHandle = Minecraft.getInstance().getConnection();
            if (netHandle == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                var info = netHandle.getServerData();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else {
                    if (info.isRealm()) {
                        basePath = basePath.resolve("realms");
                    } else {
                        basePath = basePath.resolve(info.ip.replace(":", "_"));
                    }
                }
            }
        }
        return basePath.toAbsolutePath();
    }
}
