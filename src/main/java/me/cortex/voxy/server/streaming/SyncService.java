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
	// Player position is sampled every N ticks; if the player section coords
	// have changed since the last sample, slideBounds reshapes the tree to
	// the new center. Every 40 ticks (2 s) is fine -- a walking player
	// crosses sections every ~8 s, sprinting every ~3 s.
	private static final int POSITION_CHECK_INTERVAL = 40;
	private static final int DIRTY_DEBOUNCE_TICKS = 20;          // wait 1 second after last dirty before processing
	private static final int STATUS_SEND_INTERVAL = 20;          // send sync status to clients every 1 second
	private static final int TELEMETRY_INTERVAL = 20;            // emit per-second snapshots; debug log fires here too
	private static final long SLOW_TICK_NANOS = 50_000_000L;     // 50ms = vanilla tick budget
	// Internal safety cap on concurrent in-flight chunk loads. With Lithium/C2ME,
	// off-thread getChunk(FULL, true) is routed through the main-thread mailbox
	// via CompletableFuture.AsyncSupply -- each pending one recurses through
	// getChunkBlocking which spins the main-thread executor and picks up MORE
	// of our pending tasks. Each level adds ~getChunkAvg ms to main-thread
	// blocking. With cap=16, worst-case stacking is ~16 * 100ms = 1.6s, far
	// below the 60s watchdog. This is intentionally NOT a user knob.
	// Upper bound for the maxInFlightChunks dispatch cap. Validation in
	// ConfigSyncHandler matches this. Bumping further requires also growing
	// the fetcher and ingest pool sizes (otherwise tasks just queue up).
	private static final int MAX_IN_FLIGHT_CHUNKS_CEILING = 512;

	// Two-stage executor split. Stage 1 (fetcher) does getChunk(FULL, true)
	// which routes through the main thread under Lithium -- so this pool is
	// kept small to bound main-thread contention. Stage 2 (ingest) does the
	// CPU-bound voxelizeNewChunk + voxy enqueueIngest (which copies lighting
	// layers and walks all chunk sections) -- this pool is sized larger so
	// CPU work doesn't serialize behind getChunks. The two-pool split also
	// means a slow getChunk doesn't pin a thread that could be ingesting.
	private static final int FETCHER_POOL_SIZE = 16;
	private static final int INGEST_POOL_SIZE = 32;
	// The session's generation queue holds the FULL set of near-empty columns at
	// tree-build time, sorted by distance from the player's current section.
	// The tree slides to the player's current center on every section-boundary
	// crossing, so the queue always reflects recent position.
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
	private final ExecutorService fetcherExecutor;
	private final ExecutorService ingestExecutor;
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
	// Sync-push activity counters. Replace the per-section debug logs that
	// Telemetry. windowSectionsSent counts sections actually wire-shipped via
	// pollBatch; windowHeartbeatsEmitted counts L2-snapshot heartbeats sent
	// (skipped emissions don't increment).
	private final AtomicInteger windowSectionsSent = new AtomicInteger();
	private final AtomicInteger windowHeartbeatsEmitted = new AtomicInteger();
	private final AtomicInteger windowHeartbeatsSkipped = new AtomicInteger();
	// Merkle-pipeline diagnostics: client L1 batch arrivals (one per
	// MerkleClientL1Payload), and sections added to send queues from diff
	// results. Together with sectionsSent these expose where the LOD
	// streaming throughput is bottlenecking when gen is saturated but the
	// client visibly lags behind.
	private final AtomicInteger windowClientL1BatchesRx = new AtomicInteger();
	private final AtomicInteger windowSectionsEnqueued = new AtomicInteger();
	// Counts onSectionDirty firings: every time voxy's IngestService writes
	// new L0 section data into the WorldEngine. This is the *real* commit
	// rate -- distinct from windowChunksCompleted which counts dispatches
	// to the IngestService queue. If the queue grows (workers slower than
	// dispatch), windowChunksCompleted will outrun this.
	private final AtomicInteger windowSectionCommits = new AtomicInteger();
	private long windowTickNanos = 0;
	private long windowTickMaxNanos = 0;
	private int windowSlowTicks = 0;
	private long windowMcTickNanos = 0;
	private long windowMcTickMaxNanos = 0;
	private long lastTickStartNanos = 0;
	// Wall-time of the previous emitTelemetry call. Used to compute the actual
	// elapsed seconds in the window so per-second rate metrics stay honest
	// when MC tick rate dips below 20 TPS (a 20-tick window can take 1.5s+
	// when ticks slow down).
	private long lastTelemetryNanos = 0;
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
		// Stage 1: getChunk(FULL, true). Small pool, main-thread-routed under
		// Lithium so concurrency past ~16 just makes the tick worse.
		this.fetcherExecutor = Executors.newFixedThreadPool(
			FETCHER_POOL_SIZE,
			r -> {
				Thread t = new Thread(r, "VoxyServer Fetcher");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			}
		);
		// Stage 2: voxelizeNewChunk + voxy.enqueueIngest. Pure CPU; scales with
		// cores. Sized larger so a slow getChunk in stage 1 doesn't block
		// ingest work that could otherwise proceed in parallel.
		this.ingestExecutor = Executors.newFixedThreadPool(
			INGEST_POOL_SIZE,
			r -> {
				Thread t = new Thread(r, "VoxyServer Ingest");
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

	/** Clamp the configured in-flight cap to [1, ceiling]. */
	private static int clampInFlight(int requested) {
		if (requested < 1) return 1;
		if (requested > MAX_IN_FLIGHT_CHUNKS_CEILING) return MAX_IN_FLIGHT_CHUNKS_CEILING;
		return requested;
	}


	public void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			VoxyServerMod.debug("[Sync] Player connection joined: {}", handler.getPlayer().getName().getString());
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID playerId = handler.getPlayer().getUUID();
			PlayerSyncSession session = sessions.remove(playerId);
			if (session != null) {
				session.reset();
				VoxyServerMod.debug("[Sync] Player disconnected, session removed: {}", handler.getPlayer().getName().getString());
			}
		});

		//? if HAS_NEW_NETWORKING {
		ServerPlayNetworking.registerGlobalReceiver(MerkleReadyPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = context.server();
			VoxyServerMod.debug("[Sync] Received MerkleReadyPayload from {}", player.getName().getString());
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
			VoxyServerMod.debug("[Sync] Received MerkleReadyPayload from {}", player.getName().getString());
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

				VoxyServerMod.debug("[Sync] Tree built for {}: {} L2 regions, {} L0 sections",
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
					VoxyServerMod.debug("[Sync] Sending {} L2 hashes to {}", regionKeys.length, player.getName().getString());
					ServerPlayNetworking.send(player, new MerkleL2HashesPayload(dimension, regionKeys, regionHashValues));
					session.setState(PlayerSyncSession.State.L2_SENT);
					// Record what we just sent so the heartbeat doesn't re-emit
					// the same snapshot on its next firing if nothing has changed.
					session.setLastEmittedL3Hash(tree.getL3Hash());
				});

				// Generation queue is filled on the tick loop via refillSessionQueues(),
				// which uses the player's CURRENT position. No bulk preload here -- the
				// player may already have moved by the time the tree finishes building.

				// Drain any chunks that are already loaded server-side but not yet
				// voxelized. Catches spawn-radius chunks loaded before our
				// CHUNK_LOAD listener registered, and any chunks force-loaded by
				// other mods. Bypasses the dispatcher's slow worldgen path.
				voxelizeAlreadyLoadedEmptyColumns(server, session, dimension);
			} catch (Exception e) {
				VoxyServerMod.LOGGER.error("[Sync] Failed to build Merkle tree for {}", player.getName().getString(), e);
			}
		});
	}

	private void onClientL1Hashes(ServerPlayer player, MerkleClientL1Payload payload, MinecraftServer server) {
		windowClientL1BatchesRx.incrementAndGet();
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

			VoxyServerMod.debug("[Sync] Merkle diff for {}: {} sections to sync, {} columns to generate across {} regions",
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
				windowSectionsEnqueued.addAndGet(diff.sectionsToSync().size());
				session.setState(PlayerSyncSession.State.SYNCING);
				VoxyServerMod.debug("[Sync] Queued {} sections for sending to {}",
					diff.sectionsToSync().size(), player.getName().getString());
			}

			// Generation path: nothing to do here. The dispatcher pulls candidates
			// fresh from the tree each tick using the player's current position;
			// any column the L1 diff would have flagged as missing-on-server is
			// already covered by getNearestEmptyColumns (it returns columns where
			// the server's L1 hash is 0).

			if (diff.sectionsToSync().isEmpty() && diff.columnsToGenerate().isEmpty()) {
				session.setState(PlayerSyncSession.State.IDLE);
				VoxyServerMod.debug("[Sync] {} is fully synced", player.getName().getString());
			}
		} catch (Exception e) {
			VoxyServerMod.LOGGER.error("[Sync] Failed to process client L1 hashes for {}", player.getName().getString(), e);
		}
	}

	/**
	 * Round-robin through connected players, popping the closest queued column
	 * from each session in turn and submitting its missing chunks to the two-stage executor.
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
					session.setLastEmittedL3Hash(tree.getL3Hash());
				});

				// Generation candidates are pulled fresh from the tree each tick
				// in dispatchGeneration() using the player's CURRENT position --
				// no pre-populated queue. This guarantees that as the player
				// moves (even within TREE_REBUILD_DISTANCE) chunk gen tracks
				// their actual location, not a stale snapshot.
				int emptyCount = tree.getEmptyColumnCount();
				VoxyServerMod.debug(
					"[MerkleGen] Tree rebuilt ({}) for {} at ({},{}) -> {} L2 regions, {} empty columns",
					reason, player.getName().getString(), sectionX, sectionZ,
					l2Hashes.size(), emptyCount);

				// Inline-voxelize any empty columns whose chunks are already
				// loaded server-side (mod-forced or carried over from a prior
				// player session). See onPlayerReady for rationale.
				voxelizeAlreadyLoadedEmptyColumns(server, session, dimension);
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
	 * After a tree (re)build, walk the empty columns and inline-voxelize any
	 * chunks that happen to be already loaded server-side. The dispatcher's
	 * normal path runs each missing chunk through the fetcher then ingest pools where
	 * {@code getChunk(FULL, true)} can trigger world generation -- expensive
	 * for unloaded chunks, but pointless for chunks that are already in the
	 * server's chunk cache. Splitting them out here lets the trivially-fast
	 * cases bypass the AIMD-throttled dispatcher entirely, which fixes the
	 * "thin square at the spawn radius" symptom (spawn chunks are loaded but
	 * miss the CHUNK_LOAD event because the listener registers in
	 * SERVER_STARTED, after spawn-load completes).
	 *
	 * Also catches chunks force-loaded by other mods (Sable SubLevels, FTB
	 * Chunks claims, etc.) that don't always fire CHUNK_LOAD.
	 *
	 * Runs on the streamWorker thread (where tree build itself runs). All
	 * calls are read-only against the server chunk cache (concurrent-safe);
	 * voxelizeNewChunk just enqueues to IngestService.
	 */
	private void voxelizeAlreadyLoadedEmptyColumns(MinecraftServer server,
			PlayerSyncSession session, Identifier dimension) {
		if (chunkVoxelizer == null) return;
		PlayerMerkleTree tree = session.getTree();
		if (tree == null) return;
		ServerLevel level = findLevel(server, dimension);
		if (level == null) return;

		var store = engine.getSectionHashStore();
		var src = level.getChunkSource();
		int[] queued = {0};

		// Bulk-load every marker in the tree's bounds in a single RocksDB
		// scan. Without this, the per-chunk marker check below would issue
		// ~4 * forEachEmptyColumnKey RocksDB queries -- on the order of 1M
		// for a 1024-radius tree -- all on the single-threaded streamWorker.
		// That blocks every other player's tree build, dirty-section push,
		// and generation dispatch behind the join, which is what surfaces
		// as "second client makes both clients stop receiving updates".
		int centerX = tree.getCenterX();
		int centerZ = tree.getCenterZ();
		int radius = tree.getRadius();
		long markerLoadStart = System.nanoTime();
		LongOpenHashSet markerSet = new LongOpenHashSet();
		store.iterateMarkersInBounds(centerX - radius, centerX + radius,
				centerZ - radius, centerZ + radius, (cx, cz) -> {
			markerSet.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
			return 0;
		});
		long markerLoadMs = (System.nanoTime() - markerLoadStart) / 1_000_000L;
		if (markerLoadMs > 250) {
			VoxyServerMod.debug(
				"[Sync] Bulk-loaded {} markers in {}ms for {} (radius={})",
				markerSet.size(), markerLoadMs,
				session.getPlayer().getName().getString(), radius);
		}

		tree.forEachEmptyColumnKey(colKey -> {
			int sx = MerkleHashUtil.columnKeyX(colKey);
			int sz = MerkleHashUtil.columnKeyZ(colKey);
			int baseChunkX = sx * 2;
			int baseChunkZ = sz * 2;
			for (int dx = 0; dx < 2; dx++) {
				for (int dz = 0; dz < 2; dz++) {
					int cx = baseChunkX + dx;
					int cz = baseChunkZ + dz;
					if (markerSet.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL))) continue;
					var chunk = src.getChunkNow(cx, cz);
					if (!(chunk instanceof LevelChunk)) continue;
					chunkVoxelizer.voxelizeNewChunk(level, (LevelChunk) chunk);
					queued[0]++;
				}
			}
		});

		if (queued[0] > 0) {
			VoxyServerMod.debug(
				"[Sync] Inline-voxelized {} already-loaded chunks for {} (bypassed worldgen path)",
				queued[0], session.getPlayer().getName().getString());
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
		int inFlightHeadroom = clampInFlight(config.maxInFlightChunks) - inFlightChunks.get();
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

		// Rotate the start index by the tick counter so two players don't
		// share unfairly: the first session in iteration order would otherwise
		// always be picked first, and a single column (typically 4 chunks)
		// can exhaust the per-tick budget on its own, starving every other
		// player. Rotation gives each session an equal shot at the first slot.
		int sessionCount = sessionList.size();
		int rotation = sessionCount > 0 ? (int) Math.floorMod(currentTick, sessionCount) : 0;

		boolean progressed = true;
		while (budget > 0 && progressed) {
			progressed = false;
			for (int rrIdx = 0; rrIdx < sessionCount; rrIdx++) {
				PlayerSyncSession session = sessionList.get((rrIdx + rotation) % sessionCount);
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
				// Triple from getNearestEmptyColumns: {x, z, isDangling}.
				// When dangling, the column is marker-covered but has no L0
				// data (dangling fingerprint from a crashed prior run); we
				// must force-revoxelize all 4 chunks rather than calling
				// getMissingChunksForSection (which would return empty since
				// the markers all exist) and use revoxelizeChunk so the
				// existing markers don't short-circuit the ingest path.
				final boolean isDangling = col[2] != 0L;
				long columnKey = MerkleHashUtil.packColumnKey(sectionX, sectionZ);
				// Mark pending so the SAME column isn't picked up by a later
				// tick's getNearestEmptyColumns query (it excludes pendingGen).
				session.markGenerationStarted(columnKey);

				List<ChunkPos> missingChunks;
				if (isDangling) {
					// All 4 chunks of the column. Section coords -> chunk
					// coords via *2 (each WorldSection covers a 2x2 chunk grid).
					int baseChunkX = sectionX * 2;
					int baseChunkZ = sectionZ * 2;
					missingChunks = new ArrayList<>(4);
					missingChunks.add(new ChunkPos(baseChunkX,     baseChunkZ));
					missingChunks.add(new ChunkPos(baseChunkX + 1, baseChunkZ));
					missingChunks.add(new ChunkPos(baseChunkX,     baseChunkZ + 1));
					missingChunks.add(new ChunkPos(baseChunkX + 1, baseChunkZ + 1));
				} else {
					missingChunks = engine.getSectionHashStore()
						.getMissingChunksForSection(sectionX, sectionZ);
				}
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
					// Two-stage dispatch: fetch on the small pool to bound
					// main-thread getChunk pressure, then hand off to the larger
					// ingest pool for the CPU-only voxelize + voxy.enqueueIngest
					// work. inFlightChunks stays incremented across both stages
					// (decremented exactly once at the end of stage 2 or in the
					// stage-1 failure path).
					fetcherExecutor.execute(() -> {
						LevelChunk fetched = null;
						long getChunkStart = System.nanoTime();
						long getChunkEnd = getChunkStart;
						try {
							ChunkAccess chunk = level.getChunkSource().getChunk(
								pos.x(), pos.z(), ChunkStatus.FULL, true);
							getChunkEnd = System.nanoTime();
							if (chunk instanceof LevelChunk lc) fetched = lc;
						} catch (Exception e) {
							getChunkEnd = System.nanoTime();
							VoxyServerMod.LOGGER.warn("[MerkleGen] Failed to fetch chunk ({},{}): {}",
								pos.x(), pos.z(), e.getMessage());
						}
						long getChunkNanos = getChunkEnd - getChunkStart;
						windowGetChunkNanos.addAndGet(getChunkNanos);
						updateMax(windowGetChunkMaxNanos, getChunkNanos);

						if (fetched == null) {
							// Stage-1 failed (exception or non-LevelChunk). Finalize
							// here -- no ingest task to submit.
							windowChunksFailed.incrementAndGet();
							inFlightChunks.decrementAndGet();
							if (remaining.decrementAndGet() == 0) {
								session.markGenerationFinished(columnKey);
							}
							return;
						}

						final LevelChunk lc = fetched;
						ingestExecutor.execute(() -> {
							boolean ok = false;
							boolean enqueued = false;
							try {
								long voxStart = System.nanoTime();
								me.cortex.voxy.server.engine.ChunkVoxelizer.IngestResult result;
								if (isDangling) {
									// force=true: bypass the per-chunk marker
									// short-circuit, otherwise the existing
									// dangling marker would skip ingest.
									result = chunkVoxelizer.revoxelizeChunk(level, lc);
								} else {
									result = chunkVoxelizer.voxelizeNewChunk(level, lc);
								}
								long voxNanos = System.nanoTime() - voxStart;
								windowVoxelizeNanos.addAndGet(voxNanos);
								updateMax(windowVoxelizeMaxNanos, voxNanos);
								enqueued = result == me.cortex.voxy.server.engine.ChunkVoxelizer.IngestResult.ENQUEUED;
								ok = true;
							} catch (Exception e) {
								VoxyServerMod.LOGGER.warn("[MerkleGen] Failed to ingest chunk ({},{}): {}",
									pos.x(), pos.z(), e.getMessage());
							} finally {
								if (enqueued) windowChunksCompleted.incrementAndGet();
								else if (!ok) windowChunksFailed.incrementAndGet();
								inFlightChunks.decrementAndGet();
								// For dangling columns, optimistically clear the
								// tree's dangling flag the moment any chunk in
								// the column ENQUEUEs to voxy. The 20-tick
								// onSectionDirty debounce otherwise leaves the
								// column flagged dangling for ~1s after revoxelize
								// returns, and the dispatcher re-picks it on every
								// tick (it's marker-covered + dangling, so
								// getNearestEmptyColumns returns it). That re-
								// dispatch loop inflates cps by ~80x per dangling
								// column.
								if (enqueued && isDangling) {
									PlayerMerkleTree tree = session.getTree();
									if (tree != null) tree.markDanglingResolved(columnKey);
								}
								if (remaining.decrementAndGet() == 0) {
									session.markGenerationFinished(columnKey);
								}
							}
						});
					});
				}

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
		windowSectionCommits.incrementAndGet();

		// Fire-and-forget push: every section that gets voxelized goes straight
		// into the send queue of every online session whose tree covers it.
		// Bypasses the merkle diff round-trip so steady-state streaming
		// throughput tracks voxelization throughput rather than the L0 ->
		// debounce -> heartbeat -> client L1 -> diff cadence. No state
		// tracking on whether the client actually got it -- the merkle diff
		// remains the reconciliation path for clients catching up after
		// disconnect, dimension change, or teleport. Send queue dedup means
		// double-enqueue (push + diff) is a no-op.
		int sx = WorldEngine.getX(sectionKey);
		int sz = WorldEngine.getZ(sectionKey);
		for (PlayerSyncSession session : sessions.values()) {
			if (!dimension.equals(session.getCurrentDimension())) continue;
			PlayerMerkleTree tree = session.getTree();
			if (tree == null) continue;
			if (!tree.isInBounds(sx, sz)) continue;
			session.enqueueSection(sectionKey);
		}
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

				// Update each in-range session's in-memory tree so the next
				// merkle heartbeat reflects this change. We deliberately do
				// NOT enqueue the section for direct push -- delivery is
				// driven by the heartbeat-then-diff loop, which keeps RocksDB
				// (durable) as the single source of truth and lets us
				// self-heal from any failed delivery on the next cycle.
				for (PlayerSyncSession session : sessions.values()) {
					PlayerMerkleTree tree = session.getTree();
					if (tree == null) continue;
					if (!dimension.equals(session.getCurrentDimension())) continue;
					if (session.isInRange(sx, sz)) {
						tree.onL0HashChanged(sectionKey, newHash);
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

	/**
	 * Emit one L2-hash heartbeat per eligible session. A session is eligible
	 * when its tree exists, it's past initial L2 send, and its current
	 * dimension matches a real level. The actual wire send is gated by an
	 * L3-root pre-check: if the tree's root is unchanged since this
	 * session's last emission, skip the work (no client divergence is
	 * possible without an L3 change).
	 *
	 * The L2 payload is always *complete* (every region in the player's
	 * current tree bounds), not delta-encoded. Cost is small (~16 KB at
	 * radius 512, ~64 KB at radius 1024) and always sending the full set
	 * means the client can recover from any missed/dropped past delivery
	 * within one heartbeat cycle, with no per-session state required to
	 * track "what we last told them".
	 */
	/**
	 * Force-emit a heartbeat for every eligible session right now, regardless
	 * of the L3-root pre-check. Used by `/voxysv merkleHeartbeat now` for
	 * debug / manual reconciliation. Returns how many sessions were sent to.
	 */
	public int forceEmitHeartbeats(MinecraftServer server) {
		int sent = 0;
		for (PlayerSyncSession session : sessions.values()) {
			session.setLastEmittedL3Hash(0);
		}
		emitL2Heartbeat(server);
		for (PlayerSyncSession session : sessions.values()) {
			if (session.getLastEmittedL3Hash() != 0) sent++;
		}
		return sent;
	}

	private void emitL2Heartbeat(MinecraftServer server) {
		for (PlayerSyncSession session : sessions.values()) {
			ServerPlayer player = session.getPlayer();
			if (player.isRemoved()) continue;

			PlayerSyncSession.State st = session.getState();
			if (st == PlayerSyncSession.State.AWAITING_READY
					|| st == PlayerSyncSession.State.TREE_BUILT) {
				// Not yet completed initial L2 handshake -- the addPlayer /
				// rebuildTreeFor paths handle that round-trip. Don't race them.
				continue;
			}

			PlayerMerkleTree tree = session.getTree();
			if (tree == null) continue;
			Identifier dimension = session.getCurrentDimension();
			if (dimension == null) continue;

			long currentL3 = tree.getL3Hash();
			if (currentL3 == session.getLastEmittedL3Hash()) {
				windowHeartbeatsSkipped.incrementAndGet();
				continue;
			}

			Long2LongOpenHashMap l2 = tree.getL2Hashes();
			int n = l2.size();
			long[] regionKeys = new long[n];
			long[] regionHashValues = new long[n];
			int i = 0;
			for (var entry : l2.long2LongEntrySet()) {
				regionKeys[i] = entry.getLongKey();
				regionHashValues[i] = entry.getLongValue();
				i++;
			}

			final long[] keysFinal = regionKeys;
			final long[] hashesFinal = regionHashValues;
			final Identifier dimFinal = dimension;
			server.execute(() -> {
				if (player.isRemoved()) return;
				ServerPlayNetworking.send(player,
					new MerkleL2HashesPayload(dimFinal, keysFinal, hashesFinal));
			});
			session.setLastEmittedL3Hash(currentL3);
			windowHeartbeatsEmitted.incrementAndGet();
		}
	}

	private int getGenQueueDepth() {
		int total = 0;
		if (fetcherExecutor instanceof ThreadPoolExecutor f) total += f.getQueue().size();
		if (ingestExecutor instanceof ThreadPoolExecutor i) total += i.getQueue().size();
		return total;
	}

	private int getGenActiveCount() {
		int total = 0;
		if (fetcherExecutor instanceof ThreadPoolExecutor f) total += f.getActiveCount();
		if (ingestExecutor instanceof ThreadPoolExecutor i) total += i.getActiveCount();
		return total;
	}

	private void emitTelemetry(MinecraftServer server) {
		long now = System.nanoTime();
		long windowNanos;
		if (lastTelemetryNanos == 0) {
			// First emit since startup -- assume the ideal 1s for normalization.
			windowNanos = TELEMETRY_INTERVAL * 50_000_000L;
		} else {
			windowNanos = now - lastTelemetryNanos;
		}
		lastTelemetryNanos = now;
		double actualSeconds = windowNanos / 1_000_000_000.0;
		if (actualSeconds < 0.001) actualSeconds = 0.001; // safety against div-by-zero

		int completed = windowChunksCompleted.getAndSet(0);
		int failed = windowChunksFailed.getAndSet(0);
		long getChunkTotalNs = windowGetChunkNanos.getAndSet(0);
		long getChunkMaxNs = windowGetChunkMaxNanos.getAndSet(0);
		long voxelizeTotalNs = windowVoxelizeNanos.getAndSet(0);
		long voxelizeMaxNs = windowVoxelizeMaxNanos.getAndSet(0);
		int sectionsSentRaw = windowSectionsSent.getAndSet(0);
		int heartbeatsEmittedRaw = windowHeartbeatsEmitted.getAndSet(0);
		int heartbeatsSkippedRaw = windowHeartbeatsSkipped.getAndSet(0);
		int clientL1BatchesRxRaw = windowClientL1BatchesRx.getAndSet(0);
		int sectionsEnqueuedRaw = windowSectionsEnqueued.getAndSet(0);
		int sectionCommitsRaw = windowSectionCommits.getAndSet(0);
		// Instantaneous read of voxy's IngestService task queue. If this grows
		// faster than commits, the voxy worker pool (workerThreads) is the
		// bottleneck.
		int voxyIngestQueueSize = engine.getIngestService().getTaskCount();
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

		double chunksPerSec = completed / actualSeconds;
		double failedPerSec = failed / actualSeconds;
		double sectionsSentPerSec = sectionsSentRaw / actualSeconds;
		double heartbeatsEmittedPerSec = heartbeatsEmittedRaw / actualSeconds;
		double heartbeatsSkippedPerSec = heartbeatsSkippedRaw / actualSeconds;
		double clientL1BatchesRxPerSec = clientL1BatchesRxRaw / actualSeconds;
		double sectionsEnqueuedPerSec = sectionsEnqueuedRaw / actualSeconds;
		double sectionCommitsPerSec = sectionCommitsRaw / actualSeconds;
		// mcTick: interval between consecutive tick() calls -> approximates MC server tick time
		long mcTickAvgMs = mcTickTotalNs > 0 ? (mcTickTotalNs / TELEMETRY_INTERVAL) / 1_000_000L : 0;
		long mcTickEmaMs = mcTickEmaNanos / 1_000_000L;

		// Compute current adaptive throttle for visibility.
		double effectiveTps = mcTickEmaNanos > 0 ? Math.min(20.0, 1_000_000_000.0 / mcTickEmaNanos) : 20.0;
		int sessionsCount = sessions.size();

		// Diagnostic: total dangling columns across all sessions. Non-zero
		// means there is leftover marker-without-L0 state from prior crashes
		// that the dispatcher is now actively recovering. Should trend to 0.
		int danglingTotal = 0;
		for (PlayerSyncSession session : sessions.values()) {
			PlayerMerkleTree tree = session.getTree();
			if (tree != null) danglingTotal += tree.getDanglingColumnCount();
		}

		VoxyServerMod.debug(
			"[MerkleGen telemetry] window={}s completed={} failed={} ({} chunks/s) sessions={}  inFlight={} active={} queued={}  getChunk avg={}ms max={}ms  voxelize avg={}ms max={}ms  syncTick avg={}ms max={}ms slow(>50ms)={}  mcTick avg={}ms max={}ms ema={}ms tps~{}  budget={}/tick (target {}tps)  sync sectionsSent={} heartbeats={}/{} (emit/skip)  dangling={}",
			String.format("%.2f", actualSeconds), completed, failed, String.format("%.1f", chunksPerSec), sessionsCount,
			inFlightChunks.get(), getGenActiveCount(), getGenQueueDepth(),
			getChunkAvgMs, getChunkMaxNs / 1_000_000L,
			voxelizeAvgMs, voxelizeMaxNs / 1_000_000L,
			ourTickAvgMs, ourTickMaxNs / 1_000_000L, slowTicks,
			mcTickAvgMs, mcTickMaxNs / 1_000_000L, mcTickEmaMs, String.format("%.1f", effectiveTps),
			String.format("%.2f", dispatchBudget), config.targetTps,
			sectionsSentRaw, heartbeatsEmittedRaw, heartbeatsSkippedRaw, danglingTotal);

		// Emit a typed snapshot to each connected client for the HUD overlay
		// to consume. Per-session because sendQueueSize / pendingGen / dangling
		// are session-local. Other fields are global but cheap to repeat.
		int chunksPerSecX10 = (int) Math.round(chunksPerSec * 10.0);
		int dispatchBudgetX100 = (int) Math.round(dispatchBudget * 100.0);
		for (PlayerSyncSession session : sessions.values()) {
			ServerPlayer player = session.getPlayer();
			if (player.isRemoved()) continue;
			PlayerMerkleTree tree = session.getTree();
			int sessionDangling = tree != null ? tree.getDanglingColumnCount() : 0;
			TelemetrySnapshotPayload snap = new TelemetrySnapshotPayload(
				chunksPerSecX10,
				(int) Math.round(failedPerSec),
				inFlightChunks.get(),
				getGenQueueDepth(),
				(int) getChunkAvgMs,
				(int) voxelizeAvgMs,
				(int) mcTickEmaMs,
				dispatchBudgetX100,
				(int) Math.round(sectionsSentPerSec),
				(int) Math.round(heartbeatsEmittedPerSec),
				(int) Math.round(heartbeatsSkippedPerSec),
				session.getSendQueueSize(),
				session.getPendingGenerationCount(),
				sessionDangling,
				sessionsCount,
				(int) Math.round(clientL1BatchesRxPerSec),
				(int) Math.round(sectionsEnqueuedPerSec),
				(int) Math.round(sectionCommitsPerSec),
				voxyIngestQueueSize);
			ServerPlayNetworking.send(player, snap);
		}
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

		// Drain per-session generation queues round-robin into the fetcher pool.
		// Queues are populated wholesale on tree build/rebuild, so the dispatcher
		// has the entire near-empty work-list to choose from; AIMD is the only
		// per-tick rate control.
		dispatchGeneration(server);

		// Slide tree centers to track player movement. Replaces the prior
		// "moved >=16 sections, full rebuild" trigger -- slideBounds runs the
		// strip-update path on streamWorker for any non-zero delta, falling
		// back to a full rebuild only on teleport-scale jumps.
		if (tickCounter % POSITION_CHECK_INTERVAL == 0) {
			for (PlayerSyncSession session : sessions.values()) {
				ServerPlayer player = session.getPlayer();
				if (player.isRemoved()) continue;

				int sectionX = player.getBlockX() >> 5;
				int sectionZ = player.getBlockZ() >> 5;
				PlayerMerkleTree tree = session.getTree();
				if (tree == null) continue;
				if (sectionX == tree.getCenterX() && sectionZ == tree.getCenterZ()) continue;

				final int newCenterX = sectionX;
				final int newCenterZ = sectionZ;
				final int teleportThreshold = config.merkleSlideTeleportThreshold;
				streamWorker.execute(() -> {
					try {
						tree.slideBounds(engine.getSectionHashStore(),
							newCenterX, newCenterZ, teleportThreshold);
						session.updatePosition(newCenterX, newCenterZ);
					} catch (Exception e) {
						VoxyServerMod.LOGGER.warn("[Sync] slideBounds failed for {}, falling back to full rebuild",
							player.getName().getString(), e);
						rebuildTreeFor(server, session, "slide-failure");
					}
				});
			}
		}

		// Periodic merkle-root heartbeat. Each session in steady state
		// receives the server's current per-region L2 hash snapshot every
		// merkleHeartbeatTicks ticks (default 5 s); the client compares
		// against its persisted L2 state and replies with L1s for any
		// mismatched regions, which `onClientL1Hashes` then turns into a
		// section diff and enqueues. Skipped per-session when the server
		// tree's L3 root hasn't changed since the last emission for that
		// session -- so steady state with no edits costs zero bandwidth.
		if (config.merkleHeartbeatTicks > 0 && tickCounter % config.merkleHeartbeatTicks == 0) {
			emitL2Heartbeat(server);
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

					if (sectionPayloads.isEmpty()) return;
					windowSectionsSent.addAndGet(sectionPayloads.size());

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

		VoxyServerMod.debug("[Sync] Player {} changed dimension to {}",
			player.getName().getString(), newLevel.dimension().identifier());

		ServerPlayNetworking.send(player, LODClearPayload.clearAll());
		session.reset();
		session.setCurrentDimension(newLevel.dimension().identifier());
		session.setState(PlayerSyncSession.State.AWAITING_READY);
	}

	public void shutdown() {
		VoxyServerMod.LOGGER.info("[Sync] Shutting down SyncService");
		fetcherExecutor.shutdownNow();
		ingestExecutor.shutdownNow();
		streamWorker.shutdownNow();
		try {
			fetcherExecutor.awaitTermination(5, TimeUnit.SECONDS);
			ingestExecutor.awaitTermination(5, TimeUnit.SECONDS);
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
