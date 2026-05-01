package me.cortex.voxy.server.client;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU line-of-sight culler for far entities. Raycasts from the camera to a
 * few sample points on each entity's bounding box, walking voxy's client-side
 * LOD WorldEngine voxel grid (the same grid the LOD renderer paints from).
 * An entity hidden behind a hill that exists only in LOD form gets culled,
 * which vanilla EntityCulling cannot do -- it only sees loaded vanilla
 * chunks, which beyond render distance don't exist on the client.
 *
 * Visibility checks run on a single worker thread. The render thread reads
 * the cached result; cache entries refresh asynchronously when they exceed
 * {@link VoxyServerClientConfig#lodEntityCullingRefreshMs}. New entities
 * default to "visible" so the first frame after the renderer first sees an
 * entity is never wrongly hidden.
 *
 * Sections without LOD data (the ray walks into a region the client hasn't
 * received yet) are treated as transparent -- we don't pretend to know what
 * isn't loaded. This biases toward false-positives (an entity that should be
 * occluded is rendered) which is the conservative direction.
 */
public class LODEntityCuller {
	// How long an unseen entity's cache entry survives before it's evicted.
	// Far longer than refreshMs so an entity that briefly leaves the far list
	// (vanilla started rendering it, or it ducked behind a chunk boundary)
	// keeps its last-known visibility while it's gone.
	private static final long EVICTION_AGE_MS = 5_000;

	// Below this distance we don't bother culling. Saves the per-frame
	// hashmap roundtrip on entities that are basically next to the camera
	// (which the LOD path almost never sees, but cheap insurance).
	private static final double MIN_CULL_DISTANCE = 16.0;

