package com.udpsendtofailed.modmenu.updater;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import com.terraformersmc.modmenu.ModMenu;
import com.udpsendtofailed.modmenu.updater.api.ModExtension;
import com.udpsendtofailed.modmenu.updater.api.UpdateInfoExtension;
import com.terraformersmc.modmenu.util.mod.Mod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ModUpdaterService {
    private final Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int count = 0;
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ModMenuUpdater-" + count++);
            t.setDaemon(true);
            return t;
        }
    });

    private static final AtomicInteger BATCH_TOTAL = new AtomicInteger(0);
    private static final AtomicInteger BATCH_COMPLETED = new AtomicInteger(0);
    private static final AtomicInteger BATCH_FAILED = new AtomicInteger(0);
    private static final AtomicBoolean BATCH_ACTIVE = new AtomicBoolean(false);

    public static boolean isBatchActive() {
        return BATCH_ACTIVE.get();
    }

    public static int getBatchTotal() {
        return BATCH_TOTAL.get();
    }

    public static int getBatchCompleted() {
        return BATCH_COMPLETED.get();
    }

    public void performUpdateAll() {
        if (BATCH_ACTIVE.get()) {
            return;
        }

        List<Mod> toUpdate = ModMenu.MODS.values().stream()
                .filter(mod -> {
                    if (mod.getUpdateInfo() instanceof UpdateInfoExtension info && info.getDownloadUrl() != null) {
                        return mod.hasUpdate() && !((ModExtension) mod).isDownloadingUpdate()
                                && !((ModExtension) mod).isUpdateDownloaded();
                    }
                    return false;
                })
                .toList();

        if (toUpdate.isEmpty())
            return;

        BATCH_TOTAL.set(toUpdate.size());
        BATCH_COMPLETED.set(0);
        BATCH_FAILED.set(0);
        BATCH_ACTIVE.set(true);

        toastSuccess(
                Component.translatable("modmenu.update.toast.all.started.title"),
                Component.translatable("modmenu.update.toast.all.started.description", toUpdate.size()));

        for (Mod mod : toUpdate) {
            performUpdate(mod, true);
        }
    }

    public void performUpdate(Mod mod) {
        performUpdate(mod, false);
    }

    private void performUpdate(Mod mod, boolean partOfBatch) {
        if (!(mod.getUpdateInfo() instanceof UpdateInfoExtension info) || info.getDownloadUrl() == null) {
            if (partOfBatch) completeBatchItem(false);
            return;
        }

        ModExtension ext = (ModExtension) mod;
        if (ext.isDownloadingUpdate() || ext.isUpdateDownloaded()) {
            if (partOfBatch) completeBatchItem(false);
            return;
        }

        Optional<Path> oldFile = findModJar(mod);
        if (oldFile.isEmpty()) {
            ModMenuUpdater.LOGGER.warn("Could not find JAR for mod '{}'. Skipping.", mod.getId());
            if (partOfBatch) completeBatchItem(true);
            return;
        }

        ext.setDownloadingUpdate(true);

        CompletableFuture.runAsync(() -> {
            Path tempFile = null;
            boolean failed = false;
            try {
                tempFile = downloadFile(info.getDownloadUrl());

                if (info.getFileHash() != null) {
                    String downloadedHash = Files.asByteSource(tempFile.toFile()).hash(Hashing.sha512()).toString();
                    if (!info.getFileHash().equalsIgnoreCase(downloadedHash)) {
                        throw new IOException("File hash mismatch");
                    }
                }

                String rawName = info.getFileName();
                if (rawName == null || rawName.isBlank()) {
                    throw new IOException("Missing target filename");
                }
                String safeName = Path.of(rawName).getFileName().toString();
                Path oldPath = oldFile.get();
                Path newFilePath = modsDir.resolve(safeName);
                boolean samePath = newFilePath.toAbsolutePath().normalize().equals(oldPath.toAbsolutePath().normalize());

                boolean oldBackedUp = false;
                if (samePath) {
                    oldBackedUp = BackupManager.performBackup(oldPath);
                    if (!oldBackedUp) {
                        // Old JAR is locked (e.g. Windows); write to a distinct filename so Fabric loads it on restart
                        String stem = safeName.endsWith(".jar") ? safeName.substring(0, safeName.length() - 4) : safeName;
                        newFilePath = modsDir.resolve(stem + "-updated.jar");
                    }
                }

                java.nio.file.Files.move(tempFile, newFilePath, StandardCopyOption.REPLACE_EXISTING);
                tempFile = null;

                if (!oldBackedUp && !BackupManager.performBackup(oldPath)) {
                    CleanupManager.scheduleForCleanup(oldPath);
                }

                if (!partOfBatch) {
                    toastSuccess(
                            Component.translatable("modmenu.update.toast.single.success.title"),
                            Component.translatable("modmenu.update.toast.single.success.description", mod.getName()));
                }

                ext.setUpdateDownloaded(true);

                String parentId = mod.getParent();
                if (parentId != null) {
                    Mod parentMod = ModMenu.MODS.get(parentId);
                    if (parentMod instanceof ModExtension parentExt) {
                        parentExt.resetChildHasUpdate();
                    }
                }
            } catch (Exception e) {
                failed = true;
                ModMenuUpdater.LOGGER.error("Update failed for " + mod.getName(), e);
                if (tempFile != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {}
                }
                String message = (e.getMessage() != null && !e.getMessage().isBlank())
                        ? e.getMessage()
                        : e.getClass().getSimpleName();
                toastError(
                        Component.translatable("modmenu.update.toast.error.title"),
                        Component.literal(mod.getName() + ": " + message));
            } finally {
                ext.setDownloadingUpdate(false);
                if (partOfBatch) {
                    completeBatchItem(failed);
                }
            }
        }, EXECUTOR);
    }

    private void completeBatchItem(boolean failed) {
        if (failed) {
            BATCH_FAILED.incrementAndGet();
        }
        int done = BATCH_COMPLETED.incrementAndGet();
        int total = BATCH_TOTAL.get();
        if (done >= total && BATCH_ACTIVE.compareAndSet(true, false)) {
            int failures = BATCH_FAILED.get();
            int succeeded = Math.max(0, total - failures);
            if (failures > 0) {
                toastError(
                        Component.translatable("modmenu.update.toast.all.finished.title"),
                        Component.translatable("modmenu.update.toast.all.finishedWithErrors.description", succeeded, total, failures));
            } else {
                toastSuccess(
                        Component.translatable("modmenu.update.toast.all.finished.title"),
                        Component.translatable("modmenu.update.toast.all.finished.description", succeeded, total));
            }
        }
    }

    private Path downloadFile(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build();
        Path tempFile = java.nio.file.Files.createTempFile("modmenu-updater", ".jar.tmp");
        HttpResponse<InputStream> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200)
            throw new IOException("HTTP " + response.statusCode());
        try (InputStream is = response.body()) {
            java.nio.file.Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    private Optional<Path> findModJar(Mod mod) {
        if (mod instanceof com.terraformersmc.modmenu.util.mod.fabric.FabricMod fabricMod) {
            var container = fabricMod.getContainer();
            if (container.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH) {
                return container.getOrigin().getPaths().stream()
                        .filter(p -> p.toString().toLowerCase().endsWith(".jar") && java.nio.file.Files.exists(p))
                        .findFirst();
            }
        }
        return Optional.empty();
    }

    private void toastSuccess(Component title, Component description) {
        Minecraft.getInstance().execute(() -> SystemToast.add(Minecraft.getInstance().gui.toastManager(),
                SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, description));
    }

    private void toastError(Component title, Component description) {
        Minecraft.getInstance().execute(() -> SystemToast.add(Minecraft.getInstance().gui.toastManager(),
                SystemToast.SystemToastId.PACK_COPY_FAILURE, title, description));
    }
}