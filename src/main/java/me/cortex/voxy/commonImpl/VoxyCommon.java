package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.thread.ServiceThreadPool;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public class VoxyCommon implements ModInitializer {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;

    static {
        ModContainer mod = (ModContainer) FabricLoader.getInstance().getModContainer("voxy").orElseThrow(NullPointerException::new);
        var version = mod.getMetadata().getVersion().getFriendlyString();
        var commit = mod.getMetadata().getCustomValue("commit").getAsString();
        MOD_VERSION = version+"-"+commit;
        IS_DEDICATED_SERVER = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
        Serialization.init();

    }

    @Override
    public void onInitialize() {
        //this.serviceThreadPool = new ServiceThreadPool(VoxyConfig.CONFIG.serviceThreads);

        //TODO: need to have a common config with server/client configs deriving from it
        // maybe server/client extend it? or something? cause like client needs server config (at least partially sometimes)
        // but server doesnt need client config
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }
}
