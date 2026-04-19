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
	private final boolean generateOnChunkLoad;
	private final ConcurrentHashMap<PendingChunk, Long> pendingChunkRetries = new ConcurrentHashMap<>();
	private long currentTick;

	private record PendingChunk(Identifier dimension, int chunkX, int chunkZ) {}

	public ChunkVoxelizer(ServerLodEngine engine, SyncService syncService, VoxyServerConfig config) {
		this.engine = engine;
		this.syncService = syncService;
		this.generateOnChunkLoad = config.generateOnChunkLoad;
	}

	public void register() {
		if (generateOnChunkLoad) {
			ServerChunkEvents.CHUNK_LOAD.register((level, chunk, isNew) -> this.onChunkLoad(level, chunk));
			ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
		}
	}

	private void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		if (ingestChunk(level, chunk)) {
			pendingChunkRetries.remove(new PendingChunk(level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z()));
			return;
		}
		scheduleRetry(level, chunk);
	}

	private boolean ingestChunk(ServerLevel level, LevelChunk chunk) {
		WorldEngine world = engine.getOrCreate(level);
		if (world == null) return false;

		engine.markChunkPossiblyPresent(level, chunk);

		// Mark voxelization started at invocation time
		long invocationTick = level.getServer().getTickCount();
		engine.getChunkTimestampStore().markVoxelizationStarted(
			chunk.getPos().x(), chunk.getPos().z(), invocationTick
		);

		boolean enqueued = engine.getIngestService().enqueueIngest(world, chunk);
		return enqueued;
	}

	/**
	 * Re-voxelize a chunk that has been marked dirty by the timestamp scanner.
	 */
	public boolean revoxelizeChunk(ServerLevel level, LevelChunk chunk) {
		return ingestChunk(level, chunk);
	}

	private void scheduleRetry(ServerLevel level, LevelChunk chunk) {
		pendingChunkRetries.put(
			new PendingChunk(level.dimension().identifier(), chunk.getPos().x(), chunk.getPos().z()),
			currentTick + RETRY_INTERVAL_TICKS
		);
	}

	private void onServerTick(MinecraftServer server) {
		currentTick++;
		if (pendingChunkRetries.isEmpty()) {
			return;
		}

		for (Map.Entry<PendingChunk, Long> entry : pendingChunkRetries.entrySet()) {
			if (entry.getValue() > currentTick) {
				continue;
			}

			PendingChunk pendingChunk = entry.getKey();
			ServerLevel level = findLevel(server, pendingChunk.dimension());
			if (level == null) {
				pendingChunkRetries.remove(pendingChunk, entry.getValue());
				continue;
			}

			LevelChunk chunk = level.getChunkSource().getChunkNow(pendingChunk.chunkX(), pendingChunk.chunkZ());
			if (chunk == null) {
				pendingChunkRetries.remove(pendingChunk, entry.getValue());
				continue;
			}

			if (ingestChunk(level, chunk)) {
				pendingChunkRetries.remove(pendingChunk, entry.getValue());
				continue;
			}

			pendingChunkRetries.replace(pendingChunk, entry.getValue(), currentTick + RETRY_INTERVAL_TICKS);
		}
	}

	private static ServerLevel findLevel(MinecraftServer server, Identifier dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().equals(dimension)) {
				return level;
			}
		}
		return null;
	}
}
