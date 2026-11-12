package com.udpsendtofailed.modmenu.updater;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class CleanupManager {
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    private static Path defaultConfigDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("modmenu_updater");
    }

    private static synchronized List<String> readPaths(Path cleanupFile) {
        if (!Files.exists(cleanupFile)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(cleanupFile)) {
            List<String> paths = GSON.fromJson(reader, LIST_TYPE);
            return paths != null ? new ArrayList<>(paths) : new ArrayList<>();
        } catch (Exception e) {
            ModMenuUpdater.LOGGER.error("Failed to read cleanup file", e);
            return new ArrayList<>();
        }
    }

    private static synchronized void writePaths(Path configDir, Path cleanupFile, List<String> paths) {
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            try (Writer writer = Files.newBufferedWriter(cleanupFile)) {
                GSON.toJson(paths, writer);
            }
        } catch (Exception e) {
            ModMenuUpdater.LOGGER.error("Failed to write cleanup file", e);
        }
    }

    public static synchronized void scheduleForCleanup(Path path) {
        scheduleForCleanup(path, defaultConfigDir());
    }

    static synchronized void scheduleForCleanup(Path path, Path configDir) {
        if (path == null) return;
        Path cleanupFile = configDir.resolve("cleanup.json");
        String abs = path.toAbsolutePath().toString();
        List<String> paths = readPaths(cleanupFile);
        if (!paths.contains(abs)) {
            paths.add(abs);
            writePaths(configDir, cleanupFile, paths);
            ModMenuUpdater.LOGGER.info("Scheduled {} for cleanup on next launch.", path.getFileName());
        }
    }

    public static void performCleanup() {
        Path configDir = defaultConfigDir();
        performCleanup(configDir, configDir.resolve("backups"));
    }

    static void performCleanup(Path configDir, Path backupDir) {
        Path cleanupFile = configDir.resolve("cleanup.json");
        List<String> pathsToClean = readPaths(cleanupFile);
        if (pathsToClean.isEmpty()) {
            return;
        }
        ModMenuUpdater.LOGGER.info("Performing cleanup of {} old mod files...", pathsToClean.size());

        List<String> remaining = new ArrayList<>();
        for (String pathStr : pathsToClean) {
            Path fileToClean = Paths.get(pathStr);
            if (!BackupManager.performBackup(fileToClean, backupDir)) {
                ModMenuUpdater.LOGGER.warn("Could not clean up {}; keeping in cleanup queue.", fileToClean.getFileName());
                remaining.add(pathStr);
            }
        }

        writePaths(configDir, cleanupFile, remaining);
    }
}
