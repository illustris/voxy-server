package me.cortex.voxy.server.engine;

import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.streaming.SyncService;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;

/**
 * Periodically scans the ChunkTimestampStore for chunks where
 * lastBlockUpdateTick > lastVoxelizationTick and queues re-voxelization.
 * Replaces the ephemeral DirtyTracker with a persistent, restart-safe approach.
 */
public class DirtyScanService {
	private final ServerLodEngine engine;
	private final ChunkVoxelizer voxelizer;
	private final SyncService syncService;
	private final int scanInterval;
	private final int maxPerScan;
	private int tickCounter = 0;

	public DirtyScanService(ServerLodEngine engine, ChunkVoxelizer voxelizer,
							SyncService syncService, VoxyServerConfig config) {
		this.engine = engine;
		this.voxelizer = voxelizer;
		this.syncService = syncService;
		this.scanInterval = config.dirtyScanInterval;
		this.maxPerScan = config.maxDirtyChunksPerScan;
	}

	public void tick(MinecraftServer server) {
		if (++tickCounter < scanInterval) {
			return;
		}
		tickCounter = 0;

		ChunkTimestampStore store = engine.getChunkTimestampStore();
		List<ChunkPos> dirtyChunks = store.findDirtyChunks(maxPerScan);

		for (ChunkPos pos : dirtyChunks) {
			// Try to find the chunk in any loaded level
			for (ServerLevel level : server.getAllLevels()) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
				if (chunk != null) {
					voxelizer.revoxelizeChunk(level, chunk);
					break;
				}
			}
		}
	}

	public void shutdown() {
		// nothing to clean up -- state is in DB
	}
}
