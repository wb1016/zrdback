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
package space.cobaltworks.zrdback.command;

import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

import space.cobaltworks.zrdback.backup.BackupService;

/**
 * {@code /backup now | prune <days> | status} — moderator level required.
 * Backup runs execute on the caller's executor (async); commands only enqueue.
 */
public final class BackupCommands {

    private BackupCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build(
            java.util.function.Supplier<BackupService> serviceSupplier) {
        return Commands.literal("zrdback")
                .requires(Commands.hasPermission(Commands.LEVEL_MODERATORS))
                .then(Commands.literal("debug-section")
                        .then(Commands.argument("localX", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 31))
                                .then(Commands.argument("localZ", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 31))
                                        .then(Commands.argument("sectionY", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-8, 24))
                                                .executes(context -> {
                                                    debugSection(context.getSource(),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "localX"),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "localZ"),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "sectionY"));
                                                    return 1;
                                                })))))
                .then(Commands.literal("now").executes(context -> {
                    BackupService service = requireService(context.getSource(), serviceSupplier);
                    if (service == null) {
                        return 0;
                    }
                    enqueue(context.getSource(), "Backup started", () -> {
                        try {
                            BackupService.Result result = service.runBackup();
                            String message = "Backup finished: " + result.regionsScanned() + " regions scanned, "
                                    + result.chunksChanged() + " chunks changed, "
                                    + result.chunksInserted() + " inserted, "
                                    + result.blobsStored() + " files stored in "
                                    + result.durationMs() + " ms";
                            if (result.entriesPruned() > 0) {
                                message += "; retention pruned " + result.entriesPruned() + " entries";
                            }
                            return message;
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return 1;
                }))
                .then(Commands.literal("prune")
                        .then(Commands.argument("days", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(context -> {
                                    BackupService service = requireService(context.getSource(), serviceSupplier);
                                    if (service == null) {
                                        return 0;
                                    }
                                    int days = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "days");
                                    enqueue(context.getSource(), "Prune started", () -> {
                                        try {
                                            BackupService.Result result = service.prune(days);
                                            return "Prune finished: " + result.regionsScanned() + " files scanned, "
                                                    + result.chunksChanged() + " entries removed in "
                                                    + result.durationMs() + " ms";
                                        } catch (java.io.IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                                    return 1;
                                })))
                .then(Commands.literal("restore")
                        .then(Commands.literal("latest").executes(context -> {
                            BackupService service = requireService(context.getSource(), serviceSupplier);
                            if (service == null) {
                                return 0;
                            }
                            long now = java.time.Instant.now().getEpochSecond();
                            enqueue(context.getSource(), "Restore started", () -> {
                                try {
                                    var result = service.restore(now);
                                    return "Restore finished: " + result.chunksRestored() + " chunks, "
                                            + result.filesRestored() + " files -> " + result.targetDir()
                                            + " (" + result.durationMs() + " ms)";
                                } catch (java.io.IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                            return 1;
                        }))
                        .then(Commands.argument("timestamp", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                .executes(context -> {
                                    BackupService service = requireService(context.getSource(), serviceSupplier);
                                    if (service == null) {
                                        return 0;
                                    }
                                    long ts = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "timestamp");
                                    enqueue(context.getSource(), "Restore started", () -> {
                                        try {
                                            var result = service.restore(ts);
                                            return "Restore finished: " + result.chunksRestored() + " chunks, "
                                                    + result.filesRestored() + " files -> " + result.targetDir()
                                                    + " (" + result.durationMs() + " ms)";
                                        } catch (java.io.IOException e) {
                                            throw new RuntimeException(e);
                                        }
                                    });
                                    return 1;
                                })))
                .then(Commands.literal("stats").executes(context -> {
                    BackupService service = requireService(context.getSource(), serviceSupplier);
                    if (service == null) {
                        return 0;
                    }
                    enqueue(context.getSource(), "Stats started", () -> {
                        try {
                            BackupService.Stats s = service.stats();
                            return ("Stats: files=%d (%d KiB), segments=%d, chains=%d "
                                    + "(entries %d, avg %.1f, min %d, max %d), teDeltas=%d, "
                                    + "palettes %d distinct / %d refs (%.1f%% dedup), "
                                    + "blobs: %d tracked, %d blobs (%d KiB)").formatted(
                                    s.files(), s.fileBytes() / 1024, s.segments(), s.chains(),
                                    s.chainEntries(), s.avgChain(), s.minChain(), s.maxChain(),
                                    s.teDeltas(), s.distinctPalettes(), s.paletteRefs(),
                                    100.0 * (1.0 - s.paletteDedupRatio()),
                                    s.trackedFiles(), s.blobCount(), s.blobBytes() / 1024);
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    BackupService service = requireService(context.getSource(), serviceSupplier);
                    if (service == null) {
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal(
                            service.statusLine()), false);
                    return 1;
                }));
    }

    private static BackupService requireService(CommandSourceStack source,
                                                java.util.function.Supplier<BackupService> supplier) {
        BackupService service = supplier.get();
        if (service == null) {
            source.sendFailure(Component.literal("ZVCR Backup is not running"));
        }
        return service;
    }

    private static void debugSection(CommandSourceStack source, int localX, int localZ, int sectionY) {
        try {
            MinecraftServer server = source.getServer();
            Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize();
            Path regionDir = net.minecraft.world.level.dimension.DimensionType
                    .getStorageFolder(net.minecraft.world.level.Level.OVERWORLD, worldRoot).resolve("region");
            Path mca = regionDir.resolve("r.0.0.mca");
            try (space.cobaltworks.zrdback.backup.ChunkReader reader =
                         space.cobaltworks.zrdback.backup.ChunkReader.open(mca, "debug", net.minecraft.world.level.Level.OVERWORLD)) {
                net.minecraft.nbt.CompoundTag chunk = reader.readChunk(localX, localZ);
                if (chunk == null) {
                    source.sendSystemMessage(Component.literal("chunk absent"));
                    return;
                }
                net.minecraft.nbt.ListTag sections = chunk.getListOrEmpty("sections");
                for (int i = 0; i < sections.size(); i++) {
                    net.minecraft.nbt.CompoundTag section = sections.getCompoundOrEmpty(i);
                    if (section.getByteOr("Y", (byte) 99) != sectionY) continue;
                    net.minecraft.nbt.CompoundTag bs = section.getCompoundOrEmpty("block_states");
                    net.minecraft.nbt.ListTag palette = bs.getListOrEmpty("palette");
                    long[] data = bs.getLongArray("data").orElse(new long[0]);
                    StringBuilder names = new StringBuilder();
                    for (int pIdx = 0; pIdx < Math.min(palette.size(), 4); pIdx++) {
                        net.minecraft.nbt.Tag entry = palette.get(pIdx);
                        names.append("[").append(entry.getId()).append("] ")
                                .append(entry.toString()).append(" | ");
                    }
                    long nonZeroLongs = java.util.Arrays.stream(data).filter(v -> v != 0).count();
                    source.sendSystemMessage(Component.literal(
                            "section Y=" + sectionY + " paletteSize=" + palette.size()
                            + " dataLongs=" + data.length + " nonZeroLongs=" + nonZeroLongs
                            + " data[0..3]=" + java.util.Arrays.toString(java.util.Arrays.copyOfRange(data, 0, Math.min(4, data.length)))
                            + " palette=[" + names + "]"));
                    return;
                }
                source.sendSystemMessage(Component.literal("section Y=" + sectionY + " not found (" + sections.size() + " sections)"));
            }
        } catch (Exception e) {
            source.sendSystemMessage(Component.literal("debug failed: " + e));
        }
    }

    private static void enqueue(CommandSourceStack source, String startedMessage,
                                java.util.function.Supplier<String> task) {
        CompletableFuture.runAsync(() -> {
            String result;
            try {
                result = task.get();
            } catch (Throwable e) {
                source.sendSystemMessage(Component.literal("ZVCR Backup failed: " + e));
                space.cobaltworks.zrdback.ZrdBack.LOGGER.error("backup task failed", e);
                return;
            }
            source.sendSystemMessage(Component.literal(result));
            space.cobaltworks.zrdback.ZrdBack.LOGGER.info("{}: {}", startedMessage, result);
        });
        source.sendSuccess(() -> Component.literal(startedMessage), false);
    }
}
