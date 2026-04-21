package me.cortex.voxy.server.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyServerClientConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("voxy-server-client");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "voxy-server-client.json";

	public String entityRenderMode = "billboard"; // "billboard" or "model"

	public static VoxyServerClientConfig load() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (Files.exists(configPath)) {
			try {
				String json = Files.readString(configPath);
				VoxyServerClientConfig config = GSON.fromJson(json, VoxyServerClientConfig.class);
				if (config != null) {
					config.save();
					return config;
				}
			} catch (Exception e) {
				LOGGER.warn("Failed to load client config, using defaults", e);
			}
		}
		VoxyServerClientConfig config = new VoxyServerClientConfig();
		config.save();
		return config;
	}

	public void save() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			Files.createDirectories(configPath.getParent());
			Files.writeString(configPath, GSON.toJson(this));
		} catch (IOException e) {
			LOGGER.warn("Failed to save client config", e);
		}
	}
}
