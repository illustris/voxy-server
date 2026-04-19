package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.server.merkle.MerkleHashUtil;
import me.cortex.voxy.server.merkle.PlayerMerkleTree;
import me.cortex.voxy.server.merkle.SectionHashStore;
import me.cortex.voxy.server.network.LODSectionPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
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

	// Queue of section keys to send to this player
	private final Queue<Long> sendQueue = new ArrayDeque<>();

	// Set of section keys already sent
	private final LongOpenHashSet sentSections = new LongOpenHashSet();

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
		this.sentSections.clear();
		this.state = State.AWAITING_READY;
	}

	/**
	 * Enqueue section keys for sending.
	 */
	public void enqueueSections(List<Long> sectionKeys) {
		for (long key : sectionKeys) {
			if (!sentSections.contains(key)) {
				sendQueue.add(key);
			}
		}
	}

	/**
	 * Enqueue a single section for sending (e.g., from dirty push).
	 */
	public void enqueueSection(long sectionKey) {
		sendQueue.add(sectionKey);
	}

	/**
	 * Poll the next batch of section keys to send.
	 */
	public long[] pollBatch(int maxSize) {
		int size = Math.min(maxSize, sendQueue.size());
		if (size == 0) return null;
		long[] batch = new long[size];
		for (int i = 0; i < size; i++) {
			long key = sendQueue.poll();
			batch[i] = key;
			sentSections.add(key);
		}
		return batch;
	}

	public boolean hasPendingSections() {
		return !sendQueue.isEmpty();
	}

	/**
	 * Check if a section's XZ position is within this player's tree bounds.
	 */
	public boolean isInRange(long sectionKey) {
		if (tree == null) return false;
		int x = WorldEngine.getX(sectionKey);
		int z = WorldEngine.getZ(sectionKey);
		return tree.isInBounds(x, z);
	}
}
