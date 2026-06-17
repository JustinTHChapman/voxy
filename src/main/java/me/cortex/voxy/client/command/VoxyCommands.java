package me.cortex.voxy.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.generation.AutoGenerationService;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Client-side {@code /voxy} commands. Registered from {@link me.cortex.voxy.client.VoxyClient}
 * via {@code RegisterClientCommandsEvent}.
 */
public final class VoxyCommands {
    private VoxyCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("voxy")
                .then(Commands.literal("regen")
                        .executes(VoxyCommands::regenCurrentDimension)));
    }

    /**
     * Wipes all stored LOD sections for the current dimension and triggers regeneration. Useful for
     * clearing dark / corrupt LODs baked into the DB: the wiped columns read as missing, so auto-gen
     * re-voxelizes them (now with correct lighting), overwriting the stale sections.
     */
    private static int regenCurrentDimension(CommandContext<CommandSourceStack> ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ctx.getSource().sendFailure(Component.literal("[Voxy] Not in a world."));
            return 0;
        }
        WorldEngine engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null) {
            ctx.getSource().sendFailure(Component.literal("[Voxy] Not active for this dimension."));
            return 0;
        }
        String dim = mc.level.dimension().location().toString();
        ctx.getSource().sendSuccess(
                () -> Component.literal("[Voxy] Wiping LOD data for " + dim + " — this may take a moment…"), false);

        // Pause auto-generation for the duration of the wipe. We must NOT reset() yet: reset() lets the
        // background DB pre-scan run, which would race the off-thread wipe — reading the still-populated
        // table and re-marking every about-to-be-deleted column as already generated, so the world would
        // never regenerate. We reset() only once the wipe has finished (below), so the scan sees an empty
        // DB. beginWipe() also short-circuits tick() so no generation runs against the table mid-wipe.
        AutoGenerationService.INSTANCE.beginWipe();

        // Wipe storage off-thread — an explored world can hold tens of thousands of sections, and the
        // storage backend is synchronised so this is safe from any thread. When done, reset auto-gen and
        // refresh the renderer on the main thread; auto-gen then re-scans the now-empty DB and regenerates.
        final WorldEngine eng = engine;
        Thread t = new Thread(() -> {
            int deleted = 0;
            try {
                for (int lvl = 0; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
                    LongArrayList keys = new LongArrayList();
                    eng.storage.iteratePositions(lvl, keys::add); // collect first; don't delete mid-iteration
                    for (int i = 0; i < keys.size(); i++) {
                        eng.storage.deleteSection(keys.getLong(i));
                    }
                    deleted += keys.size();
                }
                eng.storage.flush();
            } catch (Exception e) {
                Logger.error("[Voxy] regen wipe failed", e);
            }
            final int total = deleted;
            mc.execute(() -> {
                // Wipe is complete: clear auto-gen state + resume (reset() clears the wiping pause). The
                // DB pre-scan now reads an empty table, so every column is treated as missing and
                // regenerated instead of being skipped as already-submitted.
                AutoGenerationService.INSTANCE.reset();
                // Recreate the render pipeline to drop stale GPU geometry; regeneration starts next tick.
                mc.levelRenderer.allChanged();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[Voxy] Wiped " + total + " LOD sections; regenerating…"), false);
                }
                Logger.info("[Voxy] regen wiped " + total + " sections for " + dim);
            });
        }, "voxy-regen-wipe");
        t.setDaemon(true);
        t.start();
        return 1;
    }
}
