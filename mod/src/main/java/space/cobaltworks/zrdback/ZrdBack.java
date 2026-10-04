/*
 * ZRDBack — Zstd Reverse Delta Backup
 * Copyright (C) 2026 CobaltDev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * The ZVCR-3D format and the reference implementation (zvcr, zvcr_utils)
 * by crane are licensed under the GNU Lesser General Public License v3.
 */
package space.cobaltworks.zrdback;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.cobaltworks.zrdback.backup.BackupService;
import space.cobaltworks.zrdback.command.BackupCommands;

/**
 * Server entrypoint for the ZVCR backup mod.
 *
 * <p>The mod is a read-only observer of the live world save: it never writes
 * to the world folder. Backups are stored in the ZVCR-3D format via the
 * {@code zvcr-java} library, byte-compatible with the C++ reference tools.
 */
public final class ZrdBack implements DedicatedServerModInitializer {

    public static final String MOD_ID = "zrdback";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final AtomicReference<BackupService> SERVICE = new AtomicReference<>();
    private static volatile ScheduledExecutorService scheduler;

    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(BackupCommands.build(SERVICE::get)));
        ServerLifecycleEvents.SERVER_STARTED.register(ZrdBack::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ZrdBack::onServerStopping);
        LOGGER.info("ZVCR Backup initialized (read-only world observer, ZVCR-3D writer)");
    }

    private static void onServerStarted(MinecraftServer server) {
        try {
            Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            BackupConfig config = BackupConfig.load(worldRoot);
            BackupService service = new BackupService(config, server);
            SERVICE.set(service);

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "zrdback");
                thread.setDaemon(true);
                return thread;
            });
            // First pass after one full interval (an immediate pass would race
            // other startup activity; /zrdback now covers on-demand needs).
            scheduler.scheduleWithFixedDelay(() -> runGuarded(service),
                    config.intervalMinutes(), config.intervalMinutes(), TimeUnit.MINUTES);

            LOGGER.info("ZVCR Backup active: output={}, interval={} min, checkpoint-interval={}",
                    config.outputDirectory(), config.intervalMinutes(), config.checkpointInterval());
        } catch (Exception e) {
            LOGGER.error("Failed to start ZVCR Backup", e);
        }
    }

    private static void runGuarded(BackupService service) {
        try {
            BackupService.Result result = service.runBackup();
            if (result.chunksChanged() > 0) {
                LOGGER.info("Scheduled backup: {} regions scanned, {} chunks changed, {} inserted ({} ms)",
                        result.regionsScanned(), result.chunksChanged(), result.chunksInserted(),
                        result.durationMs());
            } else {
                LOGGER.info("Scheduled backup: nothing changed ({} regions scanned, {} ms)",
                        result.regionsScanned(), result.durationMs());
            }
        } catch (Throwable e) {
            // Throwable: a scheduled task that throws is silently cancelled by
            // the executor — log Errors too.
            LOGGER.error("Scheduled backup failed", e);
        }
    }

    private static void onServerStopping(MinecraftServer server) {
        ScheduledExecutorService current = scheduler;
        if (current != null) {
            current.shutdown();
            try {
                if (!current.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warn("Backup task did not finish within 10s of server stop");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        SERVICE.set(null);
        LOGGER.info("ZVCR Backup stopped");
    }
}
