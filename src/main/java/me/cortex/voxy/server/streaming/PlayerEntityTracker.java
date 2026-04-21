package me.cortex.voxy.server.streaming;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Per-player tracking state for LOD entity sync.
 * Stores the last-sent entity snapshots so the server can compute deltas.
 */
public class PlayerEntityTracker {

	public record EntitySnapshot(
		Identifier entityType,
		int blockX,
		int blockY,
		int blockZ,
		byte yaw,
		UUID uuid
	) {}

	private Int2ObjectOpenHashMap<EntitySnapshot> lastSent = new Int2ObjectOpenHashMap<>();

	public void reset() {
		lastSent.clear();
	}

	public Int2ObjectOpenHashMap<EntitySnapshot> getLastSent() {
		return lastSent;
	}

	public void setLastSent(Int2ObjectOpenHashMap<EntitySnapshot> snapshot) {
		this.lastSent = snapshot;
	}
}
