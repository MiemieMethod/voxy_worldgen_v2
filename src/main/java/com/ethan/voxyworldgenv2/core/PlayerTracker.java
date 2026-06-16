package com.ethan.voxyworldgenv2.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerTracker {
    private static final PlayerTracker INSTANCE = new PlayerTracker();
    private final Set<ServerPlayer> players;
    private final Map<UUID, PlayerSyncState> syncStates;
    
    private PlayerTracker() {
        this.players = ConcurrentHashMap.newKeySet();
        this.syncStates = new ConcurrentHashMap<>();
    }
    
    public static PlayerTracker getInstance() {
        return INSTANCE;
    }
    
    public void addPlayer(ServerPlayer player, MinecraftServer server) {
        players.add(player);
        PlayerSyncPersistence.LoadedSync loaded = PlayerSyncPersistence.load(server, player.getUUID());
        syncStates.put(player.getUUID(), new PlayerSyncState(loaded.syncedByDimension()));
    }
    
    public void removePlayer(ServerPlayer player, MinecraftServer server) {
        players.remove(player);
        PlayerSyncState state = syncStates.remove(player.getUUID());
        if (state != null) {
            PlayerSyncPersistence.save(server, player.getUUID(), state.snapshot());
        }
    }

    public void saveAll(MinecraftServer server) {
        for (var entry : syncStates.entrySet()) {
            PlayerSyncPersistence.save(server, entry.getKey(), entry.getValue().snapshot());
        }
    }
    
    public void clear() {
        players.clear();
        syncStates.clear();
    }
    
    public Collection<ServerPlayer> getPlayers() {
        return Collections.unmodifiableCollection(players);
    }

    public LongSet getSyncedChunks(UUID uuid, ResourceKey<Level> dimension) {
        PlayerSyncState state = syncStates.get(uuid);
        if (state == null) return null;
        return state.getOrCreate(dimension);
    }

    public void markSynced(UUID uuid, ResourceKey<Level> dimension, long chunkPos) {
        LongSet synced = getSyncedChunks(uuid, dimension);
        if (synced != null) synced.add(chunkPos);
    }

    public void markUnsynced(UUID uuid, ResourceKey<Level> dimension, long chunkPos) {
        LongSet synced = getSyncedChunks(uuid, dimension);
        if (synced != null) synced.remove(chunkPos);
    }
    
    public int getPlayerCount() {
        return players.size();
    }

    private static final class PlayerSyncState {
        private final Map<ResourceKey<Level>, LongSet> syncedByDimension;

        private PlayerSyncState(Map<ResourceKey<Level>, LongSet> syncedByDimension) {
            this.syncedByDimension = new ConcurrentHashMap<>(syncedByDimension);
        }

        LongSet getOrCreate(ResourceKey<Level> dimension) {
            return syncedByDimension.computeIfAbsent(dimension, key -> LongSets.synchronize(new LongOpenHashSet()));
        }

        Map<ResourceKey<Level>, LongSet> snapshot() {
            Map<ResourceKey<Level>, LongSet> copy = new java.util.HashMap<>();
            for (var entry : syncedByDimension.entrySet()) {
                LongSet set = entry.getValue();
                LongSet setCopy = new LongOpenHashSet(set.size());
                synchronized (set) {
                    var iterator = set.iterator();
                    while (iterator.hasNext()) {
                        setCopy.add(iterator.nextLong());
                    }
                }
                copy.put(entry.getKey(), LongSets.synchronize(setCopy));
            }
            return copy;
        }
    }
}
