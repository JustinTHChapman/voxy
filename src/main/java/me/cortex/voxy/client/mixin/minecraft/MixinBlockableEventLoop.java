package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.LoadException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockableEventLoop.class)
public abstract class MixinBlockableEventLoop {

    @Redirect(method = "doRunTask", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void voxy$forceCrashOnError(Runnable task) {
        try {
            task.run();
        } catch (LoadException le) {
            if (le.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw le;
        }
    }
}
