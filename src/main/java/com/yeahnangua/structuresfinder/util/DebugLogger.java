package com.yeahnangua.structuresfinder.util;

import com.yeahnangua.structuresfinder.StructuresFinder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Debug logger that writes detailed timing information to log.log file.
 */
public class DebugLogger {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static File logFile;

    /**
     * Initializes the log file.
     */
    private static void ensureLogFile() {
        if (logFile == null) {
            logFile = new File(StructuresFinder.getInstance().getDataFolder(), "log.log");
            if (!logFile.getParentFile().exists()) {
                logFile.getParentFile().mkdirs();
            }
        }
    }

    /**
     * Logs a message with timestamp to the log file.
     */
    public static void log(String message) {
        ensureLogFile();
        String timestamp = LocalDateTime.now().format(formatter);
        String logLine = "[" + timestamp + "] " + message;

        try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)))) {
            writer.println(logLine);
        } catch (IOException e) {
            StructuresFinder.getInstance().getLogger().warning("Failed to write to log file: " + e.getMessage());
        }

        // Also log to console for immediate visibility
        StructuresFinder.getInstance().getLogger().info(message);
    }

    /**
     * Logs a timing message.
     */
    public static void logTiming(String operation, long startTimeMs) {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        log(String.format("[TIMING] %s took %d ms", operation, elapsed));
    }

    /**
     * Clears the log file.
     */
    public static void clearLog() {
        ensureLogFile();
        try (PrintWriter writer = new PrintWriter(logFile)) {
            writer.print("");
        } catch (IOException e) {
            StructuresFinder.getInstance().getLogger().warning("Failed to clear log file: " + e.getMessage());
        }
    }
}
