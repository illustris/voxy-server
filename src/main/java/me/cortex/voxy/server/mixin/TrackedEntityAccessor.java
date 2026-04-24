package me.cortex.voxy.server.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerPlayerConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface TrackedEntityAccessor {
	@Accessor("serverEntity")
	ServerEntity voxy$getServerEntity();

	@Accessor("seenBy")
	Set<ServerPlayerConnection> voxy$getSeenBy();
}
