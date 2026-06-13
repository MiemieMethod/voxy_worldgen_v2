package com.ethan.voxyworldgenv2.network;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import com.ethan.voxyworldgenv2.integration.VoxyIntegration;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side networking. Ported from the MC 1.20.5+ CustomPacketPayload API to the MC 1.18.2 channel
 * + FriendlyByteBuf API. Receivers are registered by channel id; the payload is decoded inline.
 */
public class NetworkClientHandler {

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.HANDSHAKE_ID, (client, handler, buf, responseSender) -> {
            boolean serverHasMod = buf.readBoolean();
            client.execute(() -> NetworkState.setServerConnected(serverHasMod));
        });

        ClientPlayNetworking.registerGlobalReceiver(NetworkHandler.LOD_DATA_ID, (client, handler, buf, responseSender) -> {
            // Decode on the network thread (buf is only valid here), then ingest on the client thread.
            String dimStr = buf.readUtf();
            ChunkPos pos = buf.readChunkPos();
            int minY = buf.readInt();
            List<NetworkHandler.SectionData> sections = buf.readCollection(ArrayList::new, NetworkHandler.SectionData::read);
            ResourceKey<Level> dimension = ResourceKey.create(Registry.DIMENSION_REGISTRY, ResourceLocation.tryParse(dimStr));
            client.execute(() -> handleLODData(dimension, pos, minY, sections));
        });
    }

    private static void handleLODData(ResourceKey<Level> dimension, ChunkPos pos, int minY, List<NetworkHandler.SectionData> sections) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        // discard LOD data from a different dimension to prevent cross-dimension rendering artifacts (issue #43)
        if (!level.dimension().equals(dimension)) return;

        // calculate approximate payload size
        long bytes = 0;
        for (NetworkHandler.SectionData sd : sections) {
            bytes += sd.states().length;
            bytes += sd.biomes().length;
            if (sd.blockLight() != null) bytes += sd.blockLight().length;
            if (sd.skyLight() != null) bytes += sd.skyLight().length;
        }
        NetworkState.incrementReceived(bytes);

        Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);

        for (NetworkHandler.SectionData sectionData : sections) {
            io.netty.buffer.ByteBuf statesRaw = Unpooled.wrappedBuffer(sectionData.states());
            io.netty.buffer.ByteBuf biomesRaw = Unpooled.wrappedBuffer(sectionData.biomes());
            try {
                // recreate the section (MC 1.18.2 ctor takes the section Y and biome registry)
                LevelChunkSection section = new LevelChunkSection(sectionData.y(), biomeRegistry);

                section.getStates().read(new FriendlyByteBuf(statesRaw));
                section.getBiomes().read(new FriendlyByteBuf(biomesRaw));

                DataLayer bl = sectionData.blockLight() != null ? new DataLayer(sectionData.blockLight()) : null;
                DataLayer sl = sectionData.skyLight() != null ? new DataLayer(sectionData.skyLight()) : null;

                VoxyIntegration.rawIngest(level, section, pos.x, sectionData.y(), pos.z, bl, sl);

            } catch (Exception e) {
                VoxyWorldGenV2.LOGGER.error("failed to handle LOD data for chunk " + pos, e);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }
    }
}
