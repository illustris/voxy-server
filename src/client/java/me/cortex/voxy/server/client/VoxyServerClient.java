package me.cortex.voxy.server.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public class VoxyServerClient implements ClientModInitializer {

	private static VoxyServerClientConfig clientConfig;

	@Override
	public void onInitializeClient() {
		ClientSyncHandler.register();

		clientConfig = VoxyServerClientConfig.load();
		LODEntityRenderer entityRenderer = new LODEntityRenderer(
			ClientSyncHandler.getLODEntityManager(), clientConfig
		);

		// Must use COLLECT_SUBMITS so entity render nodes are submitted before
		// renderSolidFeatures() processes them. AFTER_SOLID_FEATURES is too late --
		// solid render types (entityCutoutNoCull used by most mobs) would never
		// be processed because the solid pass already ran.
		LevelRenderEvents.COLLECT_SUBMITS.register(entityRenderer::render);

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
		});
	}
}
