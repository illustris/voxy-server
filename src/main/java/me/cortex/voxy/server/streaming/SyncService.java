package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.engine.ServerLodEngine;
import me.cortex.voxy.server.merkle.MerkleHashUtil;
import me.cortex.voxy.server.merkle.PlayerMerkleTree;
import me.cortex.voxy.server.merkle.SectionHashStore;
import me.cortex.voxy.server.network.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import me.cortex.voxy.commonImpl.WorldIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates per-player Merkle sync: builds trees, handles hash exchange, streams sections.
 */
public class SyncService {
	private final ServerLodEngine engine;
	private final VoxyServerConfig config;
	private final ConcurrentHashMap<UUID, PlayerSyncSession> sessions = new ConcurrentHashMap<>();
	private final ExecutorService streamWorker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VoxyServer Stream Worker");
		t.setDaemon(true);
		return t;
	});

	public SyncService(ServerLodEngine engine, VoxyServerConfig config) {
		this.engine = engine;
		this.config = config;

		// Hook into the dirty callback to update player trees and push sections
		engine.setDirtySectionListener(this::onSectionDirty);
	}

	public void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			// Session will be created when client sends MerkleReadyPayload
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.getPlayer().getUUID();
			PlayerSyncSession session = sessions.remove(playerId);
			if (session != null) {
				session.reset();
			}
		});

		// Handle MerkleReadyPayload from client
		ServerPlayNetworking.registerGlobalReceiver(MerkleReadyPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = context.server();
			server.execute(() -> onPlayerReady(player, server));
		});

		// Handle MerkleClientL1Payload from client
		ServerPlayNetworking.registerGlobalReceiver(MerkleClientL1Payload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			streamWorker.execute(() -> onClientL1Hashes(player, payload));
		});
	}

	private void onPlayerReady(ServerPlayer player, MinecraftServer server) {
		PlayerSyncSession session = new PlayerSyncSession(player);
		sessions.put(player.getUUID(), session);

		Identifier dimension = player.level().dimension().identifier();
		session.setCurrentDimension(dimension);

		// Send settings
		ServerPlayNetworking.send(player, new MerkleSettingsPayload(
			config.lodStreamRadius,
			config.maxSectionsPerTickPerPlayer
		));

		// Build tree on stream worker thread
		streamWorker.execute(() -> {
			try {
				int sectionX = player.getBlockX() >> 5; // 32 blocks per section
				int sectionZ = player.getBlockZ() >> 5;
				int radiusSections = config.lodStreamRadius; // radius in section coords

				session.buildTree(engine.getSectionHashStore(), sectionX, sectionZ, radiusSections);

				// Send L2 hashes to client
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
					ServerPlayNetworking.send(player, new MerkleL2HashesPayload(
						dimension, regionKeys, regionHashValues
					));
					session.setState(PlayerSyncSession.State.L2_SENT);
				});
			} catch (Exception e) {
				VoxyServerMod.LOGGER.error("Failed to build Merkle tree for player {}", player.getName().getString(), e);
			}
		});
	}

	private void onClientL1Hashes(ServerPlayer player, MerkleClientL1Payload payload) {
		PlayerSyncSession session = sessions.get(player.getUUID());
		if (session == null || session.getTree() == null) return;

		try {
			// Reorganize client L1 hashes by region
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

			// Find differing L0 sections
			List<Long> differing = session.getTree().findDifferingL0Sections(clientL1ByRegion);

			if (differing.isEmpty()) {
				session.setState(PlayerSyncSession.State.IDLE);
				return;
			}

			// Queue sections for sending
			session.enqueueSections(differing);
			session.setState(PlayerSyncSession.State.SYNCING);
		} catch (Exception e) {
			VoxyServerMod.LOGGER.error("Failed to process client L1 hashes for player {}", player.getName().getString(), e);
		}
	}

	/**
	 * Called from dirty callback when a section changes.
	 */
	private void onSectionDirty(Identifier dimension, long sectionKey) {
		// Compute and store the new L0 hash
		streamWorker.execute(() -> {
			try {
				WorldIdentifier worldId = null;
				// Find the WorldIdentifier for this dimension
				for (var entry : sessions.values()) {
					// Use engine to find the world
				}

				// Update the hash in the store
				// (The hash will be computed when the section is next serialized)

				// For each online player in range, push the update
				for (PlayerSyncSession session : sessions.values()) {
					if (session.getState() == PlayerSyncSession.State.IDLE
						|| session.getState() == PlayerSyncSession.State.SYNCING) {
						if (dimension.equals(session.getCurrentDimension()) && session.isInRange(sectionKey)) {
							session.enqueueSection(sectionKey);
							if (session.getState() == PlayerSyncSession.State.IDLE) {
								session.setState(PlayerSyncSession.State.SYNCING);
							}
						}
					}
				}
			} catch (Exception e) {
				VoxyServerMod.LOGGER.error("Failed to handle dirty section", e);
			}
		});
	}

	/**
	 * Called from the server tick to drain send queues and send batches.
	 */
	public void tick(MinecraftServer server) {
		for (PlayerSyncSession session : sessions.values()) {
			if (!session.hasPendingSections()) continue;

			ServerPlayer player = session.getPlayer();
			if (player.isRemoved()) continue;

			// Process on stream worker to avoid tick thread serialization
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
					for (long sectionKey : batch) {
						WorldSection section = world.acquireIfExists(sectionKey);
						if (section == null) continue;
						try {
							LODSectionPayload payload = SectionSerializer.serialize(
								section, world.getMapper(), dimension
							);
							sectionPayloads.add(payload);
						} finally {
							section.release();
						}
					}

					if (sectionPayloads.isEmpty()) return;

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
					VoxyServerMod.LOGGER.error("Failed to send section batch", e);
				}
			});
		}
	}

	public void onDimensionChange(ServerPlayer player, ServerLevel newLevel) {
		PlayerSyncSession session = sessions.get(player.getUUID());
		if (session == null) return;

		// Send clear payload
		ServerPlayNetworking.send(player, LODClearPayload.clearAll());

		// Reset session and rebuild for new dimension
		session.reset();
		session.setCurrentDimension(newLevel.dimension().identifier());
		session.setState(PlayerSyncSession.State.AWAITING_READY);
	}

	public void shutdown() {
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
