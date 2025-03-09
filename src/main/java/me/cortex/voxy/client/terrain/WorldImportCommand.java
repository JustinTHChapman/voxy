package me.cortex.voxy.client.terrain;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
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
        return ClientCommandManager.literal("voxy").requires((ctx)-> VoxyCommon.getInstance() != null)
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
                                //.requires((ctx)->((IGetVoxelCore)MinecraftClient.getInstance().worldRenderer).getVoxelCore().importer.isImporterRunning())
                                .executes(WorldImportCommand::cancelImport))
        );
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }
        return instance.importWrapper.createWorldImporter(MinecraftClient.getInstance().player.clientWorld,
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
        return fileDirectorySuggester(MinecraftClient.getInstance().runDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (CommandSource.shouldSuggest(remaining, wn) || CommandSource.shouldSuggest(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }

    private static int importWorld(CommandContext<FabricClientCommandSource> ctx) {
        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase();
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (!(name.endsWith("region"))) {
            file = file.resolve("region");
        }
        return fileBasedImporter(file.toFile())?0:1;
    }

    private static int importZip(CommandContext<FabricClientCommandSource> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return 1;
        }
        String finalInnerDir = innerDir;
        return instance.importWrapper.createWorldImporter(MinecraftClient.getInstance().player.clientWorld,
                (importer, up, done)->importer.importZippedRegionDirectoryAsyncStart(zip, finalInnerDir, up, done))?0:1;
    }

    private static int cancelImport(CommandContext<FabricClientCommandSource> fabricClientCommandSourceCommandContext) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return 1;
        }
        instance.importWrapper.stopImporter();
        return 0;
    }
}