package me.cortex.voxy.client.compat.moreculling.mixin;

import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * MoreCulling 1.0.8 calls the removed sodium 0.5 method
 * {@code SodiumClientMod.options()} from {@code areLeavesOpaque()}. On sodium
 * 0.6.x this crashes with {@link NoSuchMethodError} during chunk meshing.
 *
 * We overwrite the method to skip the sodium branch entirely and fall back to
 * the vanilla graphics-mode check. The only behavioural loss is sodium's
 * per-quality "leaves quality" override; vanilla fancy/fast leaves still work.
 */
@Pseudo
@Mixin(targets = "ca.fxco.moreculling.utils.CullingUtils", remap = false)
public class MixinMoreCullingCullingUtils {

    /**
     * @author voxy moreculling-sodium 0.6 compat
     * @reason avoid NoSuchMethodError on removed SodiumClientMod.options()
     */
    @Overwrite(remap = false)
    public static boolean areLeavesOpaque() {
        GraphicsStatus mode = Minecraft.getInstance().options.graphicsMode().get();
        return mode.getId() < GraphicsStatus.FANCY.getId();
    }
}
