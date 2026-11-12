package com.udpsendtofailed.modmenu.updater;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class BackupManager {
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("modmenu_updater");
    private static final Path BACKUP_DIR = CONFIG_DIR.resolve("backups");
    private static final Path INDEX_FILE = BACKUP_DIR.resolve("backups.json");
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private record ModJarInfo(String id) {}

    public static synchronized boolean performBackup(Path fileToBackup) {
        if (!Files.exists(fileToBackup)) {
            return true;
        }

        Optional<ModJarInfo> modInfo = readModInfo(fileToBackup);
        if (modInfo.isEmpty()) {
            ModMenuUpdater.LOGGER.warn("Could not read mod info from {}, moving to backups without cleanup.", fileToBackup.getFileName());
            return moveWithoutCleanup(fileToBackup);
        }

        try {
            if (!Files.exists(BACKUP_DIR)) {
                Files.createDirectories(BACKUP_DIR);
            }

            Map<String, String> index = readIndex();

            if (index.containsKey(modInfo.get().id())) {
                Path oldBackupPath = Paths.get(index.get(modInfo.get().id()));
                ModMenuUpdater.LOGGER.info("Removing old backup for {}: {}", modInfo.get().id(), oldBackupPath.getFileName());
                Files.deleteIfExists(oldBackupPath);
            }

            Path destination = BACKUP_DIR.resolve(fileToBackup.getFileName());
            Files.move(fileToBackup, destination, StandardCopyOption.REPLACE_EXISTING);
            index.put(modInfo.get().id(), destination.toAbsolutePath().toString());
            writeIndex(index);
            ModMenuUpdater.LOGGER.info("Backed up {} to {}", fileToBackup.getFileName(), destination.getFileName());
            return true;

        } catch (IOException e) {
            ModMenuUpdater.LOGGER.debug("Could not move {} to backups: {}", fileToBackup.getFileName(), e.getMessage());
            return false;
        }
    }

    private static boolean moveWithoutCleanup(Path file) {
        try {
            if (!Files.exists(BACKUP_DIR)) {
                Files.createDirectories(BACKUP_DIR);
            }
            Path destination = BACKUP_DIR.resolve(file.getFileName());
            Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            ModMenuUpdater.LOGGER.debug("Failed to move file to backups: {}", file.getFileName());
            return false;
        }
    }

    private static Optional<ModJarInfo> readModInfo(Path jarPath) {
        try (FileSystem fs = FileSystems.newFileSystem(jarPath)) {
            Path modJsonPath = fs.getPath("fabric.mod.json");
            if (!Files.exists(modJsonPath)) return Optional.empty();
            
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(modJsonPath))) {
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                String id = json.getAsJsonPrimitive("id").getAsString();
                return Optional.of(new ModJarInfo(id));
            }
        } catch (Exception e) {
            ModMenuUpdater.LOGGER.warn("Could not read mod info from {}: {}", jarPath.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, String> readIndex() {
        if (!Files.exists(INDEX_FILE)) {
            return new HashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(INDEX_FILE)) {
            Map<String, String> index = GSON.fromJson(reader, MAP_TYPE);
            return index != null ? new HashMap<>(index) : new HashMap<>();
        } catch (IOException e) {
            ModMenuUpdater.LOGGER.error("Failed to read backup index", e);
            return new HashMap<>();
        }
    }

    private static void writeIndex(Map<String, String> index) {
        try (Writer writer = Files.newBufferedWriter(INDEX_FILE)) {
            GSON.toJson(index, writer);
        } catch (IOException e) {
            ModMenuUpdater.LOGGER.error("Failed to write backup index", e);
        }
    }
}
