package me.cortex.voxy.server.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
//? if HAS_DEBUG_SCREEN {
import me.cortex.voxy.server.mixin.client.DebugScreenEntriesAccessor;
//?}
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
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
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class VoxyServerClient implements ClientModInitializer {

	private static VoxyServerClientConfig clientConfig;

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

		clientConfig = VoxyServerClientConfig.load();

		//? if HAS_SUBMIT_NODE_COLLECTOR {
		LODEntityRenderer entityRenderer = new LODEntityRenderer(
			ClientSyncHandler.getLODEntityManager(), clientConfig
		);
		//? if HAS_RENDER_PIPELINES {
		// Must use COLLECT_SUBMITS so entity render nodes are submitted before
		// renderSolidFeatures() processes them. AFTER_SOLID_FEATURES is too late --
		// solid render types (entityCutoutNoCull used by most mobs) would never
		// be processed because the solid pass already ran.
		LevelRenderEvents.COLLECT_SUBMITS.register(entityRenderer::render);
		//?} else {
		/*// Pre-26.1: vanilla draws entities during the world-render phase,
		// so we register against AFTER_ENTITIES which fires after vanilla
		// has drawn its entities -- equivalent injection point.
		WorldRenderEvents.AFTER_ENTITIES.register(entityRenderer::render);
		*///?}
		//?}

		//? if HAS_RENDER_PIPELINES {
		VoxyDebugRenderer debugRenderer = new VoxyDebugRenderer();
		LevelRenderEvents.COLLECT_SUBMITS.register(debugRenderer::render);
		//?}

		// Keybind to toggle LOD border visualization
		//? if HAS_IDENTIFIER {
		KeyMapping borderKey = new KeyMapping(
			"key.voxy-server.toggle_border",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_B,
			KeyMapping.Category.MISC
		);
		//?} else {
		/*KeyMapping borderKey = new KeyMapping(
			"key.voxy-server.toggle_border",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_B,
			"category.voxy-server"
		);
		*///?}
		//? if HAS_RENDER_PIPELINES {
		KeyMappingHelper.registerKeyMapping(borderKey);
		//?} else {
		/*KeyBindingHelper.registerKeyBinding(borderKey);
		*///?}

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (borderKey.consumeClick()) {
				VoxyDebugRenderer.toggleBorder();
				if (client.player != null) {
					//? if HAS_RENDER_PIPELINES {
					client.player.sendOverlayMessage(
						Component.literal("[Voxy] LOD border " +
							(VoxyDebugRenderer.isBorderEnabled() ? "shown" : "hidden"))
					);
					//?} else {
					/*client.player.displayClientMessage(
						Component.literal("[Voxy] LOD border " +
							(VoxyDebugRenderer.isBorderEnabled() ? "shown" : "hidden")),
						true
					);
					*///?}
				}
			}
		});

		registerCommands();
	}

	private void registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(literal("voxyentity")
				.then(literal("mode")
					.then(argument("mode", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							builder.suggest("billboard");
							builder.suggest("model");
							return builder.buildFuture();
						})
						.executes(ctx -> {
							String mode = StringArgumentType.getString(ctx, "mode");
							if (!"billboard".equals(mode) && !"model".equals(mode)) {
								ctx.getSource().sendError(Component.literal("Invalid mode: " + mode + ". Use 'billboard' or 'model'."));
								return 0;
							}
							clientConfig.entityRenderMode = mode;
							clientConfig.save();
							ctx.getSource().sendFeedback(Component.literal("Entity render mode set to: " + mode));
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendFeedback(Component.literal("Current entity render mode: " + clientConfig.entityRenderMode));
						return 1;
					}))
				.then(literal("debug")
					.executes(ctx -> {
						clientConfig.debugLogging = !clientConfig.debugLogging;
						clientConfig.save();
						ctx.getSource().sendFeedback(Component.literal("Debug logging " + (clientConfig.debugLogging ? "enabled" : "disabled")));
						return 1;
					})));

			dispatcher.register(literal("voxyhighlight")
				.executes(ctx -> {
					LODSectionHighlightTracker.toggle();
					ctx.getSource().sendFeedback(Component.literal(
						"[Voxy] Section highlighting " +
						(LODSectionHighlightTracker.isEnabled() ? "enabled" : "disabled")
					));
					return 1;
				})
				.then(literal("clear")
					.executes(ctx -> {
						LODSectionHighlightTracker.clear();
						ctx.getSource().sendFeedback(Component.literal("[Voxy] Cleared section highlights"));
						return 1;
					})));
		});
	}
}
