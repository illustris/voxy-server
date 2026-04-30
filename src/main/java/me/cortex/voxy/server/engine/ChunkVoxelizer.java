package me.cortex.voxy.server.engine;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.streaming.SyncService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkVoxelizer {
	private static final long RETRY_INTERVAL_TICKS = 2L;

	private final ServerLodEngine engine;
	private final SyncService syncService;
	private final VoxyServerConfig config;
	private final ConcurrentHashMap<PendingChunk, Long> pendingChunkRetries = new ConcurrentHashMap<>();
	private long currentTick;
	private int totalVoxelized = 0;

	private record PendingChunk(Identifier dimension, int chunkX, int chunkZ) {}

	public ChunkVoxelizer(ServerLodEngine engine, SyncService syncService, VoxyServerConfig config) {
		this.engine = engine;
		this.syncService = syncService;
		this.config = config;
		VoxyServerMod.LOGGER.info("[Voxelizer] Created, generateOnChunkLoad={}", config.generateOnChunkLoad);
	}

	public void register() {
		// Always register the listeners; the onChunkLoad callback gates on
		// config.generateOnChunkLoad live so the toggle takes effect at runtime
		// without re-registration (which Fabric's event API doesn't support).
		//? if HAS_RENDER_PIPELINES {
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, isNew) -> this.onChunkLoad(level, chunk));
		//?} else {
		/*ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> this.onChunkLoad(level, chunk));
		*///?}
		ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		VoxyServerMod.LOGGER.info("[Voxelizer] Registered chunk load listener");
	}

	private void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		if (!config.generateOnChunkLoad) return;
		VoxyServerMod.debug("[Voxelizer] Chunk loaded: ({},{}) in {}",
			chunk.getPos().x(), chunk.getPos().z(), level.dimension().identifier());
		if (ingestChunk(level, chunk, false)) {
			pendingChunkRetries.remove(new PendingChunk(level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z()));
			return;
		}
		scheduleRetry(level, chunk);
	}

	private boolean ingestChunk(ServerLevel level, LevelChunk chunk, boolean force) {
		WorldEngine world = engine.getOrCreate(level);
		if (world == null) return false;

		engine.markChunkPossiblyPresent(level, chunk);

		// Skip if this chunk has already been voxelized (idempotency guard).
		// Re-voxelizing produces non-deterministic results (depends on lighting
		// and neighbor state), causing spurious hash conflicts.
		// Only force=true (from DirtyScanService for actual block changes) bypasses this.
		//
		// IMPORTANT: The marker key is per-chunk (using level=15 namespace), NOT
		// per-WorldSection. A WorldSection covers 2x2 chunks, so a section-level
		// check would incorrectly skip neighbor chunks that share the same section.
		if (!force) {
			long chunkMarkerKey = WorldEngine.getWorldSectionId(
				15, chunk.getPos().x(), 0, chunk.getPos().z()
			);
			if (engine.getSectionHashStore().getHash(chunkMarkerKey) != 0) {
				VoxyServerMod.debug("[Voxelizer] Skipping already-voxelized chunk ({},{}) in {}",
					chunk.getPos().x(), chunk.getPos().z(), level.dimension().identifier());
				return true; // pretend success so it's not retried
			}
		}

		long invocationTick = level.getServer().getTickCount();
		engine.getChunkTimestampStore().markVoxelizationStarted(
			chunk.getPos().x(), chunk.getPos().z(), invocationTick
		);

		boolean enqueued = engine.getIngestService().enqueueIngest(world, chunk);
		if (enqueued) {
			// Mark this specific chunk as voxelized so it won't be re-voxelized
			// on future loads. Uses level=15 namespace to avoid collisions with
			// real section hashes (level=0).
			if (!force) {
				long chunkMarkerKey = WorldEngine.getWorldSectionId(
					15, chunk.getPos().x(), 0, chunk.getPos().z()
				);
				engine.getSectionHashStore().putHash(chunkMarkerKey, 1L);
				// Notify sync service so active sessions' Merkle trees can flip
				// the column to "covered" without waiting for the dirty-section
				// debounce (~20 ticks). Otherwise the dispatcher loops through
				// these columns finding no missing chunks.
				if (syncService != null) {
					syncService.notifyChunkMarkerSet(chunk.getPos().x(), chunk.getPos().z());
				}
			}
			totalVoxelized++;
			if (totalVoxelized % 100 == 0) {
				VoxyServerMod.LOGGER.info("[Voxelizer] Total chunks voxelized: {}", totalVoxelized);
			}
		}
		return enqueued;
	}

	/**
	 * Re-voxelize a chunk due to a confirmed block change (from DirtyScanService).
	 * Uses force=true to bypass the already-voxelized check.
	 */
	public boolean revoxelizeChunk(ServerLevel level, LevelChunk chunk) {
		VoxyServerMod.debug("[Voxelizer] Re-voxelizing chunk ({},{}) in {}",
			chunk.getPos().x(), chunk.getPos().z(), level.dimension().identifier());
		return ingestChunk(level, chunk, true);
	}

	/**
	 * Voxelize a newly generated chunk. Skips if already voxelized.
	 */
	public boolean voxelizeNewChunk(ServerLevel level, LevelChunk chunk) {
		return ingestChunk(level, chunk, false);
	}

	private void scheduleRetry(ServerLevel level, LevelChunk chunk) {
		pendingChunkRetries.put(
			new PendingChunk(level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z()),
			currentTick + RETRY_INTERVAL_TICKS
		);
	}

	private void onServerTick(MinecraftServer server) {
		currentTick++;
		if (pendingChunkRetries.isEmpty()) return;

		for (Map.Entry<PendingChunk, Long> entry : pendingChunkRetries.entrySet()) {
			if (entry.getValue() > currentTick) continue;

			PendingChunk pendingChunk = entry.getKey();
			ServerLevel level = findLevel(server, pendingChunk.dimension());
			if (level == null) { pendingChunkRetries.remove(pendingChunk, entry.getValue()); continue; }

			LevelChunk chunk = level.getChunkSource().getChunkNow(pendingChunk.chunkX(), pendingChunk.chunkZ());
			if (chunk == null) { pendingChunkRetries.remove(pendingChunk, entry.getValue()); continue; }

			if (ingestChunk(level, chunk, false)) {
				pendingChunkRetries.remove(pendingChunk, entry.getValue());
			} else {
				pendingChunkRetries.replace(pendingChunk, entry.getValue(), currentTick + RETRY_INTERVAL_TICKS);
			}
		}
	}

	private static ServerLevel findLevel(MinecraftServer server, Identifier dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().equals(dimension)) return level;
		}
		return null;
	}
}
