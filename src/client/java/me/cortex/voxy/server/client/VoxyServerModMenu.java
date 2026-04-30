package me.cortex.voxy.server.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration entrypoint (optional dep). Wired up in fabric.mod.json
 * under the {@code modmenu} entrypoint key. If ModMenu is not installed this
 * class simply isn't loaded -- ModMenu is the only consumer of the entrypoint.
 *
 * Returns a factory that builds {@link VoxyServerConfigScreen} when the user
 * opens the per-mod config screen from the ModMenu list.
 */
public class VoxyServerModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new VoxyServerConfigScreen(parent);
	}
}
