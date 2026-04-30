package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongComparator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.server.merkle.PlayerMerkleTree;
import me.cortex.voxy.server.merkle.SectionHashStore;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Per-player sync session. Tracks the player's Merkle tree state and
 * manages the queue of sections to send.
 */
public class PlayerSyncSession {
	public enum State {
		AWAITING_READY,
		TREE_BUILT,
		L2_SENT,
		SYNCING,
		IDLE
	}

	private final UUID playerId;
	private final ServerPlayer player;
	private volatile State state = State.AWAITING_READY;
	private volatile Identifier currentDimension;
	private volatile PlayerMerkleTree tree;

	// Current position in section coordinates
	private volatile int currentSectionX;
	private volatile int currentSectionZ;

	// Pending section keys to send to this player. NOT FIFO -- pollBatch sorts
	// by distance to the player's current section before draining the closest N,
	// so a player who moves while the queue is large always gets nearest-first
	// LOD updates relative to their CURRENT location (not where they were when
	// each section got queued).
	//
	// Membership is *transient*: a section that's been wire-shipped is removed
	// from sendQueueDedup and may be re-enqueued by a future merkle diff if
	// the client doesn't actually have it (failed delivery, mod-side bug, etc).
	// The merkle protocol -- not a "sent already" set -- is the source of truth
	// for "client has this section".
	private final LongArrayList sendQueue = new LongArrayList();
	private final LongOpenHashSet sendQueueDedup = new LongOpenHashSet();

	// L3 root hash at the moment of the last L2 heartbeat emission. Used to
	// short-circuit the next heartbeat when the tree hasn't changed -- in
	// steady state with no edits this skips emission entirely.
	private long lastEmittedL3Hash = 0;

	// Column keys (packed as MerkleHashUtil.packColumnKey) currently in-flight
	// for generation. Prevents re-scheduling the same column across successive
	// dispatcher ticks (getNearestEmptyColumns excludes these).
	private final LongOpenHashSet pendingGeneration = new LongOpenHashSet();
	private final Object generationLock = new Object();

	public PlayerSyncSession(ServerPlayer player) {
		this.playerId = player.getUUID();
		this.player = player;
	}

	public UUID getPlayerId() {
		return playerId;
	}

	public ServerPlayer getPlayer() {
		return player;
	}

	public State getState() {
		return state;
	}

	public void setState(State state) {
		this.state = state;
	}

	public Identifier getCurrentDimension() {
		return currentDimension;
	}

	public void setCurrentDimension(Identifier dimension) {
		this.currentDimension = dimension;
	}

	public PlayerMerkleTree getTree() {
		return tree;
	}

	public void updatePosition(int sectionX, int sectionZ) {
		this.currentSectionX = sectionX;
		this.currentSectionZ = sectionZ;
	}

	/**
	 * Check if the player has moved significantly from the tree center.
	 */
	public boolean hasMovedSignificantly(int newSectionX, int newSectionZ, int threshold) {
		int dx = Math.abs(newSectionX - currentSectionX);
		int dz = Math.abs(newSectionZ - currentSectionZ);
		return dx > threshold || dz > threshold;
	}

	/**
	 * Check if a section at (sectionX, sectionZ) is within the player's current LOD range.
	 * Uses the player's current position, not the static tree bounds.
	 */
	public boolean isInRange(int sectionX, int sectionZ) {
		if (tree == null) return false;
		return tree.isInBounds(sectionX, sectionZ);
	}

	/**
	 * Build the player's Merkle tree from the hash store.
	 */
	public void buildTree(SectionHashStore store, int centerX, int centerZ, int radius) {
		this.tree = PlayerMerkleTree.build(store, centerX, centerZ, radius);
		this.state = State.TREE_BUILT;
	}

	/**
	 * Reset session state (e.g., on dimension change).
	 */
	public void reset() {
		this.tree = null;
		this.sendQueue.clear();
		this.sendQueueDedup.clear();
		this.lastEmittedL3Hash = 0;
		synchronized (generationLock) {
			pendingGeneration.clear();
		}
		this.state = State.AWAITING_READY;
	}

	public long getLastEmittedL3Hash() { return lastEmittedL3Hash; }
	public void setLastEmittedL3Hash(long h) { this.lastEmittedL3Hash = h; }

	public void clearPendingGeneration() {
		synchronized (generationLock) {
			pendingGeneration.clear();
		}
	}

	/**
	 * Snapshot of in-flight generation column keys. Caller-owned copy so the
	 * caller can iterate without holding the session lock.
	 */
	public LongOpenHashSet getPendingGenerationKeys() {
		synchronized (generationLock) {
			return new LongOpenHashSet(pendingGeneration);
		}
	}

	public void markGenerationStarted(long columnKey) {
		synchronized (generationLock) {
			pendingGeneration.add(columnKey);
		}
	}

	public void markGenerationFinished(long columnKey) {
		synchronized (generationLock) {
			pendingGeneration.remove(columnKey);
		}
	}

	/**
	 * Enqueue section keys for sending. Dedup against the live queue; the
	 * merkle diff (not a per-session "already sent" set) decides whether a
	 * section needs to be re-shipped, so a section flushed from the queue
	 * by a previous pollBatch may legitimately re-enter on the next diff.
	 */
	public synchronized void enqueueSections(List<Long> sectionKeys) {
		for (long key : sectionKeys) {
			if (sendQueueDedup.add(key)) {
				sendQueue.add(key);
			}
		}
	}

	/**
	 * Enqueue a single section for sending. Same dedup semantics as
	 * {@link #enqueueSections}.
	 */
	public synchronized void enqueueSection(long sectionKey) {
		if (sendQueueDedup.add(sectionKey)) {
			sendQueue.add(sectionKey);
		}
	}

	/**
	 * Sort the pending send-queue by squared distance from the player's current
	 * section, then return the closest {@code maxSize} sections. The remainder
	 * stays in the queue (sorted, but each subsequent call re-sorts in case the
	 * player has moved). This guarantees nearest-first LOD delivery even when
	 * the queue holds many sections accumulated over time.
	 */
	public synchronized long[] pollBatch(int maxSize, int playerSectionX, int playerSectionZ) {
		int size = sendQueue.size();
		if (size == 0) return null;

		final long psx = playerSectionX;
		final long psz = playerSectionZ;
		LongComparator byDist = (a, b) -> {
			long ax = WorldEngine.getX(a), az = WorldEngine.getZ(a);
			long bx = WorldEngine.getX(b), bz = WorldEngine.getZ(b);
			long adx = ax - psx, adz = az - psz;
			long bdx = bx - psx, bdz = bz - psz;
			return Long.compare(adx * adx + adz * adz, bdx * bdx + bdz * bdz);
		};
		long[] backing = sendQueue.elements();
		LongArrays.quickSort(backing, 0, size, byDist);

		int batchSize = Math.min(maxSize, size);
		long[] batch = new long[batchSize];
		System.arraycopy(backing, 0, batch, 0, batchSize);
		// Remove the dispatched portion from the front of the queue.
		// (Keep the remainder in sorted order; next call resorts anyway.)
		sendQueue.removeElements(0, batchSize);
		for (int i = 0; i < batchSize; i++) {
			sendQueueDedup.remove(batch[i]);
		}
		return batch;
	}

	public synchronized boolean hasPendingSections() {
		return !sendQueue.isEmpty();
	}

	public synchronized int getSendQueueSize() {
		return sendQueue.size();
	}

	public int getPendingGenerationCount() {
		synchronized (generationLock) {
			return pendingGeneration.size();
		}
	}
}
