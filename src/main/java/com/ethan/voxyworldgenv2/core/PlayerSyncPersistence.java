package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class PlayerSyncPersistence {
    private static final int VERSION = 1;
    private static final String DIR_NAME = "voxy_player_sync";

    private PlayerSyncPersistence() {}

    record LoadedSync(Map<ResourceKey<Level>, LongSet> syncedByDimension) {}

    static LoadedSync load(MinecraftServer server, UUID uuid) {
        Map<ResourceKey<Level>, LongSet> syncedByDimension = new HashMap<>();
        Path path = getPath(server, uuid);
        if (!Files.exists(path)) {
            return new LoadedSync(syncedByDimension);
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int version = in.readInt();
            if (version != VERSION) {
                VoxyWorldGenV2.LOGGER.warn("ignoring unsupported player sync cache version {} for {}", version, uuid);
                return new LoadedSync(syncedByDimension);
            }

            int dimensionCount = in.readInt();
            for (int i = 0; i < dimensionCount; i++) {
                ResourceLocation id = ResourceLocation.tryParse(in.readUTF());
                int chunkCount = in.readInt();
                if (id == null) {
                    for (int j = 0; j < chunkCount; j++) in.readLong();
                    continue;
                }

                LongSet chunks = LongSets.synchronize(new LongOpenHashSet(chunkCount));
                for (int j = 0; j < chunkCount; j++) {
                    chunks.add(in.readLong());
                }
                syncedByDimension.put(ResourceKey.create(Registry.DIMENSION_REGISTRY, id), chunks);
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to load player sync cache for " + uuid, e);
        }

        return new LoadedSync(syncedByDimension);
    }

    static void save(MinecraftServer server, UUID uuid, Map<ResourceKey<Level>, LongSet> syncedByDimension) {
        try {
            Path path = getPath(server, uuid);
            Files.createDirectories(path.getParent());

            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                out.writeInt(VERSION);
                out.writeInt(syncedByDimension.size());
                for (var entry : syncedByDimension.entrySet()) {
                    out.writeUTF(entry.getKey().location().toString());
                    LongSet chunks = entry.getValue();
                    synchronized (chunks) {
                        out.writeInt(chunks.size());
                        var iterator = chunks.iterator();
                        while (iterator.hasNext()) {
                            out.writeLong(iterator.nextLong());
                        }
                    }
                }
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to save player sync cache for " + uuid, e);
        }
    }

    private static Path getPath(MinecraftServer server, UUID uuid) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIR_NAME).resolve(uuid + ".bin");
    }
}
