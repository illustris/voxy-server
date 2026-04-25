package me.cortex.voxy.server.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Tracks LOD sections that were recently updated, for visual highlighting.
 * Thread-safe: updates arrive from the main client thread (scheduled by netty),
 * rendering reads from the render thread.
 */
public class LODSectionHighlightTracker {
	private static final long HIGHLIGHT_DURATION_MS = 2500;
	private static final int MAX_ENTRIES = 256;

	private static volatile boolean enabled = false;

	// sectionKey -> timestamp when received (System.currentTimeMillis)
	private static final Long2LongOpenHashMap activeSections = new Long2LongOpenHashMap();

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean value) {
		enabled = value;
	}

	public static void toggle() {
		enabled = !enabled;
	}

	/**
	 * Called from ClientSyncHandler.handleSection() when a section is received.
	 */
	public static void onSectionReceived(long sectionKey) {
		if (!enabled) return;
		synchronized (activeSections) {
			// Evict oldest if at capacity
			if (activeSections.size() >= MAX_ENTRIES) {
				long oldestKey = -1;
				long oldestTime = Long.MAX_VALUE;
				for (var entry : activeSections.long2LongEntrySet()) {
					if (entry.getLongValue() < oldestTime) {
						oldestTime = entry.getLongValue();
						oldestKey = entry.getLongKey();
					}
				}
				if (oldestKey != -1) {
					activeSections.remove(oldestKey);
				}
			}
			activeSections.put(sectionKey, System.currentTimeMillis());
		}
	}

	/**
	 * Snapshot of active highlights for the current frame.
	 * Prunes expired entries.
	 * Returns array of [sectionKey, timestampMs, sectionKey, timestampMs, ...]
	 */
	public static long[] getActiveHighlights() {
		long now = System.currentTimeMillis();
		synchronized (activeSections) {
			activeSections.long2LongEntrySet().removeIf(
				e -> (now - e.getLongValue()) > HIGHLIGHT_DURATION_MS
			);

			long[] result = new long[activeSections.size() * 2];
			int i = 0;
			for (var entry : activeSections.long2LongEntrySet()) {
				result[i++] = entry.getLongKey();
				result[i++] = entry.getLongValue();
			}
			return result;
		}
	}

	public static void clear() {
		synchronized (activeSections) {
			activeSections.clear();
		}
	}
}
