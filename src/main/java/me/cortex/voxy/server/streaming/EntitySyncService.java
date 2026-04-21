package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.network.LODEntityRemovePayload;
import me.cortex.voxy.server.network.LODEntityUpdatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Syncs entity positions to clients for entities within the LOD radius
 * but outside vanilla's entity tracking range. Runs independently of
 * the Merkle-based terrain sync.
 */
public class EntitySyncService {
	private final VoxyServerConfig config;
	private final SyncService syncService;
	private final ConcurrentHashMap<UUID, PlayerEntityTracker> trackers = new ConcurrentHashMap<>();
	private final int lodRadiusBlocks;
	private int tickCounter = 0;

	public EntitySyncService(VoxyServerConfig config, SyncService syncService) {
		this.config = config;
		this.syncService = syncService;
		this.lodRadiusBlocks = config.lodStreamRadius * 32;
		VoxyServerMod.LOGGER.info("[EntitySync] Initialized: mode={} interval={}t radius={}b maxPerPlayer={}",
			config.entitySyncMode, config.entitySyncIntervalTicks, lodRadiusBlocks, config.maxLODEntitiesPerPlayer);
	}

	public void tick(MinecraftServer server) {
		if (++tickCounter < config.entitySyncIntervalTicks) return;
		tickCounter = 0;

		int vanillaRadiusBlocks = server.getPlayerList().getViewDistance() * 16;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!syncService.hasSession(player.getUUID())) continue;

			PlayerEntityTracker tracker = trackers.computeIfAbsent(
				player.getUUID(), k -> new PlayerEntityTracker()
			);

