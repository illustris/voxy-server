package me.cortex.voxy.server.engine;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
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
	// Spawn re-scan: vanilla's prepareLevels returns before all spawn chunks
	// have reached FULL status, so the one-shot SERVER_STARTED scan only
	// catches a fraction (~49 of ~1089 in the local test). Re-run every 20
	// ticks for the first ~60s to pick up chunks as they finish loading.
	private static final long SPAWN_RESCAN_INTERVAL_TICKS = 20L;
	private static final long SPAWN_RESCAN_DURATION_TICKS = 1200L;
	private long spawnRescanCutoffTick = -1;

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

	/**
	 * Scan chunks force-loaded around each dimension's spawn point and run
	 * them through {@link #ingestChunk} if they're at FULL status. Idempotent
	 * via the marker check inside ingestChunk -- chunks that were already
	 * voxelized are skipped, so this can be called repeatedly cheaply.
	 *
	 * Used both as a one-shot at SERVER_STARTED (when vanilla has just
	 * returned from prepareLevels) and as a periodic re-scan for the first
	 * minute of server uptime (because prepareLevels returns before most
	 * spawn chunks have actually reached FULL status -- the one-shot only
	 * catches a fraction of them).
	 */
	public void scanAlreadyLoadedSpawnChunks(MinecraftServer server) {
		if (!config.generateOnChunkLoad) return;
		int totalScanned = 0;
		int totalEnqueued = 0;
		var hashStore = engine.getSectionHashStore();
		for (ServerLevel level : server.getAllLevels()) {
			//? if HAS_LOOKUP_OR_THROW {
			var spawn = level.getRespawnData().pos();
			//?} else {
			/*var spawn = level.getSharedSpawnPos();
			*///?}
			int spawnChunkX = spawn.getX() >> 4;
			int spawnChunkZ = spawn.getZ() >> 4;
			// Vanilla force-loads a 23x23 chunk square (radius 11) around the
			// shared spawn for the overworld, plus configurable forceloaded
			// chunks. Use 16 (slightly larger) to cover both, plus safety.
			int radius = 16;
			int dimEnqueued = 0;
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					int cx = spawnChunkX + dx;
					int cz = spawnChunkZ + dz;
					// Skip already-marked chunks here so the count below reflects
					// genuinely new work, not ingestChunk's idempotent short-circuit.
					long markerKey = WorldEngine.getWorldSectionId(15, cx, 0, cz);
					if (hashStore.getHash(markerKey) != 0L) continue;
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) continue;
					totalScanned++;
					if (ingestChunk(level, chunk, false) == IngestResult.ENQUEUED) {
						dimEnqueued++;
					}
				}
			}
			totalEnqueued += dimEnqueued;
			if (dimEnqueued > 0) {
				VoxyServerMod.debug(
					"[Voxelizer] Spawn scan {}: enqueued {} new chunks",
					level.dimension().identifier(), dimEnqueued);
			}
		}
		// Only log on the very first invocation or when the periodic rescan
		// finds something. Otherwise this fires every 20 ticks.
		if (totalEnqueued > 0 || spawnRescanCutoffTick < 0) {
			VoxyServerMod.debug(
				"[Voxelizer] Spawn scan: {} unmarked chunks scanned, {} newly enqueued",
				totalScanned, totalEnqueued);
		}
		// Arm the periodic rescan window the first time this is invoked.
		if (spawnRescanCutoffTick < 0) {
			spawnRescanCutoffTick = currentTick + SPAWN_RESCAN_DURATION_TICKS;
		}
	}

	private void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		if (!config.generateOnChunkLoad) return;
		// Self-heal for the DirtyScanService.skippedUnloaded -> deleteRecord
		// path: if a chunk got edited while loaded, then unloaded before
		// DirtyScan could revoxelize it, the scan will have dropped its
		// record. When it comes back, the per-chunk marker is still set
		// from its prior voxelization, so the regular force=false path
		// would short-circuit and the edit would be lost. Force-revoxelize
		// here when the timestamp store still says lastBlockTick > lastVoxTick
		// (which can only be true if either: the scan hasn't run yet, or
		// the chunk unloaded before the scan got to it).
		boolean force = engine.getChunkTimestampStore()
			.isChunkDirty(chunk.getPos().x(), chunk.getPos().z());
		if (ingestChunk(level, chunk, force) != IngestResult.FAILED) {
			pendingChunkRetries.remove(new PendingChunk(level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z()));
			return;
		}
		scheduleRetry(level, chunk);
	}

	/**
	 * Outcome of an ingest attempt. Callers that drive retry loops treat
	 * anything other than {@link #FAILED} as "stop retrying"; callers that
	 * tally real work done (telemetry, scan counters) only count
	 * {@link #ENQUEUED}.
	 */
	public enum IngestResult {
		ENQUEUED,           // task submitted; voxy worker will write section data
		ALREADY_VOXELIZED,  // chunk had a per-chunk marker; idempotent skip
		FAILED              // ingest queue rejected; caller may retry
	}

	private IngestResult ingestChunk(ServerLevel level, LevelChunk chunk, boolean force) {
		WorldEngine world = engine.getOrCreate(level);
		if (world == null) return IngestResult.FAILED;

		engine.markChunkPossiblyPresent(level, chunk);

		// Skip if this chunk has already been voxelized (idempotency guard).
		// Re-voxelizing produces non-deterministic results (depends on lighting
		// and neighbor state), causing spurious hash conflicts.
		// Only force=true (from DirtyScanService for actual block changes) bypasses this.
		//
		// IMPORTANT: The marker key is per-chunk (using level=15 namespace), NOT
		// per-WorldSection. A WorldSection covers 2x2 chunks, so a section-level
		// check would incorrectly skip neighbor chunks that share the same section.
		long markerKey = WorldEngine.getWorldSectionId(15, chunk.getPos().x(), 0, chunk.getPos().z());
		if (!force) {
			if (engine.getSectionHashStore().getHash(markerKey) != 0L) {
				return IngestResult.ALREADY_VOXELIZED;
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
				engine.getSectionHashStore().putHash(markerKey, 1L);
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
				VoxyServerMod.debug("[Voxelizer] Total chunks voxelized: {}", totalVoxelized);
			}
			return IngestResult.ENQUEUED;
		}
		return IngestResult.FAILED;
	}

	/**
	 * Re-voxelize a chunk due to a confirmed block change (from DirtyScanService).
	 * Uses force=true to bypass the already-voxelized check, so the result is
	 * either {@link IngestResult#ENQUEUED} or {@link IngestResult#FAILED}
	 * (never {@link IngestResult#ALREADY_VOXELIZED}).
	 */
	public IngestResult revoxelizeChunk(ServerLevel level, LevelChunk chunk) {
		return ingestChunk(level, chunk, true);
	}

	/**
	 * Detect "dangling markers": per-chunk markers (level=15) for which the
	 * containing WorldSection (level=0) has no stored hash at any Y. This
	 * happens when a previous server run successfully enqueued a voxelization
	 * (so the marker was set) but crashed/was-killed before voxy's worker
	 * pool finished writing the section data. Both the per-player
	 * {@code voxelizeAlreadyLoadedEmptyColumns} pass and the dispatcher's
	 * {@code getNearestEmptyColumns} skip such columns (markedFullColumns +
	 * empty L1 looks identical to "fully covered"), so without an explicit
	 * recovery they stay broken forever.
	 *
	 * Bounded to the spawn region for cost. The user-facing
	 * {@code /voxysv revoxelize} command covers other regions on demand.
	 */
	public void detectAndRecoverDanglingSpawnMarkers(MinecraftServer server) {
		var hashStore = engine.getSectionHashStore();
		int totalDanglingChunks = 0;
		for (ServerLevel level : server.getAllLevels()) {
			//? if HAS_LOOKUP_OR_THROW {
			var spawn = level.getRespawnData().pos();
			//?} else {
			/*var spawn = level.getSharedSpawnPos();
			*///?}
			int spawnSecX = spawn.getX() >> 5;
			int spawnSecZ = spawn.getZ() >> 5;
			int sectionRadius = 8; // 17x17 sections = 34x34 chunks, covers spawn

			LongOpenHashSet sectionsWithL0 = new LongOpenHashSet();
			hashStore.iterateInBounds(spawnSecX - sectionRadius, spawnSecX + sectionRadius,
					spawnSecZ - sectionRadius, spawnSecZ + sectionRadius,
					(key, hash) -> {
						int x = WorldEngine.getX(key);
						int z = WorldEngine.getZ(key);
						sectionsWithL0.add(((long) x << 32) | (z & 0xFFFFFFFFL));
					});

			LongOpenHashSet markersWithoutL0 = new LongOpenHashSet();
			hashStore.iterateMarkersInBounds(spawnSecX - sectionRadius, spawnSecX + sectionRadius,
					spawnSecZ - sectionRadius, spawnSecZ + sectionRadius,
					(chunkX, chunkZ) -> {
						int sectionX = chunkX >> 1;
						int sectionZ = chunkZ >> 1;
						long sectionXZ = ((long) sectionX << 32) | (sectionZ & 0xFFFFFFFFL);
						if (!sectionsWithL0.contains(sectionXZ)) {
							markersWithoutL0.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
						}
						return 0;
					});

			if (markersWithoutL0.isEmpty()) continue;
			int dimRecovered = 0;
			for (long packed : markersWithoutL0) {
				int cx = (int) (packed >> 32);
				int cz = (int) packed;
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				if (ingestChunk(level, chunk, true) == IngestResult.ENQUEUED) dimRecovered++;
			}
			totalDanglingChunks += dimRecovered;
			VoxyServerMod.LOGGER.info(
				"[Voxelizer] Recovered {} dangling-marker chunks in {} (out of {} dangling, rest not loaded)",
				dimRecovered, level.dimension().identifier(), markersWithoutL0.size());
		}
		if (totalDanglingChunks > 0) {
			VoxyServerMod.LOGGER.info(
				"[Voxelizer] Dangling-marker recovery complete: {} chunks force-revoxelized",
				totalDanglingChunks);
		}
	}

	/**
	 * Voxelize a newly generated chunk. Returns {@link IngestResult#ENQUEUED}
	 * if real work was queued; {@link IngestResult#ALREADY_VOXELIZED} for the
	 * idempotent short-circuit (existing per-chunk marker), so callers
	 * tallying real work (telemetry) can distinguish the two.
	 */
	public IngestResult voxelizeNewChunk(ServerLevel level, LevelChunk chunk) {
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
		// Periodic spawn rescan to catch chunks that finish loading after
		// SERVER_STARTED. The CHUNK_LOAD listener should also fire for these,
		// but in practice we've observed gaps -- if vanilla loads a chunk
		// while our retry queue has it pending and getChunkNow briefly
		// returns null, the retry is dropped and that chunk is missed forever.
		if (spawnRescanCutoffTick > 0 && currentTick <= spawnRescanCutoffTick
				&& currentTick % SPAWN_RESCAN_INTERVAL_TICKS == 0) {
			scanAlreadyLoadedSpawnChunks(server);
		}
		if (pendingChunkRetries.isEmpty()) return;

		for (Map.Entry<PendingChunk, Long> entry : pendingChunkRetries.entrySet()) {
			if (entry.getValue() > currentTick) continue;

			PendingChunk pendingChunk = entry.getKey();
			ServerLevel level = findLevel(server, pendingChunk.dimension());
			if (level == null) { pendingChunkRetries.remove(pendingChunk, entry.getValue()); continue; }

			LevelChunk chunk = level.getChunkSource().getChunkNow(pendingChunk.chunkX(), pendingChunk.chunkZ());
			if (chunk == null) { pendingChunkRetries.remove(pendingChunk, entry.getValue()); continue; }

			if (ingestChunk(level, chunk, false) != IngestResult.FAILED) {
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
