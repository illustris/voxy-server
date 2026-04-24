package me.cortex.voxy.server.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Fixes position updates for entities in unloaded client chunks (native LOD entities).
 *
 * Problem: vanilla's moveOrInterpolateTo delegates to InterpolationHandler.interpolateTo()
 * which stores a lerp TARGET -- the actual position only advances when the entity's
 * interpolate() is called during tick(). Entities in unloaded chunks are never ticked
 * by ClientLevel, so their positions go stale.
 *
 * Fix: when moveOrInterpolateTo is called for an entity in an unloaded chunk, set the
 * position directly instead of deferring to interpolation.
 */
@Mixin(Entity.class)
public abstract class EntityLerpMixin {

	@Inject(method = "moveOrInterpolateTo(Ljava/util/Optional;Ljava/util/Optional;Ljava/util/Optional;)V",
			at = @At("HEAD"), cancellable = true)
	private void voxyDirectPositionUpdate(Optional<Vec3> pos, Optional<Float> yRot,
										   Optional<Float> xRot, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (!(self.level() instanceof ClientLevel clientLevel)) return;

		// Only intervene for entities whose chunk is not loaded on the client --
		// these are native-transport LOD entities that vanilla won't tick.
		if (clientLevel.getChunkSource().hasChunk(self.getBlockX() >> 4, self.getBlockZ() >> 4)) return;

		// Skip interpolation handler; set position and rotation directly
		pos.ifPresent(p -> {
			self.setPos(p);
			self.xOld = p.x;
			self.yOld = p.y;
			self.zOld = p.z;
		});
		yRot.ifPresent(y -> {
			self.setYRot(y);
			self.yRotO = y;
		});
		xRot.ifPresent(x -> {
			self.setXRot(x);
			self.xRotO = x;
		});
		ci.cancel();
	}
}
