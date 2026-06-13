package com.ethan.voxyworldgenv2.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.io.IOException;
import java.util.function.BooleanSupplier;

// MC 1.18.2: readChunk returns CompoundTag directly (not CompletableFuture<Optional<CompoundTag>>) and throws IOException.
@Mixin(ChunkMap.class)
public interface ChunkMapMixin {
    @Invoker("tick")
    void invokeTick(BooleanSupplier booleanSupplier);

    @Invoker("readChunk")
    CompoundTag invokeReadChunk(ChunkPos pos) throws IOException;
}
