package me.cortex.voxy.server.mixin;

import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.streaming.EntitySyncService;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Extends vanilla entity tracking to include far entities within the LOD radius
 * when native entity transport mode is enabled. Intercepts the tracking decision
 * in ChunkMap$TrackedEntity.updatePlayer to force-track entities that would
 * otherwise be rejected by vanilla's distance and chunk visibility checks.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ChunkMapTrackedEntityMixin {

	@Shadow @Final private ServerEntity serverEntity;
	@Shadow @Final private Entity entity;
	@Shadow @Final private Set<ServerPlayerConnection> seenBy;

	@Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
	private void voxyForceEntityTracking(ServerPlayer player, CallbackInfo ci) {
		// Let vanilla handle self-tracking
		if (player == (Object) this.entity) return;

		EntitySyncService service = VoxyServerMod.getEntitySyncService();
		if (service == null) return;
		if (!service.shouldForceTrack(player.getUUID(), this.entity.getId())) return;

		// Entity is in the force-track set: ensure it's paired with this player.
		// seenBy.add returns false if already present, guarding addPairing from
		// sending duplicate spawn packets.
		if (this.seenBy.add(player.connection)) {
			this.serverEntity.addPairing(player);
		}
		// Cancel vanilla's logic which would remove the entity since isChunkTracked
		// fails for far chunks outside the player's view distance.
		ci.cancel();
	}
}
