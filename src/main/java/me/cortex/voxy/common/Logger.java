package me.cortex.voxy.common;

import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Logger {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("Voxy");

    public static void logError(Object... args) {
        Throwable throwable = null;
        for (var i : args) {
            if (i instanceof Throwable) {
                throwable = (Throwable) i;
            }
        }
        var stackEntry = new Throwable().getStackTrace()[1];
        LOGGER.error("["+stackEntry.getClassName()+"]: "+ Stream.of(args).map(Object::toString).collect(Collectors.joining(" ")), throwable);
    }
}
