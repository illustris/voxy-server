package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.engine.ServerLodEngine;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SyncService {
	private static final int TREE_REBUILD_DISTANCE = 64;
	private static final int POSITION_CHECK_INTERVAL = 100;

	private final ServerLodEngine engine;
	private final VoxyServerConfig config;
	private final ConcurrentHashMap<UUID, PlayerSyncSession> sessions = new ConcurrentHashMap<>();
	private final ExecutorService streamWorker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VoxyServer Stream Worker");
		t.setDaemon(true);
		return t;
	});
	private int tickCounter = 0;

	public SyncService(ServerLodEngine engine, VoxyServerConfig config) {
		this.engine = engine;
		this.config = config;
		engine.setDirtySectionListener(this::onSectionDirty);
		VoxyServerMod.LOGGER.info("[Sync] SyncService created with lodStreamRadius={}", config.lodStreamRadius);
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

			List<Long> differing = session.getTree().findDifferingL0Sections(clientL1ByRegion);

			VoxyServerMod.LOGGER.info("[Sync] Merkle diff for {}: {} differing L0 sections across {} regions",
				player.getName().getString(), differing.size(), clientL1ByRegion.size());

			if (VoxyServerMod.isDebug()) {
				// Log each differing column with coordinates
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

			if (differing.isEmpty()) {
				session.setState(PlayerSyncSession.State.IDLE);
				VoxyServerMod.LOGGER.info("[Sync] {} is fully synced", player.getName().getString());
				return;
			}

			session.enqueueSections(differing);
			session.setState(PlayerSyncSession.State.SYNCING);
			VoxyServerMod.LOGGER.info("[Sync] Queued {} sections for sending to {}",
				differing.size(), player.getName().getString());
		} catch (Exception e) {
			VoxyServerMod.LOGGER.error("[Sync] Failed to process client L1 hashes for {}", player.getName().getString(), e);
		}
	}

	/**
	 * Called from dirty callback when a section changes.
	 * Computes and stores the L0 hash, then notifies players.
	 */
	private void onSectionDirty(Identifier dimension, long sectionKey) {
		streamWorker.execute(() -> {
			try {
				WorldEngine world = engine.getWorldEngineForDimension(dimension);
				if (world == null) {
					VoxyServerMod.LOGGER.warn("[Sync] onSectionDirty: no WorldEngine for dimension {}", dimension);
					return;
				}

				WorldSection section = world.acquire(sectionKey);
				if (section != null) {
					try {
						long newHash = SectionSerializer.computeSectionHash(section);
						long oldHash = engine.getSectionHashStore().getHash(sectionKey);
						engine.getSectionHashStore().putHash(sectionKey, newHash);

						if (oldHash == 0) {
							// Newly generated section, not a conflict
							VoxyServerMod.debug("[Sync] New section hash: {} = {}",
								WorldEngine.pprintPos(sectionKey), Long.toHexString(newHash));
						} else if (oldHash != newHash) {
							// Hash changed -- this is a real update (block change)
							int sx = WorldEngine.getX(sectionKey);
							int sz = WorldEngine.getZ(sectionKey);
							VoxyServerMod.debug("[Sync] Hash conflict (block update): section ({},{}) old={} new={}",
								sx * 32, sz * 32, Long.toHexString(oldHash), Long.toHexString(newHash));
						}

						// Push to players in range
						for (PlayerSyncSession session : sessions.values()) {
							if (session.getTree() == null) continue;
							if (!dimension.equals(session.getCurrentDimension())) continue;

							int sx = WorldEngine.getX(sectionKey);
							int sz = WorldEngine.getZ(sectionKey);
							if (session.isInRange(sx, sz)) {
								long playerOldHash = session.getTree().getL0Hash(sectionKey);
								session.getTree().onL0HashChanged(sectionKey, newHash);

								// Only push if this is a real change from the player's perspective
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
				}
			} catch (Exception e) {
				VoxyServerMod.LOGGER.error("[Sync] Failed to handle dirty section {}", WorldEngine.pprintPos(sectionKey), e);
			}
		});
	}

	public void tick(MinecraftServer server) {
		tickCounter++;

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
					PreSerializedLodPayload preserialized = PreSerializedLodPayload.fromBulk(
						bulk, server.registryAccess()
					);

					server.execute(() -> {
						if (!player.isRemoved()) {
							ServerPlayNetworking.send(player, preserialized);
						}
					});
				} catch (Exception e) {
					VoxyServerMod.LOGGER.error("[Sync] Failed to send section batch to {}", player.getName().getString(), e);
				}
			});
		}
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
		streamWorker.shutdownNow();
		try {
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
