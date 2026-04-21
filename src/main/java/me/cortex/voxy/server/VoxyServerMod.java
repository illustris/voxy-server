package me.cortex.voxy.server;

import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.engine.ChunkGenerationService;
import me.cortex.voxy.server.engine.ChunkVoxelizer;
import me.cortex.voxy.server.engine.DirtyScanService;
import me.cortex.voxy.server.engine.ServerLodEngine;
import me.cortex.voxy.server.network.VoxyServerNetworking;
import me.cortex.voxy.server.streaming.EntitySyncService;
import me.cortex.voxy.server.streaming.SyncService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyServerMod implements ModInitializer {
	public static final String MOD_ID = "voxy-server";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static VoxyServerConfig config;
	private ServerLodEngine lodEngine;
	private ChunkVoxelizer chunkVoxelizer;
	private SyncService syncService;
	private DirtyScanService dirtyScanService;
	private ChunkGenerationService chunkGenService;
	private EntitySyncService entitySyncService;

	private static volatile boolean debugEnabled = false;

	public static VoxyServerConfig getConfig() {
		return config;
	}

	public static boolean isDebug() {
		return debugEnabled;
	}

	public static void debug(String msg, Object... args) {
		if (debugEnabled) {
			LOGGER.info(msg, args);
		}
	}

	@Override
	public void onInitialize() {
		config = VoxyServerConfig.load();
		debugEnabled = config.debugLogging;
		LOGGER.info("Voxy Server initialized (debug={})", debugEnabled);
		VoxyServerNetworking.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!server.isDedicatedServer()) {
				LOGGER.info("Voxy Server disabled in singleplayer.");
				return;
			}

			var worldPath = server.getWorldPath(LevelResource.ROOT);
			lodEngine = new ServerLodEngine(worldPath);
			lodEngine.updateDedicatedThreadsCount(config.workerThreads);
			syncService = new SyncService(lodEngine, config);
			syncService.register();
			chunkVoxelizer = new ChunkVoxelizer(lodEngine, syncService, config);
			chunkVoxelizer.register();
			if (config.passiveChunkGeneration) {
				chunkGenService = new ChunkGenerationService(lodEngine, chunkVoxelizer, config);
			} else {
				LOGGER.info("Passive chunk generation disabled");
			}
			dirtyScanService = new DirtyScanService(lodEngine, chunkVoxelizer, syncService, config);
			if (config.enableEntitySync) {
				entitySyncService = new EntitySyncService(config, syncService);
			}

			ServerTickEvents.END_SERVER_TICK.register(tick -> {
				if (syncService != null) syncService.tick(tick);
				if (dirtyScanService != null) dirtyScanService.tick(tick);
				if (chunkGenService != null) chunkGenService.tick(tick);
				if (entitySyncService != null) entitySyncService.tick(tick);
			});

			LOGGER.info("Voxy Server engine started for world: {}", worldPath);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (lodEngine != null) {
				LOGGER.info("Shutting down Voxy Server engine");
				if (entitySyncService != null) entitySyncService.shutdown();
				if (chunkGenService != null) chunkGenService.shutdown();
				if (syncService != null) syncService.shutdown();
				if (dirtyScanService != null) dirtyScanService.shutdown();
				lodEngine.shutdown();
				lodEngine = null;
				chunkVoxelizer = null;
				syncService = null;
				dirtyScanService = null;
				chunkGenService = null;
				entitySyncService = null;
			}
		});

		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			if (syncService != null) {
				syncService.onDimensionChange(player, destination);
			}
			if (entitySyncService != null) {
				entitySyncService.onDimensionChange(player);
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (entitySyncService != null) {
				entitySyncService.onPlayerDisconnect(handler.getPlayer().getUUID());
			}
		});
	}
}
