package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SyncService {
	// Player needs to drift this far (in level-0 sections, 1 section = 32 blocks) before
	// the tree is rebuilt around their new position. With lodStreamRadius=256, 16 = ~6%
	// off-center so the tree always tracks the player closely. An elytra player at
	// ~50 blocks/s triggers a rebuild every ~10s, walking rarely.
	private static final int TREE_REBUILD_DISTANCE = 16;
	private static final int POSITION_CHECK_INTERVAL = 40;       // 2s -- cheap iteration
	private static final int DIRTY_DEBOUNCE_TICKS = 20;          // wait 1 second after last dirty before processing
	private static final int STATUS_SEND_INTERVAL = 20;          // send sync status to clients every 1 second
	private static final int TELEMETRY_INTERVAL = 60;            // log generation telemetry every 3 seconds
	private static final long SLOW_TICK_NANOS = 50_000_000L;     // 50ms = vanilla tick budget
	// Internal safety cap on concurrent in-flight chunk loads. With Lithium/C2ME,
	// off-thread getChunk(FULL, true) is routed through the main-thread mailbox
	// via CompletableFuture.AsyncSupply -- each pending one recurses through
	// getChunkBlocking which spins the main-thread executor and picks up MORE
	// of our pending tasks. Each level adds ~getChunkAvg ms to main-thread
	// blocking. With cap=16, worst-case stacking is ~16 * 100ms = 1.6s, far
	// below the 60s watchdog. This is intentionally NOT a user knob.
	private static final int MAX_IN_FLIGHT_CHUNKS = 16;
	// The session's generation queue holds the FULL set of near-empty columns at
	// tree-build time, sorted by distance from the player's current section. The
	// queue is rebuilt from scratch whenever the tree is rebuilt (player moved >
	// TREE_REBUILD_DISTANCE), so it always reflects the player's recent position.
	// No artificial per-tick queue cap -- AIMD on dispatchBudget is the only
	// rate control, so a single user can saturate the chunk pipeline up to
	// whatever targetTps allows.

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
	private final AtomicInteger inFlightChunks = new AtomicInteger();
	private int tickCounter = 0;

	// AIMD rate limiter. dispatchBudget is the chunks-per-tick we are allowed
	// to dispatch. mcTick > 1.5x target -> halve; mcTick within target window
	// AND we did real work -> add max(0.5, budget/8). Floor 0.25 so overloaded
	// servers still emit something eventually; per-tick dispatch is then
	// bounded by MAX_IN_FLIGHT_CHUNKS regardless of how high budget goes.
	private double dispatchBudget = 1.0;
	private static final double DISPATCH_BUDGET_MIN = 0.25;
	// Sanity ceiling: per-tick dispatch is bounded by MAX_IN_FLIGHT_CHUNKS anyway,
	// so this is purely an overflow guard against runaway ramp without MD signal.
	private static final double DISPATCH_BUDGET_MAX = 1024.0;
	private double dispatchAccumulator = 0.0;
	private boolean dispatchedThisTick = false;

	// Telemetry counters reset each TELEMETRY_INTERVAL window.
	private final AtomicInteger windowChunksCompleted = new AtomicInteger();
	private final AtomicInteger windowChunksFailed = new AtomicInteger();
	private final AtomicLong windowGetChunkNanos = new AtomicLong();
	private final AtomicLong windowGetChunkMaxNanos = new AtomicLong();
	private final AtomicLong windowVoxelizeNanos = new AtomicLong();
	private final AtomicLong windowVoxelizeMaxNanos = new AtomicLong();
	private long windowTickNanos = 0;
	private long windowTickMaxNanos = 0;
	private int windowSlowTicks = 0;
	private long windowMcTickNanos = 0;
	private long windowMcTickMaxNanos = 0;
	private long lastTickStartNanos = 0;
	// Exponential moving average of mcTick. alpha = 1/EMA_DENOM, ~5 sample window @ 20 TPS = 0.25s.
	private long mcTickEmaNanos = 0;
	private static final int EMA_DENOM = 5;

	// Debounce: sectionKey -> (dimension, tick when last dirtied)
	private record PendingDirty(Identifier dimension, long lastDirtyTick) {}
	private final ConcurrentHashMap<Long, PendingDirty> pendingDirty = new ConcurrentHashMap<>();
	private volatile long currentTick = 0;

	public SyncService(ServerLodEngine engine, VoxyServerConfig config) {
		this.engine = engine;
		this.config = config;
		engine.setDirtySectionListener(this::onSectionDirty);
		// Pool sized to MAX_IN_FLIGHT_CHUNKS so ALL queued tasks have a worker
		// thread immediately. Each worker calls getChunk(FULL, true) which
		// blocks until completion. The MAX_IN_FLIGHT cap (= pool size here)
		// is what bounds Lithium's recursive routing to main thread.
		this.genExecutor = Executors.newFixedThreadPool(
			MAX_IN_FLIGHT_CHUNKS,
			r -> {
				Thread t = new Thread(r, "VoxyServer MerkleGen");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			}
		);
		VoxyServerMod.LOGGER.info("[Sync] SyncService created with lodStreamRadius={}, targetTps={}",
			config.lodStreamRadius, config.targetTps);
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

				// Generation queue is filled on the tick loop via refillSessionQueues(),
				// which uses the player's CURRENT position. No bulk preload here -- the
				// player may already have moved by the time the tree finishes building.
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

			// Generation path: nothing to do here. The dispatcher pulls candidates
			// fresh from the tree each tick using the player's current position;
			// any column the L1 diff would have flagged as missing-on-server is
			// already covered by getNearestEmptyColumns (it returns columns where
			// the server's L1 hash is 0).

			if (diff.sectionsToSync().isEmpty() && diff.columnsToGenerate().isEmpty()) {
				session.setState(PlayerSyncSession.State.IDLE);
				VoxyServerMod.LOGGER.info("[Sync] {} is fully synced", player.getName().getString());
			}
		} catch (Exception e) {
			VoxyServerMod.LOGGER.error("[Sync] Failed to process client L1 hashes for {}", player.getName().getString(), e);
		}
	}

	/**
	 * Round-robin through connected players, popping the closest queued column
	 * from each session in turn and submitting its missing chunks to genExecutor.
	 * Bounded by config.maxInFlightChunks so the main thread is never flooded
	 * and no single player can starve the others.
	 */
	/**
	 * AIMD adjustment of dispatchBudget based on the previous tick's observed mcTick.
	 * Called once per server tick from tick().
	 *   mcTick > targetMs       -> multiplicative decrease (halve)
	 *   mcTick <= targetMs * .8 -> additive increase (+0.5 if there is queued work)
	 *   otherwise (in dead zone)-> hold
	 * Hysteresis keeps us from oscillating around the target.
	 */
	private void updateDispatchBudget() {
		if (mcTickEmaNanos == 0) return; // not enough samples yet
		long targetNanos = Math.max(1_000_000L, 1_000_000_000L / Math.max(1, config.targetTps));
		// MD threshold is 1.5x target -- only halve when significantly slow,
		// not on every tick that brushes against the target. This gives chunk
		// gen room to operate at higher target TPS values; a 51ms tick at
		// target=20 (50ms target) is normal, not a reason to back off.
		long mdThresholdNanos = targetNanos + targetNanos / 2;
		if (mcTickEmaNanos > mdThresholdNanos) {
			dispatchBudget = Math.max(DISPATCH_BUDGET_MIN, dispatchBudget * 0.5);
		} else if (dispatchedThisTick) {
			// AI ramp on any not-significantly-slow tick where we're doing
			// real work. The dispatchedThisTick guard prevents unbounded
			// growth on idle servers.
			double step = Math.max(0.5, dispatchBudget / 8.0);
			dispatchBudget = Math.min(DISPATCH_BUDGET_MAX, dispatchBudget + step);
		}
	}

	/**
	 * For each session whose queue has drained near empty, pull fresh empty columns
	 * from the tree, sorted by the player's CURRENT section position. This is the
	 * mechanism that makes generation track player movement: a player who flies
	 * 500 blocks while the queue is in flight will, on the next refill, see new
	 * candidates from the front of their travel direction, not stale columns
	 * around their old location.
	 */
	/**
	 * Rebuild a single session's Merkle tree centered on the player's current section.
	 * Drops any pending generation queue (stale center) so the next refill pulls
	 * candidates from the new center.
	 */
	private void rebuildTreeFor(MinecraftServer server, PlayerSyncSession session, String reason) {
		ServerPlayer player = session.getPlayer();
		if (player.isRemoved()) return;
		int sectionX = player.getBlockX() >> 5;
		int sectionZ = player.getBlockZ() >> 5;
		Identifier dimension = session.getCurrentDimension();
		if (dimension == null) return;
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

				// Generation candidates are pulled fresh from the tree each tick
				// in dispatchGeneration() using the player's CURRENT position --
				// no pre-populated queue. This guarantees that as the player
				// moves (even within TREE_REBUILD_DISTANCE) chunk gen tracks
				// their actual location, not a stale snapshot.
				int emptyCount = tree.getEmptyColumnCount();
				VoxyServerMod.LOGGER.info(
					"[MerkleGen] Tree rebuilt ({}) for {} at ({},{}) -> {} L2 regions, {} empty columns",
					reason, player.getName().getString(), sectionX, sectionZ,
					l2Hashes.size(), emptyCount);
			} catch (Exception e) {
				VoxyServerMod.LOGGER.error("[Sync] Failed to rebuild tree ({}) for {}",
					reason, player.getName().getString(), e);
			}
		});
	}

	/**
	 * Rebuild every connected session's tree. Used after config changes that
	 * affect the tree shape (e.g. lodRadius).
	 */
	public void rebuildAllTrees(MinecraftServer server) {
		for (PlayerSyncSession session : sessions.values()) {
			rebuildTreeFor(server, session, "config-change");
		}
	}

	/**
	 * Called by ChunkVoxelizer immediately after writing a marker for a chunk.
	 * Updates all active sessions' trees so the dispatcher's
	 * getNearestEmptyColumns skips columns whose chunks have all been
	 * voxelized -- without waiting for the 20-tick dirty-section debounce
	 * to update the column's L1 hash.
	 */
	public void notifyChunkMarkerSet(int chunkX, int chunkZ) {
		for (PlayerSyncSession session : sessions.values()) {
			PlayerMerkleTree tree = session.getTree();
			if (tree != null) {
				tree.notifyChunkMarkerSet(chunkX, chunkZ);
			}
		}
	}

	private void dispatchGeneration(MinecraftServer server) {
		dispatchedThisTick = false;
		if (chunkVoxelizer == null) return;

		// Hard correctness cap (NOT a tuning knob): bound concurrent in-flight
		// chunk loads. With Lithium/C2ME present, exceeding this would risk
		// pathological main-thread recursion depth. AIMD will keep ramping
		// budget but only up to this ceiling per tick.
		int inFlightHeadroom = MAX_IN_FLIGHT_CHUNKS - inFlightChunks.get();
		if (inFlightHeadroom <= 0) return;

		// Token-bucket: accumulate fractional budget so very low rates still progress.
		dispatchAccumulator += dispatchBudget;
		int tokensThisTick = (int) dispatchAccumulator;
		dispatchAccumulator -= tokensThisTick;
		if (tokensThisTick <= 0) return;

		List<PlayerSyncSession> sessionList = new ArrayList<>(sessions.values());
		if (sessionList.isEmpty()) return;
		int budget = Math.min(tokensThisTick, inFlightHeadroom);

		// Build per-session candidate lists FRESH each tick using each player's
		// CURRENT section. With no persistent queue we always dispatch nearest-
		// to-now, even as the player moves within TREE_REBUILD_DISTANCE. The
		// tree iteration is O(N=l1Hashes) per call (~263k entries at radius 256,
		// ~3ms); at 14-20 TPS this is well within budget.
		// Limit is generous (budget rounded up to nearest column count) so the
		// dispatcher never runs out of candidates within a tick if one column
		// has fewer than 4 missing chunks.
		int candidateLimit = Math.max(8, budget); // each col is up to 4 chunks
		java.util.Map<PlayerSyncSession, java.util.Iterator<long[]>> candidates =
			new java.util.IdentityHashMap<>();
		for (PlayerSyncSession session : sessionList) {
			ServerPlayer player = session.getPlayer();
			if (player.isRemoved()) continue;
			PlayerMerkleTree tree = session.getTree();
			if (tree == null) continue;
			int psx = player.getBlockX() >> 5;
			int psz = player.getBlockZ() >> 5;
			List<long[]> cols = tree.getNearestEmptyColumns(
				psx, psz, candidateLimit, session.getPendingGenerationKeys());
			if (!cols.isEmpty()) {
				candidates.put(session, cols.iterator());
			}
		}
		if (candidates.isEmpty()) return;
		// dispatchedThisTick is set TRUE only when we actually submit a chunk
		// task (in the loop body below), not just because candidates exist.
		// Many candidates may turn out to have all chunks already voxelized
		// (markers set from vanilla chunk loading) before the tree's L1 has
		// caught up via the 20-tick dirty-section debounce -- iterating them
		// is a no-op and should not let AIMD ramp.

		boolean progressed = true;
		while (budget > 0 && progressed) {
			progressed = false;
			for (PlayerSyncSession session : sessionList) {
				if (budget <= 0) break;
				java.util.Iterator<long[]> iter = candidates.get(session);
				if (iter == null || !iter.hasNext()) continue;

				ServerPlayer player = session.getPlayer();
				if (player.isRemoved()) continue;

				Identifier dimension = session.getCurrentDimension();
				ServerLevel level = findLevel(server, dimension);
				if (level == null) continue;

				long[] col = iter.next();
				int sectionX = (int) col[0];
				int sectionZ = (int) col[1];
				long columnKey = MerkleHashUtil.packColumnKey(sectionX, sectionZ);
				// Mark pending so the SAME column isn't picked up by a later
				// tick's getNearestEmptyColumns query (it excludes pendingGen).
				session.markGenerationStarted(columnKey);

				List<ChunkPos> missingChunks = engine.getSectionHashStore()
					.getMissingChunksForSection(sectionX, sectionZ);
				if (missingChunks.isEmpty()) {
					session.markGenerationFinished(columnKey);
					progressed = true;
					continue;
				}

				int n = missingChunks.size();
				inFlightChunks.addAndGet(n);
				budget -= n;
				progressed = true;
				dispatchedThisTick = true;

				AtomicInteger remaining = new AtomicInteger(n);
				for (ChunkPos pos : missingChunks) {
					// Synchronous getChunk(FULL, true) from worker thread.
					// Lithium's getChunkOffThread mixin re-routes back through
					// the main thread mailbox -- which is why MAX_IN_FLIGHT_CHUNKS
					// is hard-capped: too many concurrent requests recurse through
					// the main-thread executor and risk the 60s watchdog.
					genExecutor.execute(() -> {
						boolean ok = false;
						long getChunkStart = System.nanoTime();
						long getChunkEnd = getChunkStart;
						try {
							ChunkAccess chunk = level.getChunkSource().getChunk(
								pos.x(), pos.z(), ChunkStatus.FULL, true);
							getChunkEnd = System.nanoTime();
							if (chunk instanceof LevelChunk lc) {
								long voxStart = System.nanoTime();
								chunkVoxelizer.voxelizeNewChunk(level, lc);
								long voxNanos = System.nanoTime() - voxStart;
								windowVoxelizeNanos.addAndGet(voxNanos);
								updateMax(windowVoxelizeMaxNanos, voxNanos);
							}
							ok = true;
						} catch (Exception e) {
							getChunkEnd = System.nanoTime();
							VoxyServerMod.LOGGER.warn("[MerkleGen] Failed to generate chunk ({},{}): {}",
								pos.x(), pos.z(), e.getMessage());
						} finally {
							long getChunkNanos = getChunkEnd - getChunkStart;
							windowGetChunkNanos.addAndGet(getChunkNanos);
							updateMax(windowGetChunkMaxNanos, getChunkNanos);
							if (ok) windowChunksCompleted.incrementAndGet();
							else windowChunksFailed.incrementAndGet();
							inFlightChunks.decrementAndGet();
							if (remaining.decrementAndGet() == 0) {
								session.markGenerationFinished(columnKey);
							}
						}
					});
				}

				VoxyServerMod.debug("[MerkleGen] dispatched col ({},{}) ({} chunks) for {}",
					sectionX, sectionZ, n, player.getName().getString());
			}
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

	private static void updateMax(AtomicLong target, long sample) {
		long prev;
		do {
			prev = target.get();
			if (sample <= prev) return;
		} while (!target.compareAndSet(prev, sample));
	}

	private int getGenQueueDepth() {
		return (genExecutor instanceof ThreadPoolExecutor tpe) ? tpe.getQueue().size() : -1;
	}

	private int getGenActiveCount() {
		return (genExecutor instanceof ThreadPoolExecutor tpe) ? tpe.getActiveCount() : -1;
	}

	private void emitTelemetry(MinecraftServer server) {
		int completed = windowChunksCompleted.getAndSet(0);
		int failed = windowChunksFailed.getAndSet(0);
		long getChunkTotalNs = windowGetChunkNanos.getAndSet(0);
		long getChunkMaxNs = windowGetChunkMaxNanos.getAndSet(0);
		long voxelizeTotalNs = windowVoxelizeNanos.getAndSet(0);
		long voxelizeMaxNs = windowVoxelizeMaxNanos.getAndSet(0);
		long ourTickTotalNs = windowTickNanos;
		long ourTickMaxNs = windowTickMaxNanos;
		int slowTicks = windowSlowTicks;
		long mcTickTotalNs = windowMcTickNanos;
		long mcTickMaxNs = windowMcTickMaxNanos;
		windowTickNanos = 0;
		windowTickMaxNanos = 0;
		windowSlowTicks = 0;
		windowMcTickNanos = 0;
		windowMcTickMaxNanos = 0;

		int totalSamples = completed + failed;
		long getChunkAvgMs = totalSamples > 0 ? (getChunkTotalNs / totalSamples) / 1_000_000L : 0;
		long voxelizeAvgMs = completed > 0 ? (voxelizeTotalNs / completed) / 1_000_000L : 0;
		long ourTickAvgMs = (ourTickTotalNs / TELEMETRY_INTERVAL) / 1_000_000L;

		double seconds = TELEMETRY_INTERVAL / 20.0;
		double chunksPerSec = completed / seconds;
		// mcTick: interval between consecutive tick() calls -> approximates MC server tick time
		long mcTickAvgMs = mcTickTotalNs > 0 ? (mcTickTotalNs / TELEMETRY_INTERVAL) / 1_000_000L : 0;
		long mcTickEmaMs = mcTickEmaNanos / 1_000_000L;

		// Compute current adaptive throttle for visibility.
		double effectiveTps = mcTickEmaNanos > 0 ? Math.min(20.0, 1_000_000_000.0 / mcTickEmaNanos) : 20.0;
		int sessionsCount = sessions.size();

		VoxyServerMod.LOGGER.info(
			"[MerkleGen telemetry] window={}s completed={} failed={} ({} chunks/s) sessions={}  inFlight={} active={} queued={}  getChunk avg={}ms max={}ms  voxelize avg={}ms max={}ms  syncTick avg={}ms max={}ms slow(>50ms)={}  mcTick avg={}ms max={}ms ema={}ms tps~{}  budget={}/tick (target {}tps)",
			(int) seconds, completed, failed, String.format("%.1f", chunksPerSec), sessionsCount,
			inFlightChunks.get(), getGenActiveCount(), getGenQueueDepth(),
			getChunkAvgMs, getChunkMaxNs / 1_000_000L,
			voxelizeAvgMs, voxelizeMaxNs / 1_000_000L,
			ourTickAvgMs, ourTickMaxNs / 1_000_000L, slowTicks,
			mcTickAvgMs, mcTickMaxNs / 1_000_000L, mcTickEmaMs, String.format("%.1f", effectiveTps),
			String.format("%.2f", dispatchBudget), config.targetTps);
	}

	public void tick(MinecraftServer server) {
		long tickStart = System.nanoTime();
		if (lastTickStartNanos != 0) {
			long mcTickNanos = tickStart - lastTickStartNanos;
			windowMcTickNanos += mcTickNanos;
			if (mcTickNanos > windowMcTickMaxNanos) windowMcTickMaxNanos = mcTickNanos;
			// Update EMA: ema = ema*(D-1)/D + sample/D
			mcTickEmaNanos = mcTickEmaNanos == 0
				? mcTickNanos
				: (mcTickEmaNanos * (EMA_DENOM - 1) + mcTickNanos) / EMA_DENOM;
		}
		lastTickStartNanos = tickStart;
		tickCounter++;
		currentTick++;

		// Process debounced dirty sections
		processPendingDirty();

		// Adjust AIMD generation budget based on observed tick load
		updateDispatchBudget();

		// Drain per-session generation queues round-robin into genExecutor.
		// Queues are populated wholesale on tree build/rebuild, so the dispatcher
		// has the entire near-empty work-list to choose from; AIMD is the only
		// per-tick rate control.
		dispatchGeneration(server);

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
					rebuildTreeFor(server, session, "movement");
				}
			}
		}

		// Drain send queues
		for (PlayerSyncSession session : sessions.values()) {
			if (!session.hasPendingSections()) continue;

			ServerPlayer player = session.getPlayer();
			if (player.isRemoved()) continue;

			// Capture player's current section here on the main thread; the
			// streamWorker lambda then sorts/drains the queue against this
			// position so the closest LOD sections to the player NOW go out
			// first, even if many sections were enqueued during prior movement.
			final int playerSectionX = player.getBlockX() >> 5;
			final int playerSectionZ = player.getBlockZ() >> 5;
			streamWorker.execute(() -> {
				try {
					long[] batch = session.pollBatch(config.sectionsPerPacket,
						playerSectionX, playerSectionZ);
					if (batch == null) return;

					Identifier dimension = session.getCurrentDimension();
					ServerLevel level = findLevel(server, dimension);
					if (level == null) return;

					WorldEngine world = engine.getOrCreate(level);
					if (world == null) return;

					//? if HAS_LOOKUP_OR_THROW {
					Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
					//?} else {
					/*Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
					*///?}

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
								section, world.getMapper(), dimension, biomeRegistry
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

		// Periodically send sync status to connected players
		if (tickCounter % STATUS_SEND_INTERVAL == 0) {
			for (PlayerSyncSession session : sessions.values()) {
				ServerPlayer player = session.getPlayer();
				if (player.isRemoved()) continue;

				SyncStatusPayload status = new SyncStatusPayload(
					session.getSendQueueSize(),
					session.getState().ordinal(),
					session.getPendingGenerationCount()
				);
				ServerPlayNetworking.send(player, status);
			}
		}

		// Telemetry: track tick duration, emit summary every TELEMETRY_INTERVAL
		long tickNanos = System.nanoTime() - tickStart;
		windowTickNanos += tickNanos;
		if (tickNanos > windowTickMaxNanos) windowTickMaxNanos = tickNanos;
		if (tickNanos > SLOW_TICK_NANOS) windowSlowTicks++;
		if (tickCounter % TELEMETRY_INTERVAL == 0) {
			emitTelemetry(server);
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
