package com.ethan.voxyworldgenv2.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.CompletableFuture;

// MC 1.18.2: getChunkFutureMainThread returns CompletableFuture<Either<ChunkAccess, ChunkLoadingFailure>>
// (ChunkResult was introduced in 1.20.2). ChunkStatus is in net.minecraft.world.level.chunk (no .status subpkg).
@Mixin(ServerChunkCache.class)
public interface ServerChunkCacheMixin {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> invokeGetChunkFutureMainThread(int x, int z, ChunkStatus status, boolean create);

    @Invoker
    boolean invokeRunDistanceManagerUpdates();
}
