package me.cortex.voxy.server.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class VoxyServerClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientSyncHandler.register();

		VoxyServerClientConfig clientConfig = VoxyServerClientConfig.load();
		LODEntityRenderer entityRenderer = new LODEntityRenderer(
			ClientSyncHandler.getLODEntityManager(), clientConfig
		);

		LevelRenderEvents.AFTER_SOLID_FEATURES.register(entityRenderer::render);
	}
}
