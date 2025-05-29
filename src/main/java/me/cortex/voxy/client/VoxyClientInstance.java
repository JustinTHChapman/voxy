package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class VoxyClientInstance extends VoxyInstance {
    public static boolean isInGame = false;

    private final SectionStorageConfig storageConfig;
    private final Path basePath = getBasePath();
    public VoxyClientInstance() {
        super(VoxyConfig.CONFIG.serviceThreads);
        this.storageConfig = getCreateStorageConfig(this.basePath);
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, getWorldId(identifier));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.storageConfig.build(ctx);
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static String getWorldId(WorldIdentifier identifier) {
        String data = identifier.biomeSeed + identifier.key.toString();
        try {
            return bytesToHex(MessageDigest.getInstance("SHA-256").digest(data.getBytes())).substring(0, 32);
        } catch (
                NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static SectionStorageConfig getCreateStorageConfig(Path path) {
        var json = path.resolve("config.json");
        Config config = null;
        if (Files.exists(json)) {
            try {
                config = Serialization.GSON.fromJson(Files.readString(json), Config.class);
                if (config == null) {
                    throw new IllegalStateException("Config deserialization null, reverting to default");
                }
                if (config.sectionStorageConfig == null) {
                    throw new IllegalStateException("Config section storage null, reverting to default");
                }
            } catch (Exception e) {
                Logger.error("Failed to load the storage configuration file, resetting it to default, this will probably break your save if you used a custom storage config", e);
            }
        }

        if (config == null) {
            config = DEFAULT_STORAGE_CONFIG;
        }
        try {
            Files.writeString(json, Serialization.GSON.toJson(config));
        } catch (Exception e) {
            throw new RuntimeException("Failed write the config, aborting!", e);
        }
        if (config == null) {
            throw new IllegalStateException("Config is still null\n");
        }
        return config.sectionStorageConfig;
    }

    private static class Config {
        public SectionStorageConfig sectionStorageConfig;
    }
    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();

        //Load the default config
        var baseDB = new RocksDBStorageBackend.Config();

        var compressor = new ZSTDCompressor.Config();
        compressor.compressionLevel = 1;

        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = baseDB;
        compression.compressor = compressor;

        var serializer = new SectionSerializationStorage.Config();
        serializer.storage = compression;
        config.sectionStorageConfig = serializer;

        DEFAULT_STORAGE_CONFIG = config;
    }

    private static Path getBasePath() {
        Path basePath = MinecraftClient.getInstance().runDirectory.toPath().resolve(".voxy").resolve("saves");
        var iserver = MinecraftClient.getInstance().getServer();
        if (iserver != null) {
            basePath = iserver.getSavePath(WorldSavePath.ROOT).resolve("voxy");
        } else {
            var netHandle = MinecraftClient.getInstance().interactionManager;
            if (netHandle == null) {
                Logger.error("Network handle null");
                basePath = basePath.resolve("UNKNOWN");
            } else {
                var info = netHandle.networkHandler.getServerInfo();
                if (info == null) {
                    Logger.error("Server info null");
                    basePath = basePath.resolve("UNKNOWN");
                } else {
                    if (info.isRealm()) {
                        basePath = basePath.resolve("realms");
                    } else {
                        basePath = basePath.resolve(info.address.replace(":", "_"));
                    }
                }
            }
        }
        return basePath;
    }

    /*
    private static void testDbPerformance(WorldEngine engine) {
        Random r = new Random(123456);
        r.nextLong();
        long start = System.currentTimeMillis();
        int c = 0;
        long tA = 0;
        long tR = 0;
        for (int i = 0; i < 1_000_000; i++) {
            if (i == 20_000) {
                c = 0;
                start = System.currentTimeMillis();
            }
            c++;
            int x = (r.nextInt(256*2+2)-256);//-32
            int z = (r.nextInt(256*2+2)-256);//-32
            int y = r.nextInt(2)-1;
            int lvl = 0;//r.nextInt(5);
            long t = System.nanoTime();
            var sec = engine.acquire(WorldEngine.getWorldSectionId(lvl, x>>lvl, y>>lvl, z>>lvl));
            tA += System.nanoTime()-t;
            t = System.nanoTime();
            sec.release();
            tR += System.nanoTime()-t;
        }
        long delta = System.currentTimeMillis() - start;
        System.out.println("Total "+delta+"ms " + ((double)delta/c) + "ms average tA: " + tA + " tR: " + tR);
    }
    private static void testDbPerformance2(WorldEngine engine) {
        Random r = new Random(123456);
        r.nextLong();
        ConcurrentLinkedDeque<Long> queue = new ConcurrentLinkedDeque<>();
        var ser = engine.instanceIn.getThreadPool().createServiceNoCleanup("aa", 1, ()-> () ->{
            var sec = engine.acquire(queue.poll());
            sec.release();
        });
        int priming = 1_000_000;
        for (int i = 0; i < 2_000_000+priming; i++) {
            int x = (r.nextInt(256*2+2)-256)>>2;//-32
            int z = (r.nextInt(256*2+2)-256)>>2;//-32
            int y = r.nextInt(2)-1;
            int lvl = 0;//r.nextInt(5);
            queue.add(WorldEngine.getWorldSectionId(lvl, x>>lvl, y>>lvl, z>>lvl));
        }
        for (int i = 0; i < priming; i++) {
            ser.execute();
        }
        ser.blockTillEmpty();
        int c = queue.size();
        long start = System.currentTimeMillis();
        for (int i = 0; i < c; i++) {
            ser.execute();
        }
        ser.blockTillEmpty();
        long delta = System.currentTimeMillis() - start;
        ser.shutdown();
        System.out.println("Total "+delta+"ms " + ((double)delta/c) + "ms average total, avg wrt threads: " + (((double)delta/c)*engine.instanceIn.getThreadPool().getThreadCount()) + "ms");
    }


    private void verifyTopNodeChildren(int X, int Y, int Z) {
        var world = this.getOrMakeRenderWorld(MinecraftClient.getInstance().world);
        for (int lvl = 0; lvl < 5; lvl++) {
            for (int y = (Y<<5)>>lvl; y < ((Y+1)<<5)>>lvl; y++) {
                for (int x = (X<<5)>>lvl; x < ((X+1)<<5)>>lvl; x++) {
                    for (int z = (Z<<5)>>lvl; z < ((Z+1)<<5)>>lvl; z++) {
                        if (lvl == 0) {
                            var own = world.acquire(lvl, x, y, z);
                            if ((own.getNonEmptyChildren() != 0) ^ (own.getNonEmptyBlockCount() != 0)) {
                                Logger.error("Lvl 0 node not marked correctly " + WorldEngine.pprintPos(own.key));
                            }
                            own.release();
                        } else {
                            byte msk = 0;
                            for (int child = 0; child < 8; child++) {
                                var section = world.acquire(lvl-1, (child&1)+(x<<1), ((child>>2)&1)+(y<<1), ((child>>1)&1)+(z<<1));
                                msk |= (byte) (section.getNonEmptyBlockCount()!=0?(1<<child):0);
                                section.release();
                            }
                            var own = world.acquire(lvl, x, y, z);
                            if (own.getNonEmptyChildren() != msk) {
                                Logger.error("Section empty child mask not correct " + WorldEngine.pprintPos(own.key) + " got: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(own.getNonEmptyChildren()))).replace(' ', '0') + " expected: " + String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(msk))).replace(' ', '0'));
                            }
                            own.release();
                        }
                    }
                }
            }
        }
    }
     */
}