	private static final class Entry {
		volatile boolean visible = true;
		volatile long lastUpdateMs = 0;
		final AtomicBoolean inFlight = new AtomicBoolean(false);
	}

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "voxy-lod-entity-culler");
		t.setDaemon(true);
		return t;
	});

	private final ConcurrentHashMap<Integer, Entry> cache = new ConcurrentHashMap<>();

	/**
	 * Returns whether the entity should be rendered this frame. Non-blocking:
	 * may return a stale value while a fresh check is in flight. Returns true
	 * when culling is disabled or LOD data is unavailable.
	 */
	public boolean isVisible(Entity entity, double cameraX, double cameraY, double cameraZ) {
		VoxyServerClientConfig cfg = VoxyServerClientConfig.get();
		if (!cfg.lodEntityCullingEnabled) return true;

		double dx = entity.getX() - cameraX;
		double dy = entity.getY() - cameraY;
		double dz = entity.getZ() - cameraZ;
		double distSq = dx * dx + dy * dy + dz * dz;
		if (distSq < MIN_CULL_DISTANCE * MIN_CULL_DISTANCE) return true;

		int id = entity.getId();
		Entry e = cache.computeIfAbsent(id, k -> new Entry());

		long now = System.currentTimeMillis();
		long refresh = Math.max(20, cfg.lodEntityCullingRefreshMs);
		if (now - e.lastUpdateMs > refresh && e.inFlight.compareAndSet(false, true)) {
			final double ex = entity.getX();
			final double ey = entity.getY();
			final double ez = entity.getZ();
			final double bbH = entity.getBbHeight();
			final int maxBlocks = Math.max(64, cfg.lodEntityCullingMaxRayBlocks);
			worker.submit(() -> {
				try {
					boolean v = computeVisibility(cameraX, cameraY, cameraZ, ex, ey, ez, bbH, maxBlocks);
					e.visible = v;
					e.lastUpdateMs = System.currentTimeMillis();
				} catch (Exception ignored) {
					// Don't poison the cache on a transient lookup failure.
				} finally {
					e.inFlight.set(false);
				}
			});
		}
		return e.visible;
	}

	/**
	 * Drop cache entries for entities that haven't been queried recently and
	 * aren't in the active set this frame. Cheap; safe to call every frame.
	 */
	public void onFrameComplete(Set<Integer> activeIds) {
		long now = System.currentTimeMillis();
		cache.entrySet().removeIf(en -> {
			if (activeIds.contains(en.getKey())) return false;
			return now - en.getValue().lastUpdateMs > EVICTION_AGE_MS;
		});
	}

	/** Wipe all cached results -- e.g. on dimension change. */
	public void invalidateAll() {
		cache.clear();
	}

	public void shutdown() {
		worker.shutdownNow();
	}

	// -----------------------------------------------------------------------
	// Visibility computation. Runs on the worker thread.
	// -----------------------------------------------------------------------

	private static boolean computeVisibility(double cx, double cy, double cz,
											  double ex, double ey, double ez,
											  double bbHeight, int maxBlocks) {
		WorldEngine engine = currentEngine();
		// No LOD engine -> no data to test against; report visible so the
		// renderer behaves like it does today (non-regression on first join).
		if (engine == null) return true;

		// Three sample points along entity vertical centerline. ANY clear LoS
		// makes the entity visible. Keeps a tall mob behind a fence visible
		// when its head pokes over.
		double half = bbHeight * 0.5;
		if (rayClear(engine, cx, cy, cz, ex, ey + bbHeight, ez, maxBlocks)) return true;
		if (rayClear(engine, cx, cy, cz, ex, ey + half,     ez, maxBlocks)) return true;
		if (rayClear(engine, cx, cy, cz, ex, ey + 0.1,      ez, maxBlocks)) return true;
		return false;
	}

	private static WorldEngine currentEngine() {
		var instance = VoxyCommon.getInstance();
		if (instance == null) return null;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return null;
		WorldIdentifier wid = WorldIdentifier.of(level);
		if (wid == null) return null;
		return instance.getOrCreate(wid);
	}

	/**
	 * 3D-DDA from camera to (tx,ty,tz). Returns true iff no opaque LOD voxel
	 * sits between the two endpoints. The camera's own block is skipped (the
	 * loop steps before sampling) so being inside a block doesn't insta-cull.
	 *
	 * The most recently acquired WorldSection is held across consecutive
	 * cells in the same section -- a typical 256-block ray performs ~8
	 * acquireIfExists calls instead of 256.
	 */
	private static boolean rayClear(WorldEngine engine,
									 double cx, double cy, double cz,
									 double tx, double ty, double tz,
									 int maxBlocks) {
		double dx = tx - cx;
		double dy = ty - cy;
		double dz = tz - cz;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0e-3) return true;
		// Truncate ultra-long rays. If the truncated path is clear, we
		// optimistically call the entity visible -- it's at the far edge of
		// LOD anyway and the user almost certainly wants to see it.
		if (len > maxBlocks) {
			double s = maxBlocks / len;
			tx = cx + dx * s; ty = cy + dy * s; tz = cz + dz * s;
			dx *= s; dy *= s; dz *= s;
		}

		int bx = (int) Math.floor(cx);
		int by = (int) Math.floor(cy);
		int bz = (int) Math.floor(cz);

		int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
		int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
		int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

		double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
		double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
		double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

		double tMaxX = stepX != 0 ? ((stepX > 0 ? (bx + 1) : bx) - cx) / dx : Double.POSITIVE_INFINITY;
		double tMaxY = stepY != 0 ? ((stepY > 0 ? (by + 1) : by) - cy) / dy : Double.POSITIVE_INFINITY;
		double tMaxZ = stepZ != 0 ? ((stepZ > 0 ? (bz + 1) : bz) - cz) / dz : Double.POSITIVE_INFINITY;

		WorldSection cachedSec = null;
		int cachedSecX = Integer.MIN_VALUE, cachedSecY = Integer.MIN_VALUE, cachedSecZ = Integer.MIN_VALUE;

		// Worst-case axis-step count: every block boundary on every axis.
		// Add a small slack for floating-point drift on grazing rays.
		int safetyCap = (maxBlocks + 16) * 3;
		try {
			for (int i = 0; i < safetyCap; i++) {
				double tNext = Math.min(Math.min(tMaxX, tMaxY), tMaxZ);
				if (tNext > 1.0) return true;

				if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
					bx += stepX; tMaxX += tDeltaX;
				} else if (tMaxY <= tMaxZ) {
					by += stepY; tMaxY += tDeltaY;
				} else {
					bz += stepZ; tMaxZ += tDeltaZ;
				}

				int sx = bx >> 5;
				int sy = by >> 5;
				int sz = bz >> 5;
				if (sx != cachedSecX || sy != cachedSecY || sz != cachedSecZ) {
					if (cachedSec != null) cachedSec.release();
					cachedSec = engine.acquireIfExists(sx, sy, sz, 0);
					cachedSecX = sx; cachedSecY = sy; cachedSecZ = sz;
				}
				if (cachedSec == null) continue;

				long packed = cachedSec._unsafeGetRawDataArray()[
					WorldSection.getIndex(bx & 31, by & 31, bz & 31)];
				if (!Mapper.isAir(packed)) return false;
			}
			return true;
		} finally {
			if (cachedSec != null) cachedSec.release();
		}
	}
}
