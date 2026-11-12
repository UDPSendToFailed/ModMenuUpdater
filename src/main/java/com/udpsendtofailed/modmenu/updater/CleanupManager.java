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
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("modmenu_updater");
    private static final Path CLEANUP_FILE = CONFIG_DIR.resolve("cleanup.json");
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    private static synchronized List<String> readPaths() {
        if (!Files.exists(CLEANUP_FILE)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(CLEANUP_FILE)) {
            List<String> paths = GSON.fromJson(reader, LIST_TYPE);
            return paths != null ? new ArrayList<>(paths) : new ArrayList<>();
        } catch (Exception e) {
            ModMenuUpdater.LOGGER.error("Failed to read cleanup file", e);
            return new ArrayList<>();
        }
    }

    private static synchronized void writePaths(List<String> paths) {
        try {
            if (!Files.exists(CONFIG_DIR)) {
                Files.createDirectories(CONFIG_DIR);
            }
            try (Writer writer = Files.newBufferedWriter(CLEANUP_FILE)) {
                GSON.toJson(paths, writer);
            }
        } catch (Exception e) {
            ModMenuUpdater.LOGGER.error("Failed to write cleanup file", e);
        }
    }

    public static synchronized void scheduleForCleanup(Path path) {
        if (path == null) return;
        String abs = path.toAbsolutePath().toString();
        List<String> paths = readPaths();
        if (!paths.contains(abs)) {
            paths.add(abs);
            writePaths(paths);
            ModMenuUpdater.LOGGER.info("Scheduled {} for cleanup on next launch.", path.getFileName());
        }
    }

    public static void performCleanup() {
        List<String> pathsToClean = readPaths();
        if (pathsToClean.isEmpty()) {
            return;
        }
        ModMenuUpdater.LOGGER.info("Performing cleanup of {} old mod files...", pathsToClean.size());

        List<String> remaining = new ArrayList<>();
        for (String pathStr : pathsToClean) {
            Path fileToClean = Paths.get(pathStr);
            if (!BackupManager.performBackup(fileToClean)) {
                ModMenuUpdater.LOGGER.warn("Could not clean up {}; keeping in cleanup queue.", fileToClean.getFileName());
                remaining.add(pathStr);
            }
        }

        writePaths(remaining);
    }
}
