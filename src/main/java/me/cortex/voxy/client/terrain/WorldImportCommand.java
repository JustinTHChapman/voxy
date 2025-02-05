package me.cortex.voxy.client.terrain;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxelCore;
import me.cortex.voxy.client.core.VoxelCore;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.command.CommandSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;


public class WorldImportCommand {
    public static LiteralArgumentBuilder<FabricClientCommandSource> register() {
        return ClientCommandManager.literal("voxy").requires((ctx)-> ((IGetVoxelCore)MinecraftClient.getInstance().worldRenderer).getVoxelCore() != null)
                .then(ClientCommandManager.literal("import")
                        .then(ClientCommandManager.literal("world")
                                .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                        .suggests(WorldImportCommand::importWorldSuggester)
                                        .executes(WorldImportCommand::importWorld)))
                        .then(ClientCommandManager.literal("bobby")
                                .then(ClientCommandManager.argument("world_name", StringArgumentType.string())
                                        .suggests(WorldImportCommand::importBobbySuggester)
                                        .executes(WorldImportCommand::importBobby)))
                        .then(ClientCommandManager.literal("raw")
                                .then(ClientCommandManager.argument("path", StringArgumentType.string())
                                        .executes(WorldImportCommand::importRaw)))
                        .then(ClientCommandManager.literal("zip")
                                .then(ClientCommandManager.argument("zipPath", StringArgumentType.string())
                                        .executes(WorldImportCommand::importZip)
                                        .then(ClientCommandManager.argument("innerPath", StringArgumentType.string())
                                                .executes(WorldImportCommand::importZip))))
                        .then(ClientCommandManager.literal("cancel")
                                .requires((ctx)->((IGetVoxelCore)MinecraftClient.getInstance().worldRenderer).getVoxelCore().importer.isImporterRunning())
                                .executes((ctx)->{((IGetVoxelCore)MinecraftClient.getInstance().worldRenderer).getVoxelCore().importer.stopImporter(); return 0;}))
        );
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = MinecraftClient.getInstance();
        var core = ((IGetVoxelCore)instance.worldRenderer).getVoxelCore();
        return core.importer.createWorldImporter(instance.player.clientWorld,
                (importer, up, done)->importer.importRegionDirectoryAsyncStart(directory, up, done));
    }

    private static int importRaw(CommandContext<FabricClientCommandSource> ctx) {
        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static int importBobby(CommandContext<FabricClientCommandSource> ctx) {
        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(MinecraftClient.getInstance().runDirectory.toPath().resolve("saves"), sb);
    }
    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(new File(".bobby").toPath(), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (CommandSource.shouldSuggest(sb.getRemaining(), wn) || CommandSource.shouldSuggest(sb.getRemaining(), '"'+wn)) {
                    if (wn.contains(" ")) {
                        wn = '"' + wn + '"';
                    }
                    sb.suggest(wn);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return sb.buildFuture();
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> ctx) {
        var file = new File("saves").toPath().resolve(ctx.getArgument("world_name", String.class)).resolve("region").toFile();
        return fileBasedImporter(file)?0:1;
    }

    private static int importZip(CommandContext<FabricClientCommandSource> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = MinecraftClient.getInstance();
        var core = ((IGetVoxelCore)instance.worldRenderer).getVoxelCore();
        if (core == null) {
            return 1;
        }
        String finalInnerDir = innerDir;
        return core.importer.createWorldImporter(instance.player.clientWorld,
                (importer, up, done)->importer.importZippedRegionDirectoryAsyncStart(zip, finalInnerDir, up, done))?0:1;
    }
}