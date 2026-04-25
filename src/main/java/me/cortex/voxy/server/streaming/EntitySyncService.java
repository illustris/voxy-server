package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.mixin.ChunkMapAccessor;
import me.cortex.voxy.server.mixin.TrackedEntityAccessor;
import me.cortex.voxy.server.network.LODEntityRemovePayload;
import me.cortex.voxy.server.network.LODEntityUpdatePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
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
 *
 * Supports two transport modes:
 * - "custom": sends lightweight LOD entity packets (block precision, periodic)
 * - "native": maintains a force-track set so a ChunkMap mixin can extend
 *   vanilla's entity tracking to LOD range (full models/animations)
 */
public class EntitySyncService {
	private final VoxyServerConfig config;
	private final SyncService syncService;
	private final boolean nativeMode;
	private final int lodRadiusBlocks;
	private int tickCounter = 0;

	// Custom mode state: per-player snapshot tracking for delta packets
	private final ConcurrentHashMap<UUID, PlayerEntityTracker> trackers = new ConcurrentHashMap<>();

	// Native mode state: per-player set of entity IDs to force-track via vanilla
	private final ConcurrentHashMap<UUID, IntOpenHashSet> forceTrackSets = new ConcurrentHashMap<>();

	public EntitySyncService(VoxyServerConfig config, SyncService syncService) {
		this.config = config;
		this.syncService = syncService;
		this.nativeMode = "native".equals(config.entitySyncTransport);
		this.lodRadiusBlocks = config.lodStreamRadius * 32;
		VoxyServerMod.LOGGER.info("[EntitySync] Initialized: transport={} mode={} interval={}t radius={}b maxPerPlayer={}",
			config.entitySyncTransport, config.entitySyncMode, config.entitySyncIntervalTicks,
			lodRadiusBlocks, config.maxLODEntitiesPerPlayer);
	}

	public boolean isNativeMode() {
		return nativeMode;
	}

	/**
	 * Called from the ChunkMap$TrackedEntity mixin to check if an entity
	 * should be force-tracked for a given player in native transport mode.
	 */
	public boolean shouldForceTrack(UUID playerId, int entityId) {
		IntOpenHashSet set = forceTrackSets.get(playerId);
		return set != null && set.contains(entityId);
	}

	public void tick(MinecraftServer server) {
		if (++tickCounter < config.entitySyncIntervalTicks) return;
		tickCounter = 0;

		int vanillaRadiusBlocks = server.getPlayerList().getViewDistance() * 16;

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!syncService.hasSession(player.getUUID())) continue;

			ServerLevel level = (ServerLevel) player.level();

