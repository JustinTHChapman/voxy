package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.config.Serialization;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public class VoxyCommon implements ModInitializer {
    private static VoxyInstance INSTANCE;

    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;

    static {
        ModContainer mod = (ModContainer) FabricLoader.getInstance().getModContainer("voxy").orElse(null);
        if (mod == null) {
            System.err.println("RUNNING WITHOUT MOD");
            MOD_VERSION = "<UNKNOWN>";
            IS_DEDICATED_SERVER = false;
        } else {
            var version = mod.getMetadata().getVersion().getFriendlyString();
            var commit = mod.getMetadata().getCustomValue("commit").getAsString();
            MOD_VERSION = version + "-" + commit;
            IS_DEDICATED_SERVER = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
            Serialization.init();
        }
    }

    @Override
    public void onInitialize() {
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }


    //This is hardcoded like this because people do not understand what they are doing
    private static final boolean GlobalVerificationDisableOverride = false;//System.getProperty("voxy.verificationDisableOverride", "false").equals("true");
    public static boolean isVerificationFlagOn(String name) {
        return (!GlobalVerificationDisableOverride) && System.getProperty("voxy."+name, "true").equals("true");
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            INSTANCE.shutdown();
            INSTANCE = null;
        }
    }

    public static void createInstance() {
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        INSTANCE = new VoxyInstance(12);
    }
}
