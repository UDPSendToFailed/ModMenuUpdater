package com.udpsendtofailed.modmenu.updater;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CleanupManagerTest {
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    @TempDir
    Path tempDir;

    @Test
    void scheduleDeduplicatesAndPerformCleanupMovesFiles() throws IOException {
        Path configDir = tempDir.resolve("config");
        Path backupDir = configDir.resolve("backups");
        Path modsDir = tempDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path jar = createDummyModJar(modsDir.resolve("mod-1.0.jar"), "testmod");

        CleanupManager.scheduleForCleanup(jar, configDir);
        CleanupManager.scheduleForCleanup(jar, configDir);

        List<String> scheduled = readCleanupList(configDir.resolve("cleanup.json"));
        assertEquals(1, scheduled.size());

        CleanupManager.performCleanup(configDir, backupDir);

        assertFalse(Files.exists(jar));
        assertTrue(Files.exists(backupDir.resolve("mod-1.0.jar")));
        assertTrue(readCleanupList(configDir.resolve("cleanup.json")).isEmpty());
    }

    @Test
    void performCleanupRetainsLockedFilesInQueue() throws IOException {
        Path configDir = tempDir.resolve("config");
        Path backupDir = configDir.resolve("backups");
        Path modsDir = tempDir.resolve("mods");
        Files.createDirectories(modsDir);

        Path unlockedJar = createDummyModJar(modsDir.resolve("unlocked-1.0.jar"), "unlocked");
        Path lockedJar = createDummyModJar(modsDir.resolve("locked-1.0.jar"), "locked");

        CleanupManager.scheduleForCleanup(unlockedJar, configDir);
        CleanupManager.scheduleForCleanup(lockedJar, configDir);

        try (FileChannel channel = FileChannel.open(lockedJar, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            CleanupManager.performCleanup(configDir, backupDir);
        }

        assertFalse(Files.exists(unlockedJar));
        assertTrue(Files.exists(backupDir.resolve("unlocked-1.0.jar")));

        List<String> remaining = readCleanupList(configDir.resolve("cleanup.json"));
        if (Files.exists(lockedJar)) {
            assertEquals(List.of(lockedJar.toAbsolutePath().toString()), remaining);
        } else {
            assertTrue(remaining.isEmpty());
        }
    }

    private static List<String> readCleanupList(Path cleanupFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(cleanupFile)) {
            return GSON.fromJson(reader, LIST_TYPE);
        }
    }

    private static Path createDummyModJar(Path path, String modId) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(path))) {
            zos.putNextEntry(new ZipEntry("fabric.mod.json"));
            String json = "{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\"}";
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return path;
    }
}

