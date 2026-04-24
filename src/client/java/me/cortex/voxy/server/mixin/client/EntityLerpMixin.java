package me.cortex.voxy.server.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes position updates for entities in unloaded client chunks (native LOD entities).
 *
 * Problem: vanilla's movement packet handler calls entity.lerpTo() which sets a
 * lerp TARGET, but the actual position (getX/Y/Z) only advances toward the target
 * during entity.tick(). Entities in unloaded chunks are never ticked by
 * ClientLevel.tickEntities(), so their positions go stale.
 *
 * Fix: when lerpTo is called for an entity in an unloaded chunk, set the position
 * directly instead of deferring to the (never-called) tick interpolation.
 */
@Mixin(Entity.class)
public abstract class EntityLerpMixin {

	@Inject(method = "lerpTo", at = @At("HEAD"), cancellable = true)
	private void voxyDirectPositionUpdate(double x, double y, double z,
										   float yRot, float xRot,
										   int lerpSteps, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (!(self.level() instanceof ClientLevel clientLevel)) return;

		// Only intervene for entities whose chunk is not loaded on the client --
		// these are native-transport LOD entities that vanilla won't tick.
		if (clientLevel.getChunkSource().hasChunk(self.getBlockX() >> 4, self.getBlockZ() >> 4)) return;

		self.setPos(x, y, z);
		self.setYRot(yRot);
		self.yRotO = yRot;
		self.setXRot(xRot);
		self.xRotO = xRot;
		self.xOld = x;
		self.yOld = y;
		self.zOld = z;
		ci.cancel();
	}
}
