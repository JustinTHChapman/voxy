package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.Serialization;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforgespi.language.IModInfo;

public class VoxyCommon {
    public static final String MOD_VERSION;
    public static final boolean IS_DEDICATED_SERVER;
    public static final boolean IS_IN_MINECRAFT;

    static {
        boolean inMc;
        String version;
        boolean dedicated;
        try {
            var modList = ModList.get();
            IModInfo info = (modList == null) ? null : modList.getModContainerById("voxy").map(c -> c.getModInfo()).orElse(null);
            if (info == null) {
                inMc = false;
                version = "<UNKNOWN>";
                dedicated = false;
                Logger.error("Running voxy without minecraft");
            } else {
                inMc = true;
                version = info.getVersion().toString();
                dedicated = FMLEnvironment.dist == Dist.DEDICATED_SERVER;
            }
        } catch (Throwable t) {
            inMc = false;
            version = "<UNKNOWN>";
            dedicated = false;
            Logger.error("Failed to query ModList: " + t.getMessage());
        }
        IS_IN_MINECRAFT = inMc;
        MOD_VERSION = version;
        IS_DEDICATED_SERVER = dedicated;
        if (IS_IN_MINECRAFT) {
            Serialization.init();
        }
    }

    public static boolean isVerificationFlagOn(String name) {
        return isVerificationFlagOn(name, false);
    }

    public static boolean isVerificationFlagOn(String name, boolean defaultOn) {
        return System.getProperty("voxy."+name, defaultOn?"true":"false").equals("true");
    }

    public static void breakpoint() {
        int breakpoint = 0;
    }

    public interface IInstanceFactory {VoxyInstance create();}
    private static VoxyInstance INSTANCE;
    private static IInstanceFactory FACTORY = null;

    public static void setInstanceFactory(IInstanceFactory factory) {
        if (FACTORY != null) {
            throw new IllegalStateException("Cannot set instance factory more than once");
        }
        FACTORY = factory;
    }

    public static VoxyInstance getInstance() {
        return INSTANCE;
    }

    public static void shutdownInstance() {
        if (INSTANCE != null) {
            var instance = INSTANCE;
            INSTANCE = null;
            instance.shutdown();
        }
    }

    public static void createInstance() {
        if (FACTORY == null) {
            return;
        }
        if (INSTANCE != null) {
            throw new IllegalStateException("Cannot create multiple instances");
        }
        try {
            INSTANCE = FACTORY.create();
        } catch (DontCreateInstance e) {
            Logger.info("Not creating instance due to DontCreateInstance");
        }
    }

    public static boolean isAvailable() {
        return FACTORY != null;
    }

    public static final boolean IS_MINE_IN_ABYSS = false;
}