			ServerLevel level = (ServerLevel) player.level();
			syncEntitiesForPlayer(player, level, tracker, vanillaRadiusBlocks);
		}

		// Clean up disconnected players
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getUUID());
		trackers.keySet().removeIf(id -> !online.contains(id));
	}

	private void syncEntitiesForPlayer(ServerPlayer observer, ServerLevel level,
										PlayerEntityTracker tracker, int vanillaRadiusBlocks) {
		double ox = observer.getX();
		double oz = observer.getZ();
		double lodRadiusSq = (double) lodRadiusBlocks * lodRadiusBlocks;
		double vanillaRadiusSq = (double) vanillaRadiusBlocks * vanillaRadiusBlocks;

		// Scan entities within LOD radius using AABB query
		List<CandidateEntity> candidates = new ArrayList<>();
		AABB scanArea = new AABB(
			ox - lodRadiusBlocks, level.getMinY(), oz - lodRadiusBlocks,
			ox + lodRadiusBlocks, level.getMaxY(), oz + lodRadiusBlocks
		);

		for (Entity entity : level.getEntities(EntityTypeTest.forClass(Entity.class), scanArea, e -> e != observer)) {
			if (!matchesFilter(entity)) continue;

			double dx = entity.getX() - ox;
			double dz = entity.getZ() - oz;
			double distSq = dx * dx + dz * dz;

			// Must be outside vanilla tracking range
			if (distSq <= vanillaRadiusSq) continue;

			Identifier entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
			boolean isPlayer = entity instanceof Player;
			candidates.add(new CandidateEntity(
				entity.getId(), entityType,
				entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
				(byte) (entity.getYRot() * 256.0f / 360.0f),
				entity.getUUID(), isPlayer, distSq
			));
		}

		// Sort: players first, then by distance
		candidates.sort((a, b) -> {
			if (a.isPlayer != b.isPlayer) return a.isPlayer ? -1 : 1;
			return Double.compare(a.distSq, b.distSq);
		});

		// Cap
		if (candidates.size() > config.maxLODEntitiesPerPlayer) {
			candidates = candidates.subList(0, config.maxLODEntitiesPerPlayer);
		}

		// Build current snapshot
		Int2ObjectOpenHashMap<PlayerEntityTracker.EntitySnapshot> current = new Int2ObjectOpenHashMap<>();
		for (CandidateEntity c : candidates) {
			current.put(c.entityId, new PlayerEntityTracker.EntitySnapshot(
				c.entityType, c.blockX, c.blockY, c.blockZ, c.yaw, c.uuid
			));
		}

		// Diff against last sent
		Int2ObjectOpenHashMap<PlayerEntityTracker.EntitySnapshot> lastSent = tracker.getLastSent();

		// Find updates (new or moved)
		List<Integer> updateIds = new ArrayList<>();
		for (var entry : current.int2ObjectEntrySet()) {
			int id = entry.getIntKey();
			PlayerEntityTracker.EntitySnapshot snap = entry.getValue();
			PlayerEntityTracker.EntitySnapshot prev = lastSent.get(id);
			if (prev == null || prev.blockX() != snap.blockX() ||
				prev.blockY() != snap.blockY() || prev.blockZ() != snap.blockZ() ||
				prev.yaw() != snap.yaw()) {
				updateIds.add(id);
			}
		}

		// Find removals
		IntArrayList removeIds = new IntArrayList();
		for (int id : lastSent.keySet()) {
			if (!current.containsKey(id)) {
				removeIds.add(id);
			}
		}

		// Send updates
		if (!updateIds.isEmpty()) {
			sendUpdates(observer, level.dimension().identifier(), current, updateIds);
		}

		// Send removals
		if (!removeIds.isEmpty()) {
			ServerPlayNetworking.send(observer, new LODEntityRemovePayload(removeIds.toIntArray()));
			VoxyServerMod.debug("[EntitySync] Sent {} removals to {}",
				removeIds.size(), observer.getName().getString());
		}

		tracker.setLastSent(current);
	}

	private void sendUpdates(ServerPlayer observer, Identifier dimension,
							  Int2ObjectOpenHashMap<PlayerEntityTracker.EntitySnapshot> snapshot,
							  List<Integer> ids) {
		int count = ids.size();
		int[] entityIds = new int[count];
		Identifier[] entityTypes = new Identifier[count];
		int[] blockX = new int[count];
		int[] blockY = new int[count];
		int[] blockZ = new int[count];
		byte[] yaw = new byte[count];
		long[] uuidMost = new long[count];
		long[] uuidLeast = new long[count];

		for (int i = 0; i < count; i++) {
			int id = ids.get(i);
			entityIds[i] = id;
			PlayerEntityTracker.EntitySnapshot snap = snapshot.get(id);
			entityTypes[i] = snap.entityType();
			blockX[i] = snap.blockX();
			blockY[i] = snap.blockY();
			blockZ[i] = snap.blockZ();
			yaw[i] = snap.yaw();
			uuidMost[i] = snap.uuid().getMostSignificantBits();
			uuidLeast[i] = snap.uuid().getLeastSignificantBits();
		}

		ServerPlayNetworking.send(observer, new LODEntityUpdatePayload(
			dimension, entityIds, entityTypes, blockX, blockY, blockZ, yaw, uuidMost, uuidLeast
		));
		VoxyServerMod.debug("[EntitySync] Sent {} updates to {}",
			count, observer.getName().getString());
	}

	private boolean matchesFilter(Entity entity) {
		return switch (config.entitySyncMode) {
			case "players_only" -> entity instanceof Player;
			case "all" -> true;
			default -> entity instanceof LivingEntity; // "living"
		};
	}

	public void onPlayerDisconnect(UUID playerId) {
		trackers.remove(playerId);
	}

	public void onDimensionChange(ServerPlayer player) {
		PlayerEntityTracker tracker = trackers.get(player.getUUID());
		if (tracker != null) {
			// Send remove for all currently tracked entities
			Int2ObjectOpenHashMap<PlayerEntityTracker.EntitySnapshot> lastSent = tracker.getLastSent();
			if (!lastSent.isEmpty()) {
				ServerPlayNetworking.send(player,
					new LODEntityRemovePayload(lastSent.keySet().toIntArray()));
			}
			tracker.reset();
		}
	}

	public void shutdown() {
		trackers.clear();
		VoxyServerMod.LOGGER.info("[EntitySync] Shut down");
	}

	private record CandidateEntity(
		int entityId, Identifier entityType,
		int blockX, int blockY, int blockZ, byte yaw,
		UUID uuid, boolean isPlayer, double distSq
	) {}
}
