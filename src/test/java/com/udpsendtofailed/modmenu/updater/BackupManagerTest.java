package com.udpsendtofailed.modmenu.updater;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BackupManagerTest {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    @TempDir
    Path tempDir;

    @Test
    void performBackupMovesModJarAndReplacesPreviousBackupForSameId() throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path backupDir = tempDir.resolve("backups");
        Files.createDirectories(modsDir);

        Path oldJar = createDummyModJar(modsDir.resolve("example-1.0.0.jar"), "examplemod");
        assertTrue(BackupManager.performBackup(oldJar, backupDir));
        assertFalse(Files.exists(oldJar));
        assertTrue(Files.exists(backupDir.resolve("example-1.0.0.jar")));

        Path newerOldJar = createDummyModJar(modsDir.resolve("example-1.1.0.jar"), "examplemod");
        assertTrue(BackupManager.performBackup(newerOldJar, backupDir));
        assertFalse(Files.exists(newerOldJar));
        assertFalse(Files.exists(backupDir.resolve("example-1.0.0.jar")));
        assertTrue(Files.exists(backupDir.resolve("example-1.1.0.jar")));

        try (Reader reader = Files.newBufferedReader(backupDir.resolve("backups.json"))) {
            Map<String, String> index = GSON.fromJson(reader, MAP_TYPE);
            assertEquals(backupDir.resolve("example-1.1.0.jar").toAbsolutePath().toString(), index.get("examplemod"));
        }
    }

    @Test
    void performBackupMovesJarWithoutModJsonAndHandlesMissingFile() throws IOException {
        Path modsDir = tempDir.resolve("mods");
        Path backupDir = tempDir.resolve("backups");
        Files.createDirectories(modsDir);

        Path plainJar = modsDir.resolve("plain.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(plainJar))) {
            zos.putNextEntry(new ZipEntry("dummy.txt"));
            zos.write("hello".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        assertTrue(BackupManager.performBackup(plainJar, backupDir));
        assertFalse(Files.exists(plainJar));
        assertTrue(Files.exists(backupDir.resolve("plain.jar")));

        assertTrue(BackupManager.performBackup(modsDir.resolve("does-not-exist.jar"), backupDir));
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

