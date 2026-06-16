package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.core.PlayerTracker;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side networking. Ported from the MC 1.20.5+ CustomPacketPayload/StreamCodec API to the
 * MC 1.18.2 channel + FriendlyByteBuf API (ServerPlayNetworking.send(player, id, buf)).
 *
 * Wire format (LOD data packet): String dimension, ChunkPos, int minY, then a length-prefixed list
 * of sections; each section = int y, byte[] states, byte[] biomes, nullable byte[] blockLight,
 * nullable byte[] skyLight.
 */
public class NetworkHandler {
    public static final ResourceLocation HANDSHAKE_ID = new ResourceLocation(VoxyWorldGenV2.MOD_ID, "handshake");
    public static final ResourceLocation LOD_DATA_ID = new ResourceLocation(VoxyWorldGenV2.MOD_ID, "lod_data");

    // keep individual packets well under Netty's 2MB limit to prevent connection resets on public servers
    private static final int MAX_PACKET_BYTES = 32_768;

    public record SectionData(int y, byte[] states, byte[] biomes, byte[] blockLight, byte[] skyLight) {
        public void write(FriendlyByteBuf buf) {
            buf.writeInt(y);
            buf.writeByteArray(states);
            buf.writeByteArray(biomes);
            buf.writeBoolean(blockLight != null);
            if (blockLight != null) buf.writeByteArray(blockLight);
            buf.writeBoolean(skyLight != null);
            if (skyLight != null) buf.writeByteArray(skyLight);
        }

        public static SectionData read(FriendlyByteBuf buf) {
            int y = buf.readInt();
            byte[] states = buf.readByteArray();
            byte[] biomes = buf.readByteArray();
            byte[] blockLight = buf.readBoolean() ? buf.readByteArray() : null;
            byte[] skyLight = buf.readBoolean() ? buf.readByteArray() : null;
            return new SectionData(y, states, biomes, blockLight, skyLight);
        }
    }

    public static void init() {
        // Server-side receivers (if any) are registered in ServerEventHandler; payload (de)serialization
        // is handled inline via FriendlyByteBuf, so there is no payload type registry on 1.18.2.
        VoxyWorldGenV2.LOGGER.info("voxy networking initialized");
    }

    private static void setSyncedState(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos, boolean isSynced) {
        if (isSynced) {
            PlayerTracker.getInstance().markSynced(player.getUUID(), dimension, pos.toLong());
        } else {
            PlayerTracker.getInstance().markUnsynced(player.getUUID(), dimension, pos.toLong());
        }
    }

    public static void broadcastLODData(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinBuildHeight() >> 4;
        List<SectionData> sections = buildSections(chunk);

        if (sections.isEmpty()) return;

        double maxDistSq = 4096.0 * 4096.0;

        for (ServerPlayer player : PlayerTracker.getInstance().getPlayers()) {
            double dx = player.getX() - (pos.getMiddleBlockX());
            double dz = player.getZ() - (pos.getMiddleBlockZ());

            if (player.getLevel() != chunk.getLevel() || (dx * dx + dz * dz > maxDistSq)) {
                setSyncedState(player, chunk.getLevel().dimension(), pos, false);
                continue;
            }

            sendSectionsInBatches(player, chunk.getLevel().dimension(), pos, minY, sections);
            setSyncedState(player, chunk.getLevel().dimension(), pos, true);
        }
    }

    public static void sendLODData(ServerPlayer player, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinBuildHeight() >> 4;
        List<SectionData> sections = buildSections(chunk);

        if (sections.isEmpty()) {
            setSyncedState(player, chunk.getLevel().dimension(), pos, true);
            return;
        }

        sendSectionsInBatches(player, chunk.getLevel().dimension(), pos, minY, sections);
        setSyncedState(player, chunk.getLevel().dimension(), pos, true);
    }

    private static List<SectionData> buildSections(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = chunk.getMinBuildHeight() >> 4;
        List<SectionData> sections = new ArrayList<>();
        var lightEngine = chunk.getLevel().getLightEngine();

        for (int i = 0; i < chunk.getSections().length; i++) {
            LevelChunkSection section = chunk.getSections()[i];
            if (section == null || section.hasOnlyAir()) continue;

            io.netty.buffer.ByteBuf statesRaw = Unpooled.buffer();
            io.netty.buffer.ByteBuf biomesRaw = Unpooled.buffer();
            byte[] states, biomes;
            try {
                FriendlyByteBuf statesBuf = new FriendlyByteBuf(statesRaw);
                section.getStates().write(statesBuf);
                states = new byte[statesBuf.readableBytes()];
                statesBuf.readBytes(states);

                FriendlyByteBuf biomesBuf = new FriendlyByteBuf(biomesRaw);
                section.getBiomes().write(biomesBuf);
                biomes = new byte[biomesBuf.readableBytes()];
                biomesBuf.readBytes(biomes);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }

            SectionPos sectionPos = SectionPos.of(pos, minY + i);
            DataLayer bl = lightEngine.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
            DataLayer sl = lightEngine.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);

            sections.add(new SectionData(
                minY + i,
                states,
                biomes,
                bl != null ? bl.getData().clone() : null,
                sl != null ? sl.getData().clone() : null
            ));
        }

        return sections;
    }

    private static FriendlyByteBuf writeLODData(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<SectionData> sections) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(dimension.location().toString());
        buf.writeChunkPos(pos);
        buf.writeInt(minY);
        buf.writeCollection(sections, (b, s) -> s.write(b));
        return buf;
    }

    private static void sendSectionsInBatches(ServerPlayer player, ResourceKey<Level> dimension, ChunkPos pos, int minY, List<SectionData> sections) {
        List<SectionData> batch = new ArrayList<>();
        int batchBytes = 0;

        for (SectionData sd : sections) {
            int sectionBytes = sd.states().length + sd.biomes().length
                + (sd.blockLight() != null ? sd.blockLight().length : 0)
                + (sd.skyLight() != null ? sd.skyLight().length : 0);

            if (!batch.isEmpty() && batchBytes + sectionBytes > MAX_PACKET_BYTES) {
                ServerPlayNetworking.send(player, LOD_DATA_ID, writeLODData(dimension, pos, minY, batch));
                batch = new ArrayList<>();
                batchBytes = 0;
            }

            batch.add(sd);
            batchBytes += sectionBytes;
        }

        if (!batch.isEmpty()) {
            ServerPlayNetworking.send(player, LOD_DATA_ID, writeLODData(dimension, pos, minY, batch));
        }
    }

    public static void sendHandshake(ServerPlayer player) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(true);
        ServerPlayNetworking.send(player, HANDSHAKE_ID, buf);
    }
}
