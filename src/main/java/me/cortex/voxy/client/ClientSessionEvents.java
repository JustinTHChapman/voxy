package me.cortex.voxy.client;

import me.cortex.voxy.client.config.ServerConfigOverride;
import me.cortex.voxy.client.core.generation.AutoGenerationService;
import me.cortex.voxy.client.sync.ManifestSyncHandler;
import me.cortex.voxy.commonImpl.VoxyCommon;

public class ClientSessionEvents {
    public static boolean inSession = false;

    public static void sessionStart() {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;

        //Should never try creating multiple instances via session start
        if (VoxyCommon.getInstance() != null) throw new IllegalStateException();

        if (VoxyCommon.isAvailable()) {
            VoxyCommon.createInstance();
        }
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        inSession = false;

        ServerConfigOverride.INSTANCE.reset();
        ManifestSyncHandler.INSTANCE.reset();
        AutoGenerationService.INSTANCE.reset();
        VoxyCommon.shutdownInstance();
    }
}
