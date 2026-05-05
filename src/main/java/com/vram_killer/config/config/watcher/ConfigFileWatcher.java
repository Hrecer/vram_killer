package com.vram_killer.config.config.watcher;

import com.vram_killer.VRAMKiller;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

public class ConfigFileWatcher {
    private final Path configPath;
    private final Runnable changeCallback;
    private WatchService watchService;
    private ScheduledExecutorService executor;
    private long lastModified;

    public ConfigFileWatcher(Path configPath, Runnable changeCallback) {
        this.configPath = configPath;
        this.changeCallback = changeCallback;
        this.lastModified = getLastModified();
    }

    public void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path parentDir = configPath.getParent();
            parentDir.register(watchService, ENTRY_MODIFY, ENTRY_CREATE, ENTRY_DELETE);

            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VRAMKiller-Config-Watcher");
                t.setDaemon(true);
                return t;
            });

            executor.scheduleWithFixedDelay(this::checkChanges, 2, 2, TimeUnit.SECONDS);
            VRAMKiller.LOGGER.debug("Started config file watcher for: {}", configPath);
        } catch (IOException e) {
            VRAMKiller.LOGGER.error("Failed to start config file watcher", e);
        }
    }

    public void stop() {
        if (executor != null) {
            executor.shutdown();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                VRAMKiller.LOGGER.error("Error closing watch service", e);
            }
        }
    }

    private void checkChanges() {
        try {
            long currentModified = getLastModified();
            if (currentModified > lastModified) {
                lastModified = currentModified;
                changeCallback.run();
            }
        } catch (Exception e) {
            VRAMKiller.LOGGER.error("Error checking config file changes", e);
        }
    }

    private long getLastModified() {
        try {
            return Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
