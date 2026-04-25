package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.engine.ChunkVoxelizer;
import me.cortex.voxy.server.engine.ServerLodEngine;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.server.merkle.MerkleHashUtil;
import me.cortex.voxy.server.merkle.PlayerMerkleTree;
import me.cortex.voxy.server.merkle.SectionHashStore;
import me.cortex.voxy.server.network.*;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SyncService {
	private static final int TREE_REBUILD_DISTANCE = 64;
	private static final int POSITION_CHECK_INTERVAL = 100;
	private static final int DIRTY_DEBOUNCE_TICKS = 20; // wait 1 second after last dirty before processing

	private final ServerLodEngine engine;
	private final VoxyServerConfig config;
	private final ConcurrentHashMap<UUID, PlayerSyncSession> sessions = new ConcurrentHashMap<>();
	private final ExecutorService streamWorker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VoxyServer Stream Worker");
		t.setDaemon(true);
		return t;
	});
	private final ExecutorService genExecutor;
	private volatile ChunkVoxelizer chunkVoxelizer;
	private int tickCounter = 0;

	// Debounce: sectionKey -> (dimension, tick when last dirtied)
	private record PendingDirty(Identifier dimension, long lastDirtyTick) {}
	private final ConcurrentHashMap<Long, PendingDirty> pendingDirty = new ConcurrentHashMap<>();
	private volatile long currentTick = 0;

	public SyncService(ServerLodEngine engine, VoxyServerConfig config) {
		this.engine = engine;
		this.config = config;
		engine.setDirtySectionListener(this::onSectionDirty);
		this.genExecutor = Executors.newFixedThreadPool(
			Math.max(1, config.chunkGenConcurrency),
			r -> {
				Thread t = new Thread(r, "VoxyServer MerkleGen");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			}
		);
		VoxyServerMod.LOGGER.info("[Sync] SyncService created with lodStreamRadius={}, genConcurrency={}",
			config.lodStreamRadius, config.chunkGenConcurrency);
	}

	public void setChunkVoxelizer(ChunkVoxelizer voxelizer) {
		this.chunkVoxelizer = voxelizer;
	}

	public void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			VoxyServerMod.LOGGER.info("[Sync] Player connection joined: {}", handler.getPlayer().getName().getString());
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.getPlayer().getUUID();
			PlayerSyncSession session = sessions.remove(playerId);
			if (session != null) {
				session.reset();
				VoxyServerMod.LOGGER.info("[Sync] Player disconnected, session removed: {}", handler.getPlayer().getName().getString());
			}
		});

		//? if HAS_NEW_NETWORKING {
		ServerPlayNetworking.registerGlobalReceiver(MerkleReadyPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = context.server();
			VoxyServerMod.LOGGER.info("[Sync] Received MerkleReadyPayload from {}", player.getName().getString());
			server.execute(() -> onPlayerReady(player, server));
		});

		ServerPlayNetworking.registerGlobalReceiver(MerkleClientL1Payload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			VoxyServerMod.debug("[Sync] Received MerkleClientL1Payload from {} with {} entries",
				player.getName().getString(), payload.regionKeys().length);
			streamWorker.execute(() -> onClientL1Hashes(player, payload, context.server()));
		});
		//?} else {
		/*ServerPlayNetworking.registerGlobalReceiver(MerkleReadyPayload.TYPE, (packet, player, sender) -> {
			MinecraftServer server = player.getServer();
			VoxyServerMod.LOGGER.info("[Sync] Received MerkleReadyPayload from {}", player.getName().getString());
			server.execute(() -> onPlayerReady(player, server));
		});

		ServerPlayNetworking.registerGlobalReceiver(MerkleClientL1Payload.TYPE, (packet, player, sender) -> {
			VoxyServerMod.debug("[Sync] Received MerkleClientL1Payload from {} with {} entries",
				player.getName().getString(), packet.regionKeys().length);
			streamWorker.execute(() -> onClientL1Hashes(player, packet, player.getServer()));
		});
		*///?}

		VoxyServerMod.LOGGER.info("[Sync] Networking handlers registered");
	}

	private void onPlayerReady(ServerPlayer player, MinecraftServer server) {
		PlayerSyncSession session = new PlayerSyncSession(player);
		sessions.put(player.getUUID(), session);

		Identifier dimension = player.level().dimension().identifier();
		session.setCurrentDimension(dimension);

		ServerPlayNetworking.send(player, new MerkleSettingsPayload(
			config.lodStreamRadius, config.maxSectionsPerTickPerPlayer
		));

		streamWorker.execute(() -> {
			try {
				int sectionX = player.getBlockX() >> 5;
				int sectionZ = player.getBlockZ() >> 5;
				int radiusSections = config.lodStreamRadius;

				VoxyServerMod.debug("[Sync] Building Merkle tree for {} at section ({},{}) radius={}",
					player.getName().getString(), sectionX, sectionZ, radiusSections);

				session.buildTree(engine.getSectionHashStore(), sectionX, sectionZ, radiusSections);
				session.updatePosition(sectionX, sectionZ);

				PlayerMerkleTree tree = session.getTree();
				Long2LongOpenHashMap l2Hashes = tree.getL2Hashes();

				VoxyServerMod.LOGGER.info("[Sync] Tree built for {}: {} L2 regions, {} L0 sections",
					player.getName().getString(), l2Hashes.size(), tree.getAllL0SectionKeys().size());

				long[] regionKeys = new long[l2Hashes.size()];
				long[] regionHashValues = new long[l2Hashes.size()];
				int i = 0;
				for (var entry : l2Hashes.long2LongEntrySet()) {
					regionKeys[i] = entry.getLongKey();
					regionHashValues[i] = entry.getLongValue();
					i++;
				}

				server.execute(() -> {
					if (player.isRemoved()) return;
					VoxyServerMod.LOGGER.info("[Sync] Sending {} L2 hashes to {}", regionKeys.length, player.getName().getString());
					ServerPlayNetworking.send(player, new MerkleL2HashesPayload(dimension, regionKeys, regionHashValues));
					session.setState(PlayerSyncSession.State.L2_SENT);
				});
			} catch (Exception e) {
				VoxyServerMod.LOGGER.error("[Sync] Failed to build Merkle tree for {}", player.getName().getString(), e);
			}
		});
	}

	private void onClientL1Hashes(ServerPlayer player, MerkleClientL1Payload payload, MinecraftServer server) {
		PlayerSyncSession session = sessions.get(player.getUUID());
		if (session == null || session.getTree() == null) {
			VoxyServerMod.LOGGER.warn("[Sync] onClientL1Hashes: no session or tree for {}", player.getName().getString());
			return;
		}

		try {
			Long2ObjectOpenHashMap<Long2LongOpenHashMap> clientL1ByRegion = new Long2ObjectOpenHashMap<>();
			for (int i = 0; i < payload.regionKeys().length; i++) {
				long regionKey = payload.regionKeys()[i];
				long columnKey = payload.columnKeys()[i];
				long columnHash = payload.columnHashes()[i];

				Long2LongOpenHashMap regionMap = clientL1ByRegion.computeIfAbsent(regionKey, k -> {
					Long2LongOpenHashMap m = new Long2LongOpenHashMap();
					m.defaultReturnValue(0);
					return m;
				});
				regionMap.put(columnKey, columnHash);
			}

			PlayerMerkleTree.MerkleDiffResult diff = session.getTree().findDifferingL0Sections(clientL1ByRegion);

			VoxyServerMod.LOGGER.info("[Sync] Merkle diff for {}: {} sections to sync, {} columns to generate across {} regions",
				player.getName().getString(), diff.sectionsToSync().size(),
				diff.columnsToGenerate().size(), clientL1ByRegion.size());

			if (VoxyServerMod.isDebug()) {
				for (var regionEntry : clientL1ByRegion.long2ObjectEntrySet()) {
					long regionKey = regionEntry.getLongKey();
					Long2LongOpenHashMap clientL1 = regionEntry.getValue();
					Long2LongOpenHashMap serverL1 = session.getTree().getL1HashesForRegion(regionKey);

					for (var colEntry : serverL1.long2LongEntrySet()) {
						long colKey = colEntry.getLongKey();
						long serverHash = colEntry.getLongValue();
						long clientHash = clientL1.get(colKey);
						if (serverHash != clientHash) {
							int cx = MerkleHashUtil.columnKeyX(colKey);
							int cz = MerkleHashUtil.columnKeyZ(colKey);
							VoxyServerMod.debug("[Sync] Hash conflict for {}: column ({},{}) server={} client={}",
								player.getName().getString(), cx * 32, cz * 32, Long.toHexString(serverHash), Long.toHexString(clientHash));
						}
					}
				}
			}

			// Sync path: enqueue sections where server has data the client needs
			if (!diff.sectionsToSync().isEmpty()) {
				session.enqueueSections(diff.sectionsToSync());
				session.setState(PlayerSyncSession.State.SYNCING);
				VoxyServerMod.LOGGER.info("[Sync] Queued {} sections for sending to {}",
					diff.sectionsToSync().size(), player.getName().getString());
			}

			// Generation path: schedule chunk generation for incomplete sections
			if (!diff.columnsToGenerate().isEmpty() && chunkVoxelizer != null) {
				Identifier dimension = session.getCurrentDimension();
				ServerLevel level = findLevel(server, dimension);
				if (level != null) {
					int playerSectionX = player.getBlockX() >> 5;
					int playerSectionZ = player.getBlockZ() >> 5;
					scheduleGeneration(server, level, diff.columnsToGenerate(),
						session, playerSectionX, playerSectionZ);
				}
			}

			if (diff.sectionsToSync().isEmpty() && diff.columnsToGenerate().isEmpty()) {
				session.setState(PlayerSyncSession.State.IDLE);
				VoxyServerMod.LOGGER.info("[Sync] {} is fully synced", player.getName().getString());
			}
		} catch (Exception e) {
			VoxyServerMod.LOGGER.error("[Sync] Failed to process client L1 hashes for {}", player.getName().getString(), e);
		}
	}

	private void scheduleGeneration(MinecraftServer server, ServerLevel level,
			List<long[]> columns, PlayerSyncSession session,
			int playerSectionX, int playerSectionZ) {
		// Sort by distance from player (closest first)
		List<long[]> sorted = new ArrayList<>(columns);
		sorted.sort(Comparator.comparingLong(col -> {
			long dx = col[0] - playerSectionX;
			long dz = col[1] - playerSectionZ;
			return dx * dx + dz * dz;
		}));

		int scheduled = 0;
		for (long[] col : sorted) {
			int sectionX = (int) col[0];
			int sectionZ = (int) col[1];
			long columnKey = MerkleHashUtil.packColumnKey(sectionX, sectionZ);

			if (session.isGenerationPending(columnKey)) continue;

			List<ChunkPos> missingChunks = engine.getSectionHashStore()
				.getMissingChunksForSection(sectionX, sectionZ);
			if (missingChunks.isEmpty()) continue;

			session.markGenerationStarted(columnKey);
			scheduled++;

			for (ChunkPos pos : missingChunks) {
				genExecutor.execute(() -> {
					server.execute(() -> {
						try {
							ChunkAccess chunk = level.getChunkSource().getChunk(
								pos.x(), pos.z(), ChunkStatus.FULL, true);
							if (chunk instanceof LevelChunk lc) {
								chunkVoxelizer.voxelizeNewChunk(level, lc);
							}
						} catch (Exception e) {
							VoxyServerMod.LOGGER.warn("[MerkleGen] Failed to generate chunk ({},{}): {}",
								pos.x(), pos.z(), e.getMessage());
						}
					});
				});
			}
		}

		if (scheduled > 0) {
			VoxyServerMod.LOGGER.info("[MerkleGen] Scheduled generation for {} columns ({} were already pending) for {}",
				scheduled, columns.size() - scheduled, session.getPlayer().getName().getString());
		}
	}

	/**
	 * Called from dirty callback when a section changes.
	 * Debounces: records the dirty event and processes it after DIRTY_DEBOUNCE_TICKS
	 * of no further changes to the same section. This prevents hashing and pushing
	 * the same section dozens of times during initial chunk loading (each neighboring
	 * chunk partially fills the same WorldSection).
	 */
	private void onSectionDirty(Identifier dimension, long sectionKey) {
		pendingDirty.put(sectionKey, new PendingDirty(dimension, currentTick));
	}

	/**
	 * Process debounced dirty sections that have stabilized.
	 */
	private void processPendingDirty() {
		if (pendingDirty.isEmpty()) return;

		List<Long> ready = new ArrayList<>();
		for (var entry : pendingDirty.entrySet()) {
			if (currentTick - entry.getValue().lastDirtyTick >= DIRTY_DEBOUNCE_TICKS) {
				ready.add(entry.getKey());
			}
		}

		if (ready.isEmpty()) return;

		// Remove from pending and process on stream worker
		List<PendingDirty> toProcess = new ArrayList<>();
		for (long key : ready) {
			PendingDirty pd = pendingDirty.remove(key);
			if (pd != null) toProcess.add(new PendingDirty(pd.dimension(), key)); // reuse record, stash sectionKey in lastDirtyTick field
		}

		// Batch process on stream worker
		streamWorker.execute(() -> {
			for (int idx = 0; idx < ready.size(); idx++) {
				long sectionKey = ready.get(idx);
				Identifier dimension = toProcess.get(idx).dimension();
				processDirtySection(dimension, sectionKey);
			}
		});
	}

	private void processDirtySection(Identifier dimension, long sectionKey) {
		try {
			WorldEngine world = engine.getWorldEngineForDimension(dimension);
			if (world == null) return;

			WorldSection section = world.acquire(sectionKey);
			if (section == null) return;

			try {
				long newHash = SectionSerializer.computeSectionHash(section);
				long oldHash = engine.getSectionHashStore().getHash(sectionKey);
				engine.getSectionHashStore().putHash(sectionKey, newHash);

				// Skip if hash hasn't actually changed
				if (oldHash == newHash) return;

				int sx = WorldEngine.getX(sectionKey);
				int sz = WorldEngine.getZ(sectionKey);

				if (oldHash != 0) {
					VoxyServerMod.debug("[Sync] Hash changed: section ({},{}) old={} new={}",
						sx * 32, sz * 32, Long.toHexString(oldHash), Long.toHexString(newHash));
				}

				// Push to players in range
				for (PlayerSyncSession session : sessions.values()) {
					if (session.getTree() == null) continue;
					if (!dimension.equals(session.getCurrentDimension())) continue;

					if (session.isInRange(sx, sz)) {
						long playerOldHash = session.getTree().getL0Hash(sectionKey);
						session.getTree().onL0HashChanged(sectionKey, newHash);

						if (playerOldHash != newHash) {
							session.enqueueSection(sectionKey);
							if (session.getState() == PlayerSyncSession.State.IDLE) {
								session.setState(PlayerSyncSession.State.SYNCING);
							}
							if (playerOldHash != 0) {
								VoxyServerMod.debug("[Sync] Pushing update to {}: section ({},{}) old={} new={}",
									session.getPlayer().getName().getString(),
									sx * 32, sz * 32,
									Long.toHexString(playerOldHash), Long.toHexString(newHash));
							}
						}
					}
				}
			} finally {
				section.release();
			}
		} catch (Exception e) {
			VoxyServerMod.LOGGER.error("[Sync] Failed to process dirty section {}", WorldEngine.pprintPos(sectionKey), e);
		}
	}

	public void tick(MinecraftServer server) {
		tickCounter++;
		currentTick++;

		// Process debounced dirty sections
		processPendingDirty();

		// Periodically check player positions and rebuild trees if moved
		if (tickCounter % POSITION_CHECK_INTERVAL == 0) {
			for (PlayerSyncSession session : sessions.values()) {
				ServerPlayer player = session.getPlayer();
				if (player.isRemoved()) continue;

				int sectionX = player.getBlockX() >> 5;
				int sectionZ = player.getBlockZ() >> 5;

				if (session.hasMovedSignificantly(sectionX, sectionZ, TREE_REBUILD_DISTANCE)) {
					VoxyServerMod.LOGGER.info("[Sync] Player {} moved significantly, rebuilding tree at ({},{})",
						player.getName().getString(), sectionX, sectionZ);

					Identifier dimension = session.getCurrentDimension();
					streamWorker.execute(() -> {
						try {
							session.clearPendingGeneration();
							session.buildTree(engine.getSectionHashStore(), sectionX, sectionZ, config.lodStreamRadius);
							session.updatePosition(sectionX, sectionZ);

							PlayerMerkleTree tree = session.getTree();
							Long2LongOpenHashMap l2Hashes = tree.getL2Hashes();

							long[] regionKeys = new long[l2Hashes.size()];
							long[] regionHashValues = new long[l2Hashes.size()];
							int i = 0;
							for (var entry : l2Hashes.long2LongEntrySet()) {
								regionKeys[i] = entry.getLongKey();
								regionHashValues[i] = entry.getLongValue();
								i++;
							}

							server.execute(() -> {
								if (player.isRemoved()) return;
								ServerPlayNetworking.send(player, new MerkleL2HashesPayload(dimension, regionKeys, regionHashValues));
								session.setState(PlayerSyncSession.State.L2_SENT);
							});
						} catch (Exception e) {
							VoxyServerMod.LOGGER.error("[Sync] Failed to rebuild tree for {}", player.getName().getString(), e);
						}
					});
				}
			}
		}

		// Drain send queues
		for (PlayerSyncSession session : sessions.values()) {
			if (!session.hasPendingSections()) continue;

			ServerPlayer player = session.getPlayer();
			if (player.isRemoved()) continue;

			streamWorker.execute(() -> {
				try {
					long[] batch = session.pollBatch(config.sectionsPerPacket);
					if (batch == null) return;

					Identifier dimension = session.getCurrentDimension();
					ServerLevel level = findLevel(server, dimension);
					if (level == null) return;

					WorldEngine world = engine.getOrCreate(level);
					if (world == null) return;

					List<LODSectionPayload> sectionPayloads = new ArrayList<>();
					int skipped = 0;
					for (long sectionKey : batch) {
						WorldSection section = world.acquireIfExists(sectionKey);
						if (section == null) {
							section = world.acquire(sectionKey);
						}
						if (section == null) {
							skipped++;
							continue;
						}
						try {
							LODSectionPayload payload = SectionSerializer.serialize(
								section, world.getMapper(), dimension
							);
							sectionPayloads.add(payload);
						} finally {
							section.release();
						}
					}

					if (sectionPayloads.isEmpty()) {
						VoxyServerMod.debug("[Sync] Batch had 0 loadable sections (skipped {})", skipped);
						return;
					}

					VoxyServerMod.debug("[Sync] Sending {} sections to {} (skipped {})",
						sectionPayloads.size(), player.getName().getString(), skipped);

					LODBulkPayload bulk = new LODBulkPayload(dimension, sectionPayloads);
					//? if HAS_NEW_NETWORKING {
					PreSerializedLodPayload preserialized = PreSerializedLodPayload.fromBulk(
						bulk, server.registryAccess()
					);
					//?} else {
					/*PreSerializedLodPayload preserialized = PreSerializedLodPayload.fromBulk(bulk);
					*///?}

					// Compute affected L1 column hashes to send alongside section data.
					// The client can't compute these itself (different Mapper IDs),
					// so the server must tell it what hashes to store.
					LongOpenHashSet affectedColumns = new LongOpenHashSet();
					for (long sectionKey : batch) {
						int sx = WorldEngine.getX(sectionKey);
						int sz = WorldEngine.getZ(sectionKey);
						affectedColumns.add(MerkleHashUtil.packColumnKey(sx, sz));
					}

					PlayerMerkleTree tree = session.getTree();
					long[] colKeys = new long[affectedColumns.size()];
					long[] colHashes = new long[affectedColumns.size()];
					int hi = 0;
					if (tree != null) {
						for (long colKey : affectedColumns) {
							colKeys[hi] = colKey;
							colHashes[hi] = tree.getL1HashForColumn(colKey);
							hi++;
						}
					}
					final int hashCount = hi;
					final MerkleHashUpdatePayload hashUpdate = new MerkleHashUpdatePayload(
						dimension,
						java.util.Arrays.copyOf(colKeys, hashCount),
						java.util.Arrays.copyOf(colHashes, hashCount)
					);

					server.execute(() -> {
						if (!player.isRemoved()) {
							ServerPlayNetworking.send(player, preserialized);
							ServerPlayNetworking.send(player, hashUpdate);
						}
					});
				} catch (Exception e) {
					VoxyServerMod.LOGGER.error("[Sync] Failed to send section batch to {}", player.getName().getString(), e);
				}
			});
		}
	}

	public boolean hasSession(UUID playerId) {
		return sessions.containsKey(playerId);
	}

	public void onDimensionChange(ServerPlayer player, ServerLevel newLevel) {
		PlayerSyncSession session = sessions.get(player.getUUID());
		if (session == null) return;

		VoxyServerMod.LOGGER.info("[Sync] Player {} changed dimension to {}",
			player.getName().getString(), newLevel.dimension().identifier());

		ServerPlayNetworking.send(player, LODClearPayload.clearAll());
		session.reset();
		session.setCurrentDimension(newLevel.dimension().identifier());
		session.setState(PlayerSyncSession.State.AWAITING_READY);
	}

	public void shutdown() {
		VoxyServerMod.LOGGER.info("[Sync] Shutting down SyncService");
		genExecutor.shutdownNow();
		streamWorker.shutdownNow();
		try {
			genExecutor.awaitTermination(5, TimeUnit.SECONDS);
			streamWorker.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		sessions.clear();
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
