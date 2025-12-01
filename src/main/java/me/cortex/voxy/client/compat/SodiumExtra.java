package me.cortex.voxy.client.compat;

import me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod;
import net.fabricmc.loader.api.FabricLoader;

public class SodiumExtra {
    public static final boolean HAS_SODIUM_EXTRA = FabricLoader.getInstance().isModLoaded("sodium-extra");
    public static boolean useSodiumExtraCulling() {
        if (!HAS_SODIUM_EXTRA) {
            return false;
        }
        return useSodiumExtraCulling0();
    }

    private static boolean useSodiumExtraCulling0() {
        return !SodiumExtraClientMod.options().renderSettings.globalFog;
    }
}