			if (nativeMode) {
				syncEntitiesNative(player, level, vanillaRadiusBlocks);
			} else {
				PlayerEntityTracker tracker = trackers.computeIfAbsent(
					player.getUUID(), k -> new PlayerEntityTracker()
				);
				syncEntitiesCustom(player, level, tracker, vanillaRadiusBlocks);
			}
		}

		// Clean up disconnected players
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getUUID());
		trackers.keySet().removeIf(id -> !online.contains(id));
		forceTrackSets.keySet().removeIf(id -> !online.contains(id));
	}

	/**
	 * Scans entities in LOD radius and returns the filtered, sorted, capped candidate list.
	 */
	private List<CandidateEntity> scanCandidates(ServerPlayer observer, ServerLevel level, int vanillaRadiusBlocks) {
		double ox = observer.getX();
		double oz = observer.getZ();
		double lodRadiusSq = (double) lodRadiusBlocks * lodRadiusBlocks;
		double vanillaRadiusSq = (double) vanillaRadiusBlocks * vanillaRadiusBlocks;

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

			if (distSq <= vanillaRadiusSq) continue;

			Identifier entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
			boolean isPlayer = entity instanceof Player;
			float headYaw = entity instanceof LivingEntity le ? le.getYHeadRot() : entity.getYRot();
			candidates.add(new CandidateEntity(
				entity.getId(), entityType,
				entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
				(byte) (entity.getYRot() * 256.0f / 360.0f),
				(byte) (entity.getXRot() * 256.0f / 360.0f),
				(byte) (headYaw * 256.0f / 360.0f),
				entity.getUUID(), isPlayer, distSq
			));
		}

		candidates.sort((a, b) -> {
			if (a.isPlayer != b.isPlayer) return a.isPlayer ? -1 : 1;
			return Double.compare(a.distSq, b.distSq);
		});

		if (candidates.size() > config.maxLODEntitiesPerPlayer) {
			candidates = candidates.subList(0, config.maxLODEntitiesPerPlayer);
		}

		return candidates;
	}

	/**
	 * Native mode: rebuild the force-track set and proactively add entities to
	 * vanilla's tracking. We cannot rely on vanilla calling updatePlayer() --
	 * that only happens on player/entity section crossings, so a stationary
	 * observer might wait indefinitely for a far entity to appear. Instead we
	 * directly add the observer to each entity's seenBy set and call addPairing
	 * so that vanilla's ServerEntity.sendChanges() streams position packets
	 * immediately.
	 *
	 * The ChunkMap$TrackedEntity mixin still prevents vanilla from *removing*
	 * force-tracked entities when its own distance/chunk checks fail.
	 */
	private void syncEntitiesNative(ServerPlayer observer, ServerLevel level, int vanillaRadiusBlocks) {
		List<CandidateEntity> candidates = scanCandidates(observer, level, vanillaRadiusBlocks);

		IntOpenHashSet newSet = new IntOpenHashSet(candidates.size());
		for (CandidateEntity c : candidates) {
			newSet.add(c.entityId);
		}

		forceTrackSets.put(observer.getUUID(), newSet);

		// Proactively add tracking via ChunkMap internals
		var chunkMap = level.getChunkSource().chunkMap;
		Int2ObjectMap<?> entityMap = ((ChunkMapAccessor) chunkMap).voxy$getEntityMap();

		int added = 0;
		for (CandidateEntity c : candidates) {
			Object tracked = entityMap.get(c.entityId);
			if (tracked instanceof TrackedEntityAccessor tea) {
				Set<ServerPlayerConnection> seenBy = tea.voxy$getSeenBy();
				if (seenBy.add(observer.connection)) {
					tea.voxy$getServerEntity().addPairing(observer);
					added++;
				}
			}
		}

		VoxyServerMod.debug("[EntitySync] Native force-track for {}: {} entities ({} newly paired)",
			observer.getName().getString(), newSet.size(), added);
	}

	/**
	 * Custom mode: send LOD entity update/remove packets (original behavior).
	 */
	private void syncEntitiesCustom(ServerPlayer observer, ServerLevel level,
									 PlayerEntityTracker tracker, int vanillaRadiusBlocks) {
		List<CandidateEntity> candidates = scanCandidates(observer, level, vanillaRadiusBlocks);

		// Build current snapshot
		Int2ObjectOpenHashMap<PlayerEntityTracker.EntitySnapshot> current = new Int2ObjectOpenHashMap<>();
		for (CandidateEntity c : candidates) {
			current.put(c.entityId, new PlayerEntityTracker.EntitySnapshot(
				c.entityType, c.blockX, c.blockY, c.blockZ, c.yaw, c.pitch, c.headYaw, c.uuid
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
				prev.yaw() != snap.yaw() || prev.pitch() != snap.pitch() ||
				prev.headYaw() != snap.headYaw()) {
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
		byte[] pitch = new byte[count];
		byte[] headYaw = new byte[count];
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
			pitch[i] = snap.pitch();
			headYaw[i] = snap.headYaw();
			uuidMost[i] = snap.uuid().getMostSignificantBits();
			uuidLeast[i] = snap.uuid().getLeastSignificantBits();
		}

		ServerPlayNetworking.send(observer, new LODEntityUpdatePayload(
			dimension, entityIds, entityTypes, blockX, blockY, blockZ,
			yaw, pitch, headYaw, uuidMost, uuidLeast
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

	public void onPlayerDisconnect(UUID playerId, MinecraftServer server) {
		trackers.remove(playerId);
		forceTrackSets.remove(playerId);

		if (!nativeMode) {
			// Custom mode: proactively remove the disconnected player's entity from
			// all other players' trackers and send removal packets immediately.
			for (var entry : trackers.entrySet()) {
				UUID observerId = entry.getKey();
				PlayerEntityTracker tracker = entry.getValue();
				Int2ObjectOpenHashMap<PlayerEntityTracker.EntitySnapshot> lastSent = tracker.getLastSent();

				IntArrayList removeIds = new IntArrayList();
				for (var snapEntry : lastSent.int2ObjectEntrySet()) {
					if (playerId.equals(snapEntry.getValue().uuid())) {
						removeIds.add(snapEntry.getIntKey());
					}
				}

				if (!removeIds.isEmpty()) {
					for (int id : removeIds) {
						lastSent.remove(id);
					}

					ServerPlayer observer = server.getPlayerList().getPlayer(observerId);
					if (observer != null) {
						ServerPlayNetworking.send(observer, new LODEntityRemovePayload(removeIds.toIntArray()));
						VoxyServerMod.debug("[EntitySync] Sent {} disconnect removals for {} to {}",
							removeIds.size(), playerId, observer.getName().getString());
					}
				}
			}
		}
		// Native mode: vanilla handles entity removal when the player entity is
		// removed from the level -- ChunkMap.removeEntity -> broadcastRemoved
	}

	public void onDimensionChange(ServerPlayer player) {
		forceTrackSets.remove(player.getUUID());

		PlayerEntityTracker tracker = trackers.get(player.getUUID());
		if (tracker != null) {
			if (!nativeMode) {
				Int2ObjectOpenHashMap<PlayerEntityTracker.EntitySnapshot> lastSent = tracker.getLastSent();
				if (!lastSent.isEmpty()) {
					ServerPlayNetworking.send(player,
						new LODEntityRemovePayload(lastSent.keySet().toIntArray()));
				}
			}
			tracker.reset();
		}
	}

	public void shutdown() {
		trackers.clear();
		forceTrackSets.clear();
		VoxyServerMod.LOGGER.info("[EntitySync] Shut down");
	}

	private record CandidateEntity(
		int entityId, Identifier entityType,
		int blockX, int blockY, int blockZ,
		byte yaw, byte pitch, byte headYaw,
		UUID uuid, boolean isPlayer, double distSq
	) {}
}
