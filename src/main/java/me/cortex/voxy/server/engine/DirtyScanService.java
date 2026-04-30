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
	private final ServerLodEngine engine;
	private final ChunkVoxelizer voxelizer;
	private final SyncService syncService;
	private final VoxyServerConfig config;
	private int tickCounter = 0;

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
		if (++tickCounter < config.dirtyScanInterval) return;
		tickCounter = 0;

		ChunkTimestampStore store = engine.getChunkTimestampStore();
		List<ChunkPos> dirtyChunks = store.findDirtyChunks(config.maxDirtyChunksPerScan);

		if (!dirtyChunks.isEmpty()) {
			VoxyServerMod.debug("[DirtyScan] Found {} dirty chunks", dirtyChunks.size());
		}

		for (ChunkPos pos : dirtyChunks) {
			ChunkTimestampStore.BlockChangeInfo changeInfo = store.getAndClearBlockChangeInfo(pos.x(), pos.z());
			boolean found = false;
			for (ServerLevel level : server.getAllLevels()) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
				if (chunk != null) {
					if (changeInfo != null) {
						VoxyServerMod.debug("[DirtyScan] Re-voxelizing dirty chunk ({},{}) in {}, triggered by {} block change(s), last: ({},{},{}) {} -> {}",
							pos.x(), pos.z(), level.dimension().identifier(),
							changeInfo.count(), changeInfo.x(), changeInfo.y(), changeInfo.z(),
							changeInfo.oldState(), changeInfo.newState());
					} else {
						VoxyServerMod.debug("[DirtyScan] Re-voxelizing dirty chunk ({},{}) in {} (trigger info unavailable, likely from previous session)",
							pos.x(), pos.z(), level.dimension().identifier());
					}
					voxelizer.revoxelizeChunk(level, chunk);
					found = true;
					break;
				}
			}
			if (!found) {
				VoxyServerMod.debug("[DirtyScan] Dirty chunk ({},{}) not loaded, skipping", pos.x(), pos.z());
			}
		}
	}

	public void shutdown() {}
}
