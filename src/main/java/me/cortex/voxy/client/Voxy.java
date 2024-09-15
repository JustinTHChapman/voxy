package me.cortex.voxy.client;

import me.cortex.voxy.client.core.VoxelCore;
import me.cortex.voxy.client.saver.ContextSelectionSystem;
import me.cortex.voxy.client.terrain.WorldImportCommand;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.storage.compressors.ZSTDCompressor;
import me.cortex.voxy.common.storage.config.StorageConfig;
import net.fabricmc.api.ClientModInitializer;
        import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.world.ClientWorld;

import java.util.Arrays;

public class Voxy implements ClientModInitializer {
    public static final boolean SHADER_DEBUG;
    static {
        SHADER_DEBUG = System.getProperty("voxy.shaderDebug", "false").equals("true");
    }

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(WorldImportCommand.register());
        });
    }


    private static final ContextSelectionSystem SELECTOR = new ContextSelectionSystem();

    public static VoxelCore createVoxelCore(ClientWorld world) {
        var selection = SELECTOR.getBestSelectionOrCreate(world);
        return new VoxelCore(selection);
    }
}
