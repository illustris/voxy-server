package me.cortex.voxy.server.client;

import net.fabricmc.api.ClientModInitializer;

public class VoxyServerClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientSyncHandler.register();
	}
}
