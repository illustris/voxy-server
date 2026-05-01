package me.cortex.voxy.server.engine;

import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.streaming.SyncService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;

public class DirtyScanService {
	// Emit a single summary line per window instead of per-event chatter.
	// 60 ticks = 3 seconds; matches the SyncService telemetry cadence.
	private static final int SUMMARY_INTERVAL_TICKS = 60;

	private final ServerLodEngine engine;
	private final ChunkVoxelizer voxelizer;
	private final SyncService syncService;
	private final VoxyServerConfig config;
	private int tickCounter = 0;
	private int summaryCounter = 0;

	// Window counters reset each summary interval.
	private int windowScans = 0;
	private int windowDirtyFound = 0;
	private int windowRevoxelized = 0;
	private int windowSkippedUnloaded = 0;

	public DirtyScanService(ServerLodEngine engine, ChunkVoxelizer voxelizer,
							SyncService syncService, VoxyServerConfig config) {
		this.engine = engine;
		this.voxelizer = voxelizer;
		this.syncService = syncService;
		this.config = config;
		VoxyServerMod.LOGGER.info("[DirtyScan] Initialized with interval={} ticks, maxPerScan={}",
			config.dirtyScanInterval, config.maxDirtyChunksPerScan);
	}

	public void tick(MinecraftServer server) {
		summaryCounter++;
		if (summaryCounter >= SUMMARY_INTERVAL_TICKS) {
			emitSummary();
			summaryCounter = 0;
		}

		if (++tickCounter < config.dirtyScanInterval) return;
		tickCounter = 0;

		ChunkTimestampStore store = engine.getChunkTimestampStore();
		List<ChunkPos> dirtyChunks = store.findDirtyChunks(config.maxDirtyChunksPerScan);
		windowScans++;
		if (dirtyChunks.isEmpty()) return;
		windowDirtyFound += dirtyChunks.size();

		for (ChunkPos pos : dirtyChunks) {
			store.getAndClearBlockChangeInfo(pos.x(), pos.z()); // drain so we don't accumulate
			boolean found = false;
			for (ServerLevel level : server.getAllLevels()) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
				if (chunk != null) {
					voxelizer.revoxelizeChunk(level, chunk);
					windowRevoxelized++;
					found = true;
					break;
				}
			}
			if (!found) {
				// Drop the record. Otherwise the same unloaded chunks sit at
				// the head of the RocksDB iteration order forever, capping the
				// scan at maxDirtyChunksPerScan stale entries every cycle and
				// starving fresh edits at the tail. ChunkVoxelizer.onChunkLoad
				// re-checks isChunkDirty when the chunk comes back, so dropping
				// here doesn't lose the dirty bit if the chunk reloads later.
				store.deleteRecord(pos.x(), pos.z());
				windowSkippedUnloaded++;
			}
		}
	}

	private void emitSummary() {
		// Suppress empty windows -- nothing to report when the server is idle.
		if (windowScans == 0 || (windowDirtyFound == 0 && windowSkippedUnloaded == 0)) {
			windowScans = 0;
			windowDirtyFound = 0;
			windowRevoxelized = 0;
			windowSkippedUnloaded = 0;
			return;
		}
		VoxyServerMod.debug(
			"[DirtyScan] window={}t scans={} dirtyFound={} revoxelized={} skippedUnloaded={}",
			SUMMARY_INTERVAL_TICKS, windowScans, windowDirtyFound, windowRevoxelized, windowSkippedUnloaded);
		windowScans = 0;
		windowDirtyFound = 0;
		windowRevoxelized = 0;
		windowSkippedUnloaded = 0;
	}

	public void shutdown() {}
}
