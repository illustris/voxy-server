package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.mixin.ChunkMapAccessor;
import me.cortex.voxy.server.mixin.TrackedEntityAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extends vanilla entity tracking to LOD radius. Each tick we scan entities
 * inside the LOD radius (but outside vanilla view distance), build a per-player
 * force-track set, and proactively call ServerEntity.addPairing so the player
 * gets full vanilla entity packets (spawn + SynchedEntityData + per-tick deltas).
 * The ChunkMap$TrackedEntity mixin keeps these entities tracked even though
 * vanilla's distance/chunk checks would otherwise drop them.
 *
 * No "custom" transport mode -- all entities ride vanilla's tracker so mods
 * that piggyback on the spawn / SynchedEntityData stream (Create contraptions,
 * Aeronautics assemblies, etc.) get their full state on the wire.
 */
public class EntitySyncService {
	private final VoxyServerConfig config;
	private final SyncService syncService;
	private final int lodRadiusBlocks;
	private int tickCounter = 0;

	// Per-player set of entity IDs to force-track via vanilla
	private final ConcurrentHashMap<UUID, IntOpenHashSet> forceTrackSets = new ConcurrentHashMap<>();

	public EntitySyncService(VoxyServerConfig config, SyncService syncService) {
		this.config = config;
		this.syncService = syncService;
		this.lodRadiusBlocks = config.lodStreamRadius * 32;
		VoxyServerMod.LOGGER.info("[EntitySync] Initialized: mode={} interval={}t radius={}b maxPerPlayer={}",
			config.entitySyncMode, config.entitySyncIntervalTicks,
			lodRadiusBlocks, config.maxLODEntitiesPerPlayer);
	}

	/**
	 * Called from the ChunkMap$TrackedEntity mixin to check if an entity should
	 * be force-tracked for a given player. The mixin uses this to cancel
	 * vanilla's distance-based removal logic.
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
			syncEntitiesFor(player, level, vanillaRadiusBlocks);
		}

		// Clean up disconnected players
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getUUID());
		forceTrackSets.keySet().removeIf(id -> !online.contains(id));
	}

	/**
	 * Scan entities inside the LOD radius (but outside vanilla view distance),
	 * filter, sort players-first then by distance, cap at maxLODEntitiesPerPlayer,
	 * and rebuild this observer's force-track set + proactively call addPairing
	 * on any newly-tracked entities so vanilla streams them right away (we can't
	 * wait for the next chunk crossing -- a stationary observer would never
	 * trigger updatePlayer).
	 *
	 * The mixin then prevents vanilla from *removing* these entities when its
	 * own distance/chunk checks would otherwise drop them.
	 */
	private void syncEntitiesFor(ServerPlayer observer, ServerLevel level, int vanillaRadiusBlocks) {
		double ox = observer.getX();
		double oz = observer.getZ();
		double vanillaRadiusSq = (double) vanillaRadiusBlocks * vanillaRadiusBlocks;

		List<Candidate> candidates = new ArrayList<>();
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
			candidates.add(new Candidate(entity.getId(), entity instanceof Player, distSq));
		}

		// Sort players first, then by distance, then cap.
		candidates.sort((a, b) -> {
			if (a.isPlayer != b.isPlayer) return a.isPlayer ? -1 : 1;
			return Double.compare(a.distSq, b.distSq);
		});
		if (candidates.size() > config.maxLODEntitiesPerPlayer) {
			candidates = candidates.subList(0, config.maxLODEntitiesPerPlayer);
		}

		IntOpenHashSet newSet = new IntOpenHashSet(candidates.size());
		for (Candidate c : candidates) newSet.add(c.entityId);
		forceTrackSets.put(observer.getUUID(), newSet);

		// Proactively pair via ChunkMap internals.
		var chunkMap = level.getChunkSource().chunkMap;
		Int2ObjectMap<?> entityMap = ((ChunkMapAccessor) chunkMap).voxy$getEntityMap();
		int added = 0;
		for (Candidate c : candidates) {
			Object tracked = entityMap.get(c.entityId);
			if (tracked instanceof TrackedEntityAccessor tea) {
				Set<ServerPlayerConnection> seenBy = tea.voxy$getSeenBy();
				if (seenBy.add(observer.connection)) {
					tea.voxy$getServerEntity().addPairing(observer);
					added++;
				}
			}
		}

		VoxyServerMod.debug("[EntitySync] Force-track for {}: {} entities ({} newly paired)",
			observer.getName().getString(), newSet.size(), added);
	}

	private boolean matchesFilter(Entity entity) {
		return switch (config.entitySyncMode) {
			case "players_only" -> entity instanceof Player;
			case "all" -> true;
			// "non_trivial": include living, custom mod entities (Create
			// contraptions, Aeronautics assemblies), boats, minecarts, falling
			// blocks, primed TNT, armor stands, etc. Exclude high-frequency /
			// low-value clutter that would saturate maxLODEntitiesPerPlayer
			// (item drops, XP orbs, projectiles in flight, lingering clouds).
			case "non_trivial" -> !(entity instanceof ItemEntity
				|| entity instanceof ExperienceOrb
				|| entity instanceof Projectile
				|| entity instanceof AreaEffectCloud);
			default -> entity instanceof LivingEntity; // "living"
		};
	}

	public void onPlayerDisconnect(UUID playerId, MinecraftServer server) {
		forceTrackSets.remove(playerId);
		// Vanilla handles entity removal when the player entity leaves the
		// level (ChunkMap.removeEntity -> broadcastRemoved); nothing else to
		// clean up.
	}

	public void onDimensionChange(ServerPlayer player) {
		forceTrackSets.remove(player.getUUID());
	}

	public void shutdown() {
		forceTrackSets.clear();
		VoxyServerMod.LOGGER.info("[EntitySync] Shut down");
	}

	private record Candidate(int entityId, boolean isPlayer, double distSq) {}
}
