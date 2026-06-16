package com.ethan.voxyworldgenv2.core;

import com.ethan.voxyworldgenv2.VoxyWorldGenV2;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class RegionChunkScanner {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final int REGION_HEADER_BYTES = 4096;

    private RegionChunkScanner() {}

    static int addExistingChunks(ServerLevel level, LongSet target) {
        Path regionPath = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        if (!Files.isDirectory(regionPath)) return 0;

        int added = 0;
        try (Stream<Path> files = Files.list(regionPath)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                added += scanRegionFile(file, target);
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.error("failed to scan existing region chunks for " + level.dimension(), e);
        }
        return added;
    }

    private static int scanRegionFile(Path file, LongSet target) {
        Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
        if (!matcher.matches()) return 0;

        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(matcher.group(1));
            regionZ = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException ignored) {
            return 0;
        }

        byte[] header = new byte[REGION_HEADER_BYTES];
        int read = 0;
        try (InputStream in = Files.newInputStream(file)) {
            while (read < REGION_HEADER_BYTES) {
                int r = in.read(header, read, REGION_HEADER_BYTES - read);
                if (r < 0) break;
                read += r;
            }
        } catch (Exception e) {
            VoxyWorldGenV2.LOGGER.warn("failed to read region header {}", file, e);
            return 0;
        }

        int added = 0;
        for (int i = 0; i < 1024; i++) {
            int base = i * 4;
            int offset = ((header[base] & 0xFF) << 16) | ((header[base + 1] & 0xFF) << 8) | (header[base + 2] & 0xFF);
            int sectors = header[base + 3] & 0xFF;
            if (offset == 0 || sectors == 0) continue;

            int localX = i & 31;
            int localZ = i >> 5;
            int chunkX = (regionX << 5) + localX;
            int chunkZ = (regionZ << 5) + localZ;
            if (target.add(ChunkPos.asLong(chunkX, chunkZ))) {
                added++;
            }
        }
        return added;
    }
}
