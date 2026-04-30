package me.cortex.voxy.server.client;

import com.mojang.blaze3d.platform.InputConstants;
//? if HAS_DEBUG_SCREEN {
import me.cortex.voxy.server.mixin.client.DebugScreenEntriesAccessor;
//?}
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if HAS_RENDER_PIPELINES {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}
//? if HAS_SUBMIT_NODE_COLLECTOR && !HAS_RENDER_PIPELINES {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
*///?}
//? if HAS_NEW_NETWORKING && !HAS_SUBMIT_NODE_COLLECTOR {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
*///?}
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry point. Server-side commands live under {@code /voxysv ...}.
 * Client-side toggles are bound to keys (no client commands), to keep the
 * UX unified and avoid client-command interception of chat input.
 *
 * Keybinds (all under category MISC):
 * - B: toggle the LOD radius border outline (only when MC >= 26.1)
 * - H: toggle per-section LOD update highlights
 */
public class VoxyServerClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientSyncHandler.register();

		//? if HAS_DEBUG_SCREEN {
		// Register voxy bandwidth stats in the F3 debug screen
		DebugScreenEntriesAccessor.invokeRegister(
			Identifier.parse("voxy-server:bandwidth"),
			new VoxyBandwidthDebugEntry()
		);
		//?}

		//? if HAS_NEW_NETWORKING {
		LODEntityRenderer entityRenderer = new LODEntityRenderer();
		//? if HAS_RENDER_PIPELINES {
		// Must use COLLECT_SUBMITS so entity render nodes are submitted before
		// renderSolidFeatures() processes them. AFTER_SOLID_FEATURES is too late --
		// solid render types (entityCutoutNoCull used by most mobs) would never
		// be processed because the solid pass already ran.
		LevelRenderEvents.COLLECT_SUBMITS.register(entityRenderer::render);
		//?} else {
		/*// Pre-26.1: vanilla draws entities during the world-render phase,
		// so we register against AFTER_ENTITIES which fires after vanilla
		// has drawn its entities -- equivalent injection point. Same call
		// site for both 1.21.11 (.world.WorldRenderEvents) and 1.21.1
		// (.v1.WorldRenderEvents); the import gate above selects the right one.
		WorldRenderEvents.AFTER_ENTITIES.register(entityRenderer::render);
		*///?}
		//?}

		//? if HAS_RENDER_PIPELINES {
		VoxyDebugRenderer debugRenderer = new VoxyDebugRenderer();
		LevelRenderEvents.COLLECT_SUBMITS.register(debugRenderer::render);
		//?}

		registerKeybinds();
	}

	private static void registerKeybinds() {
		KeyMapping borderKey = makeKeybind("toggle_border", GLFW.GLFW_KEY_B);
		KeyMapping highlightKey = makeKeybind("toggle_highlights", GLFW.GLFW_KEY_H);
		register(borderKey);
		register(highlightKey);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (borderKey.consumeClick()) {
				VoxyDebugRenderer.toggleBorder();
				notify(client, "[Voxy] LOD border " +
					(VoxyDebugRenderer.isBorderEnabled() ? "shown" : "hidden"));
			}
			while (highlightKey.consumeClick()) {
				LODSectionHighlightTracker.toggle();
				notify(client, "[Voxy] Section highlights " +
					(LODSectionHighlightTracker.isEnabled() ? "enabled" : "disabled"));
			}
		});
	}

	private static KeyMapping makeKeybind(String id, int defaultKey) {
		//? if HAS_IDENTIFIER {
		return new KeyMapping(
			"key.voxy-server." + id,
			InputConstants.Type.KEYSYM,
			defaultKey,
			KeyMapping.Category.MISC
		);
		//?} else {
		/*return new KeyMapping(
			"key.voxy-server." + id,
			InputConstants.Type.KEYSYM,
			defaultKey,
			"category.voxy-server"
		);
		*///?}
	}

	private static void register(KeyMapping key) {
		//? if HAS_RENDER_PIPELINES {
		KeyMappingHelper.registerKeyMapping(key);
		//?} else {
		/*KeyBindingHelper.registerKeyBinding(key);
		*///?}
	}

	private static void notify(net.minecraft.client.Minecraft client, String message) {
		if (client.player == null) return;
		//? if HAS_RENDER_PIPELINES {
		client.player.sendOverlayMessage(Component.literal(message));
		//?} else {
		/*client.player.displayClientMessage(Component.literal(message), true);
		*///?}
	}
}
