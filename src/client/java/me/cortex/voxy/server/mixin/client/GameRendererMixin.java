package me.cortex.voxy.server.mixin.client;

import me.cortex.voxy.server.client.ClientSyncHandler;
import net.minecraft.client.renderer.GameRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lift vanilla's projection-matrix far plane so entities (and Sable SubLevels,
 * which also render through vanilla's projection) aren't clipped at vanilla's
 * normal cap of {@code renderDistanceBlocks * 4}.
 *
 * The actual user-visible bug: at MC render distance 16 chunks the far plane
 * sits at 16*16*4 = 1024 blocks (= 64 chunks). Anything beyond that gets
 * clipped by the GPU regardless of how we draw it. Voxy's LOD terrain has
 * its own render pass with its own projection so it isn't affected, but
 * everything else (vanilla entities, Sable SubLevels, our LOD entity render)
 * uses the vanilla projection and dies at the cap.
 *
 * We lift the far plane to {@code lodRadius * 32} blocks (lodRadius is in
 * 32-block sections, so this is the LOD-terrain radius in blocks) so the
 * frustum extends out to wherever LOD content can possibly be. Lift only,
 * never lower.
 *
 * The mixin uses {@code require = 0} so on MC versions where
 * {@code getDepthFar()} doesn't exist (e.g. 26.1+, where the projection
 * pipeline was refactored) the mixin no-ops without failing. A separate
 * mixin would be needed there if that target is added.
 *
 * Trade-off: extending far while keeping vanilla's near=0.05 reduces depth
 * precision at long range. For entity rendering the precision loss is
 * imperceptible (sub-meter at multi-km distance with a 24-bit depth buffer).
 * Z-fighting between LOD-terrain and entities at the same distance is
 * theoretically possible but in practice doesn't happen because LOD terrain
 * renders in voxy's separate pass.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	private static final Logger VOXY$LOGGER = LoggerFactory.getLogger("voxy-server-client/projection");
	private static int voxy$lastLoggedRadius = -1;

	@Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true, require = 0)
	private void voxy$extendFarPlane(CallbackInfoReturnable<Float> cir) {
		int radiusSections = ClientSyncHandler.getMaxRadius();
		if (radiusSections <= 0) return;
		float lifted = radiusSections * 32.0f;
		float original = cir.getReturnValueF();
		if (lifted <= original) return;
		cir.setReturnValue(lifted);
		// Log only when the lift value changes so we don't spam the log every
		// frame (getDepthFar is called multiple times per frame).
		if (radiusSections != voxy$lastLoggedRadius) {
			voxy$lastLoggedRadius = radiusSections;
			VOXY$LOGGER.info("[Projection] Lifting far plane {} -> {} (lodRadius={} sections)",
				original, lifted, radiusSections);
		}
	}
}
