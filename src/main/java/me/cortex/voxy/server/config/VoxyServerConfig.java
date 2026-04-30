package me.cortex.voxy.server.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.server.VoxyServerMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class VoxyServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "voxy-server.json";

	public int lodStreamRadius = 256;
	public int maxSectionsPerTickPerPlayer = 100;
	public int sectionsPerPacket = 50;
	public boolean generateOnChunkLoad = true;
	public int workerThreads = 3;
	public int dirtyScanInterval = 10;
	public int maxDirtyChunksPerScan = 64;
	public boolean debugLogging = false;
	// Chunk generation rate is self-tuning: AIMD against targetTps.
	// Below target -> back off (multiplicative decrease).
	// At/above target -> ramp up (additive increase).
	// Lower values prioritize game responsiveness over LOD fill speed.
	public int targetTps = 15;

	// Entity sync settings
	public boolean enableEntitySync = true;
	public int entitySyncIntervalTicks = 10;       // scan every 0.5s
	public int maxLODEntitiesPerPlayer = 200;       // cap per player
	public String entitySyncMode = "non_trivial";   // "living", "non_trivial", "all", "players_only"

	// When true and the Sable mod (dev.ryanhcode.sable) is installed, lift
	// SableConfig.SUB_LEVEL_TRACKING_RANGE to lodStreamRadius * 32 blocks so
	// physics SubLevels (e.g. Aeronautica assemblies) stream out to LOD
	// distance instead of the default 320 blocks. Never lowers an existing
	// higher value.
	public boolean compatSableAutoTrackingRange = true;

	public static VoxyServerConfig load() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (Files.exists(configPath)) {
			try {
				String json = Files.readString(configPath);
				VoxyServerConfig config = GSON.fromJson(json, VoxyServerConfig.class);
				if (config != null) {
					config.save();
					return config;
				}
			} catch (Exception e) {
				VoxyServerMod.LOGGER.warn("Failed to load config, using defaults", e);
			}
		}
		VoxyServerConfig config = new VoxyServerConfig();
		config.save();
		return config;
	}

	public void save() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			Files.createDirectories(configPath.getParent());
			Files.writeString(configPath, GSON.toJson(this));
		} catch (IOException e) {
			VoxyServerMod.LOGGER.warn("Failed to save config", e);
		}
	}
}
