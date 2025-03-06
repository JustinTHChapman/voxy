package me.cortex.voxy.common;

import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Logger {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("Voxy");

    public static void error(Object... args) {
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }
        var stackEntry = new Throwable().getStackTrace()[1];
        String error = "["+stackEntry.getClassName()+"]: "+ Stream.of(args).map(Object::toString).collect(Collectors.joining(" "));
        LOGGER.error(error, throwable);
        if (!VoxyCommon.IS_DEDICATED_SERVER) {
            MinecraftClient.getInstance().executeSync(()->{var player = MinecraftClient.getInstance().player; if (player != null)  player.sendMessage(Text.literal(error), true);});
        }
    }

    public static void warn(Object... args) {
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }
        var stackEntry = new Throwable().getStackTrace()[1];
        LOGGER.warn("["+stackEntry.getClassName()+"]: "+ Stream.of(args).map(Object::toString).collect(Collectors.joining(" ")), throwable);
    }

    public static void info(Object... args) {
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }
        var stackEntry = new Throwable().getStackTrace()[1];
        LOGGER.info("["+stackEntry.getClassName()+"]: "+ Stream.of(args).map(Object::toString).collect(Collectors.joining(" ")), throwable);
    }
}
