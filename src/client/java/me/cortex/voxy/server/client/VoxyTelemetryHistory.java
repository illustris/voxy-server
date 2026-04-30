package me.cortex.voxy.server.client;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-metric rolling-window history populated by incoming
 * {@link me.cortex.voxy.server.network.TelemetrySnapshotPayload} packets.
 *
 * One ring buffer per metric, fixed length {@link #WINDOW_SIZE}. The HUD
 * overlay reads these to draw bar graphs; commands / config UI may also
 * read the latest value.
 *
 * Thread-safe via per-metric synchronization on the underlying float[].
 */
public class VoxyTelemetryHistory {
	/** Number of samples retained per metric. At 1 Hz emit, 10 samples = 10 s. */
	public static final int WINDOW_SIZE = 10;

	/**
	 * Stable metric ordering. The client config UI uses these keys to drive
	 * per-metric on/off toggles. Display-friendly labels are colocated.
	 */
	public enum Metric {
		CHUNKS_PER_SEC("chunksPerSec", "Chunks gen/s"),
		FAILED_CHUNKS("failedChunks", "Failed chunks"),
		IN_FLIGHT("inFlight", "In-flight chunks"),
		GEN_QUEUE_DEPTH("genQueueDepth", "Gen queue depth"),
		GET_CHUNK_AVG_MS("getChunkAvgMs", "getChunk avg (ms)"),
		VOXELIZE_AVG_MS("voxelizeAvgMs", "Voxelize avg (ms)"),
		MC_TICK_EMA_MS("mcTickEmaMs", "MC tick EMA (ms)"),
		DISPATCH_BUDGET("dispatchBudget", "Dispatch budget"),
		SECTIONS_SENT("sectionsSent", "Sections sent/s"),
		HEARTBEATS_EMITTED("heartbeatsEmitted", "Heartbeats emit/s"),
		HEARTBEATS_SKIPPED("heartbeatsSkipped", "Heartbeats skip/s"),
		SEND_QUEUE_SIZE("sendQueueSize", "Send queue size"),
		PENDING_GEN("pendingGen", "Pending gen cols"),
		DANGLING_COLUMNS("danglingColumns", "Dangling columns"),
		SESSIONS_COUNT("sessionsCount", "Sessions"),
		CLIENT_L1_BATCHES_RX("clientL1BatchesRx", "Client L1 rx/s"),
		SECTIONS_ENQUEUED("sectionsEnqueued", "Sections enq/s"),
		SECTION_COMMITS("sectionCommits", "Section commits/s"),
		VOXY_INGEST_QUEUE("voxyIngestQueueSize", "Voxy ingest queue");

		public final String key;
		public final String label;

		Metric(String key, String label) {
			this.key = key;
			this.label = label;
		}

		public static Metric byKey(String key) {
			for (Metric m : values()) if (m.key.equals(key)) return m;
			return null;
		}
	}

	/** Indexed by Metric.ordinal(); each is a length-WINDOW_SIZE ring. */
	private static final float[][] rings = new float[Metric.values().length][WINDOW_SIZE];
	/** Write position into the ring (mod WINDOW_SIZE). */
	private static int writeIndex = 0;
	/** Number of samples ever pushed; lets readers know how much of the ring is real data. */
	private static int sampleCount = 0;

	/** Push one sample per metric. Called from the network handler. */
	public static void pushSnapshot(float[] values) {
		if (values.length != Metric.values().length) {
			throw new IllegalArgumentException(
				"Expected " + Metric.values().length + " metrics, got " + values.length);
		}
		synchronized (rings) {
			for (int i = 0; i < values.length; i++) {
				rings[i][writeIndex] = values[i];
			}
			writeIndex = (writeIndex + 1) % WINDOW_SIZE;
			if (sampleCount < Integer.MAX_VALUE) sampleCount++;
		}
	}

	/**
	 * Snapshot the ring for one metric in chronological order (oldest first).
	 * The returned array is a fresh copy. If fewer than {@link #WINDOW_SIZE}
	 * samples have been pushed, missing slots are filled with NaN.
	 */
	public static float[] snapshot(Metric metric) {
		float[] out = new float[WINDOW_SIZE];
		synchronized (rings) {
			int filled = Math.min(sampleCount, WINDOW_SIZE);
			float[] ring = rings[metric.ordinal()];
			// Oldest sample is at writeIndex (next slot to overwrite). When
			// sampleCount < WINDOW_SIZE the early slots haven't been written.
			Arrays.fill(out, Float.NaN);
			for (int i = 0; i < filled; i++) {
				int src = (writeIndex - filled + i + WINDOW_SIZE) % WINDOW_SIZE;
				out[i] = ring[src];
			}
		}
		return out;
	}

	/** Return the most recent sample for one metric, or NaN if none yet. */
	public static float latest(Metric metric) {
		synchronized (rings) {
			if (sampleCount == 0) return Float.NaN;
			int last = (writeIndex - 1 + WINDOW_SIZE) % WINDOW_SIZE;
			return rings[metric.ordinal()][last];
		}
	}

	/** Number of samples ever pushed. */
	public static int getSampleCount() {
		synchronized (rings) {
			return sampleCount;
		}
	}

	/** Wipe history (e.g. on dimension change or disconnect). */
	public static void reset() {
		synchronized (rings) {
			for (float[] ring : rings) Arrays.fill(ring, 0f);
			writeIndex = 0;
			sampleCount = 0;
		}
	}

	/** Convenience: latest values keyed by metric (for debug commands etc). */
	public static Map<Metric, Float> latestAll() {
		Map<Metric, Float> out = new LinkedHashMap<>();
		for (Metric m : Metric.values()) out.put(m, latest(m));
		return out;
	}
}
