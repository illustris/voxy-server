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
	public int sectionsPerPacket = 200;
	public boolean generateOnChunkLoad = true;
	// Voxy's UnifiedServiceThreadPool worker count. These threads pull from
	// VoxelIngestService.ingestQueue and run the actual voxelization +
	// RocksDB writes. Bumping helps when section commit rate lags behind
	// chunk dispatch (visible as voxyIngestQueueSize growing).
	public int workerThreads = 8;

	// Hard cap on concurrent in-flight chunk generations (genExecutor pool
	// size). Each in-flight slot does getChunk(FULL, true) which routes
	// through the main thread under Lithium, so this directly determines
	// main-thread contention. Steady-state submission cps =
	// maxInFlightChunks * targetTps. Tune up while watching mcTickEmaMs
	// and voxyIngestQueueSize.
	public int maxInFlightChunks = 16;
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

	// In-memory cache for L0 section hashes (the hot path: every block change
	// re-hashes a section, looking up the prior hash in SectionHashStore).
	// Size is in bytes; each entry costs ~16 B (key + value + Long2LongOpenHashMap
	// overhead). Default 200 MB ~= 12.5M cached entries. RocksDB block cache
	// (separate, fixed at 128 MB) backstops misses.
	public long l0HashCacheCapBytes = 200L * 1024 * 1024;

	// Heartbeat: how often (in ticks) the server emits an L2-hashes snapshot
	// to each session for merkle reconciliation. Default 100 = 5 s. The
	// emission is a no-op when the tree's L3 root is unchanged since the
	// last emit, so steady state with no edits costs zero bandwidth.
	public int merkleHeartbeatTicks = 100;

	// slideBounds threshold: when the player's new section center differs
	// from the tree's by more than this many sections (Chebyshev distance),
	// fall back to a full rebuild instead of incremental strip-update. The
	// strip-update cost grows linearly with the delta and exceeds a fresh
	// build past ~radius/4. 64 covers the common case (walking, sprinting,
	// elytra) and short teleports; longer warps and dimension-internal /tp
	// fall back to rebuild.
	public int merkleSlideTeleportThreshold = 64;

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
