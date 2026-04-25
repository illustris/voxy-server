package me.cortex.voxy.server.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks incoming voxy network bandwidth using a rolling 1-second window.
 * All methods are safe to call from any thread.
 */
public class VoxyBandwidthTracker {
	private static final int WINDOW_MS = 1000;
	private static final int BUCKET_COUNT = 20; // 50ms per bucket

	// Per-bucket byte counts, indexed by (timeMs / bucketSize) % BUCKET_COUNT
	private static final long[] bucketBytes = new long[BUCKET_COUNT];
	private static final long[] bucketTimestamps = new long[BUCKET_COUNT];

	// Cumulative counters
	private static final AtomicLong totalBytesReceived = new AtomicLong();
	private static final AtomicInteger totalSectionsReceived = new AtomicInteger();
	private static final AtomicInteger totalPacketsReceived = new AtomicInteger();

	// Per-category byte counters for current window
	private static final AtomicLong sectionBytes = new AtomicLong();
	private static final AtomicLong entityBytes = new AtomicLong();
	private static final AtomicLong merkleBytes = new AtomicLong();

	// Server sync status (updated via SyncStatusPayload)
	private static volatile int serverQueueSize = -1;
	private static volatile int serverSyncState = -1;
	private static volatile int serverPendingGenCount = -1;

	public static void recordBytes(String category, int bytes) {
		long now = System.currentTimeMillis();
		int bucketIndex = (int) ((now / (WINDOW_MS / BUCKET_COUNT)) % BUCKET_COUNT);

		synchronized (bucketBytes) {
			long bucketTime = now / (WINDOW_MS / BUCKET_COUNT);
			if (bucketTimestamps[bucketIndex] != bucketTime) {
				bucketBytes[bucketIndex] = 0;
				bucketTimestamps[bucketIndex] = bucketTime;
			}
			bucketBytes[bucketIndex] += bytes;
		}

		totalBytesReceived.addAndGet(bytes);
		totalPacketsReceived.incrementAndGet();

		switch (category) {
			case "sections" -> sectionBytes.addAndGet(bytes);
			case "entities" -> entityBytes.addAndGet(bytes);
			case "merkle" -> merkleBytes.addAndGet(bytes);
		}
	}

	public static void recordSections(int count) {
		totalSectionsReceived.addAndGet(count);
	}

	/**
	 * Returns bytes per second over the last rolling window.
	 */
	public static double getBytesPerSecond() {
		long now = System.currentTimeMillis();
		long cutoff = (now - WINDOW_MS) / (WINDOW_MS / BUCKET_COUNT);
		long total = 0;

		synchronized (bucketBytes) {
			for (int i = 0; i < BUCKET_COUNT; i++) {
				if (bucketTimestamps[i] > cutoff) {
					total += bucketBytes[i];
				}
			}
		}

		return total; // buckets span 1 second, so total bytes ~= bytes/sec
	}

	public static long getTotalBytesReceived() {
		return totalBytesReceived.get();
	}

	public static int getTotalSectionsReceived() {
		return totalSectionsReceived.get();
	}

	public static int getTotalPacketsReceived() {
		return totalPacketsReceived.get();
	}

	public static void updateServerStatus(int queueSize, int syncState, int pendingGenCount) {
		serverQueueSize = queueSize;
		serverSyncState = syncState;
		serverPendingGenCount = pendingGenCount;
	}

	public static int getServerQueueSize() {
		return serverQueueSize;
	}

	public static int getServerSyncState() {
		return serverSyncState;
	}

	public static int getServerPendingGenCount() {
		return serverPendingGenCount;
	}

	public static void reset() {
		synchronized (bucketBytes) {
			for (int i = 0; i < BUCKET_COUNT; i++) {
				bucketBytes[i] = 0;
				bucketTimestamps[i] = 0;
			}
		}
		totalBytesReceived.set(0);
		totalSectionsReceived.set(0);
		totalPacketsReceived.set(0);
		sectionBytes.set(0);
		entityBytes.set(0);
		merkleBytes.set(0);
		serverQueueSize = -1;
		serverSyncState = -1;
		serverPendingGenCount = -1;
	}
}
