package me.cortex.voxy.server.mixin.client.compat.sable;

import me.cortex.voxy.server.client.ClientSyncHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lift the render distance Sable's Sodium-backed SubLevelRenderSectionManager
 * is constructed with so SubLevels (Aeronautica assemblies, etc.) render out
 * to voxy-server's LOD radius. Sable normally uses
 * {@code Minecraft.options.getEffectiveRenderDistance()} (in chunks), which
 * caps SubLevel section graph traversal at the player's MC render distance.
 *
 * No compile-time dependency on Sable: target by FQN string with
 * {@code remap = false}; mixin is silently skipped when Sable isn't loaded.
 *
 * The lifted value comes from the server's announced LOD radius
 * ({@code ClientSyncHandler.getMaxRadius()} in 32-block sections; multiply by
 * 2 for 16-block chunks). Lift only -- never lowers an existing value.
 *
 * Caveats:
 *  - Captured at construction. If the section manager is built before
 *    voxy-server's {@code MerkleSettingsPayload} arrives, the lift is a
 *    no-op for that SubLevel. Sable creates managers lazily per SubLevel,
 *    so handshakes that finish before the first SubLevel appears are fine.
 *  - Sodium's RenderSectionManager may clamp internally; the lift will
 *    silently saturate at that cap.
 */
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.sodium.SubLevelRenderSectionManager", remap = false)
public abstract class SubLevelRenderSectionManagerMixin {

	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
	private static int voxy$liftRenderDistance(int original) {
		int radiusSections = ClientSyncHandler.getMaxRadius();
		if (radiusSections <= 0) return original;
		// sections (32 blocks) -> chunks (16 blocks): * 2.
		return Math.max(original, radiusSections * 2);
	}
}
