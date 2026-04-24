package me.cortex.voxy.server.engine;

import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
//? if !MC_1_20_1 {
import net.minecraft.world.level.chunk.status.ChunkStatus;
//?} else {
/*import net.minecraft.world.level.chunk.ChunkStatus;
*///?}

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Generates chunks beyond the player's chunk load radius, out to the full voxy LOD radius.
 * Chunks are generated ASYNCHRONOUSLY on a dedicated thread to avoid blocking the server tick.
 */
public class ChunkGenerationService {
	private static final int CHECK_INTERVAL = 5; // ticks between scheduling batches

	private final ServerLodEngine engine;
	private final ChunkVoxelizer voxelizer;
	private final int lodRadiusChunks;
	private final int batchSize;

	private final ConcurrentHashMap<UUID, PlayerGenState> playerStates = new ConcurrentHashMap<>();
	private final ExecutorService genExecutor;
	private int tickCounter = 0;

	private static class PlayerGenState {
		final List<ChunkPos> queue;
		int index;
		int centerChunkX, centerChunkZ;
		final Set<Long> scheduled = ConcurrentHashMap.newKeySet();
		volatile int completedCount = 0;

		PlayerGenState(List<ChunkPos> queue, int cx, int cz) {
			this.queue = queue;
			this.index = 0;
			this.centerChunkX = cx;
			this.centerChunkZ = cz;
		}
	}

	public ChunkGenerationService(ServerLodEngine engine, ChunkVoxelizer voxelizer, VoxyServerConfig config) {
		this.engine = engine;
		this.voxelizer = voxelizer;
		this.lodRadiusChunks = config.lodStreamRadius * 2;
		this.batchSize = config.chunkGenConcurrency;
		this.genExecutor = Executors.newFixedThreadPool(
			Math.max(1, config.chunkGenConcurrency),
			r -> {
				Thread t = new Thread(r, "VoxyServer ChunkGen");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			}
		);
		VoxyServerMod.LOGGER.info("[ChunkGen] Initialized with LOD radius = {} chunks, concurrency = {}",
			lodRadiusChunks, config.chunkGenConcurrency);
	}

	public void tick(MinecraftServer server) {
		if (++tickCounter < CHECK_INTERVAL) return;
		tickCounter = 0;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();
			int chunkX = player.getBlockX() >> 4;
			int chunkZ = player.getBlockZ() >> 4;

			PlayerGenState state = playerStates.get(id);

			if (state == null || hasMoved(state, chunkX, chunkZ)) {
				List<ChunkPos> queue = generateSpiralQueue(chunkX, chunkZ, lodRadiusChunks);
				state = new PlayerGenState(queue, chunkX, chunkZ);
				playerStates.put(id, state);
				VoxyServerMod.LOGGER.info("[ChunkGen] Built queue for {}: {} chunks", player.getName().getString(), queue.size());
			}

			if (state.index >= state.queue.size()) continue;

			// Schedule a batch of chunks for async generation
			scheduleGenBatch(state, (ServerLevel) player.level(), player.getName().getString(), server);
		}

		// Clean disconnected players
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getUUID());
		playerStates.keySet().removeIf(id -> !online.contains(id));
	}

	private void scheduleGenBatch(PlayerGenState state, ServerLevel level, String playerName, MinecraftServer server) {
		int scheduled = 0;

		while (state.index < state.queue.size() && scheduled < batchSize) {
			ChunkPos pos = state.queue.get(state.index);
			state.index++;

			long key = ((long) pos.x() << 32) | (pos.z() & 0xFFFFFFFFL);
			if (state.scheduled.contains(key)) continue;

			// Skip if chunk already exists (loaded or saved)
			if (level.getChunkSource().hasChunk(pos.x(), pos.z())) {
				state.scheduled.add(key);
				continue;
			}

			state.scheduled.add(key);
			scheduled++;

			// Generate asynchronously -- DO NOT block the tick thread
			genExecutor.execute(() -> {
				try {
					// Use the server's execute to get back on the main thread for chunk gen
					// This queues the work rather than blocking
					server.execute(() -> {
						try {
							ChunkAccess chunk = level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, true);
							if (chunk instanceof LevelChunk levelChunk) {
								voxelizer.voxelizeNewChunk(level, levelChunk);
							}
							state.completedCount++;
							if (state.completedCount % 50 == 0) {
								VoxyServerMod.LOGGER.info("[ChunkGen] {} : generated {} chunks so far",
									playerName, state.completedCount);
							}
						} catch (Exception e) {
							VoxyServerMod.LOGGER.warn("[ChunkGen] Failed to gen chunk ({},{}): {}",
								pos.x(), pos.z(), e.getMessage());
						}
					});
				} catch (Exception e) {
					VoxyServerMod.LOGGER.warn("[ChunkGen] Failed to schedule chunk ({},{}): {}",
						pos.x(), pos.z(), e.getMessage());
				}
			});
		}
	}

	private static boolean hasMoved(PlayerGenState state, int chunkX, int chunkZ) {
		int dx = Math.abs(chunkX - state.centerChunkX);
		int dz = Math.abs(chunkZ - state.centerChunkZ);
		return dx > 16 || dz > 16;
	}

	private static List<ChunkPos> generateSpiralQueue(int cx, int cz, int radius) {
		List<ChunkPos> queue = new ArrayList<>();
		queue.add(new ChunkPos(cx, cz));

		for (int dist = 1; dist <= radius; dist++) {
			for (int x = cx - dist; x <= cx + dist; x++) queue.add(new ChunkPos(x, cz - dist));
			for (int z = cz - dist + 1; z <= cz + dist; z++) queue.add(new ChunkPos(cx + dist, z));
			for (int x = cx + dist - 1; x >= cx - dist; x--) queue.add(new ChunkPos(x, cz + dist));
			for (int z = cz + dist - 1; z >= cz - dist + 1; z--) queue.add(new ChunkPos(cx - dist, z));
		}

		return queue;
	}

	public void shutdown() {
		genExecutor.shutdownNow();
		try {
			genExecutor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		playerStates.clear();
	}
}
