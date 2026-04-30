package me.cortex.voxy.server.merkle;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.world.WorldEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Per-player Merkle quadtree built from persisted L0 hashes within the player's radius.
 * Computed in memory on player join, discarded on disconnect.
 *
 * Tree structure:
 * - L0: individual WorldSection hashes (32x32x32 voxels, keyed by sectionKey)
 * - L1: column hash = hash of all L0 sections at same (x,z) across all Y (keyed by columnKey)
 * - L2: region hash = hash of 32x32 L1 columns (keyed by regionKey)
 * - L3: root hash = hash of all L2 regions
 */
public class PlayerMerkleTree {
	// L0: sectionKey -> hash
	private final Long2LongOpenHashMap l0Hashes = new Long2LongOpenHashMap();

	// L1: columnKey -> hash (column = all Y at one (x,z))
	private final Long2LongOpenHashMap l1Hashes = new Long2LongOpenHashMap();

	// L2: regionKey -> hash (region = 32x32 columns)
	private final Long2LongOpenHashMap l2Hashes = new Long2LongOpenHashMap();

	// L3: root hash
	private long l3Hash;

	// Index: columnKey -> list of sectionKeys in that column
	private final Long2ObjectOpenHashMap<LongArrayList> columnToSections = new Long2ObjectOpenHashMap<>();

	// Index: regionKey -> list of columnKeys in that region
	private final Long2ObjectOpenHashMap<LongArrayList> regionToColumns = new Long2ObjectOpenHashMap<>();

	// Columns where ALL 4 chunks have voxelization markers in the section hash
	// store, regardless of whether their L0 hashes have been computed yet. We
	// treat these as "covered" so getNearestEmptyColumns skips them; otherwise
	// the dispatcher loops over columns whose chunks were just voxelized via
	// vanilla loading but whose L0 hashes haven't reached the store via the
	// 20-tick dirty-section debounce.
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet markedFullColumns =
		new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	// Per-column marker count for live updates (chunks marker'd since tree build).
	// When count for a column reaches 4 the column moves to markedFullColumns.
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap markerCounts =
		new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

	// "Dangling" columns: present in markedFullColumns (all 4 chunk markers
	// written) but with l1Hash == 0 (no L0 hashes anywhere in the column).
	// This is the fingerprint of a previous server run that successfully
	// enqueued voxelization (markers written) but crashed before voxy's
	// worker pool finished writing the section data. Without explicit
	// tracking, these columns are invisible to both the dispatcher
	// (markedFullColumns suppresses them) and the missing-chunk path
	// (all markers exist, so getMissingChunksForSection returns empty),
	// so they stay broken forever. We surface them as a strict refinement
	// of markedFullColumns and force-revoxelize via revoxelizeChunk.
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet danglingColumns =
		new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	// Bounds. centerX/Z are mutable to support slideBounds incremental
	// recomputation without rebuilding the whole tree on every player move.
	private int centerX, centerZ;
	private final int radius;

	private PlayerMerkleTree(int centerX, int centerZ, int radius) {
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.radius = radius;
		l0Hashes.defaultReturnValue(0);
		l1Hashes.defaultReturnValue(0);
		l2Hashes.defaultReturnValue(0);
	}

	/**
	 * Build a player's Merkle tree from the SectionHashStore within the given radius.
	 * @param store the persistent L0 hash store
	 * @param centerX center X in level-0 section coordinates
	 * @param centerZ center Z in level-0 section coordinates
	 * @param radius radius in level-0 section coordinates
	 */
	public static PlayerMerkleTree build(SectionHashStore store, int centerX, int centerZ, int radius) {
		PlayerMerkleTree tree = new PlayerMerkleTree(centerX, centerZ, radius);

		int minX = centerX - radius;
		int maxX = centerX + radius;
		int minZ = centerZ - radius;
		int maxZ = centerZ + radius;

		// Load all L0 hashes in bounds into the sparse map and column index
		store.iterateInBounds(minX, maxX, minZ, maxZ, (sectionKey, hash) -> {
			tree.l0Hashes.put(sectionKey.longValue(), hash.longValue());

			int x = WorldEngine.getX(sectionKey);
			int z = WorldEngine.getZ(sectionKey);
			long colKey = MerkleHashUtil.packColumnKey(x, z);
			tree.columnToSections.computeIfAbsent(colKey, k -> new LongArrayList()).add(sectionKey.longValue());
		});

		// Count voxelization markers per WorldSection-column. Each column maps
		// to a 2x2 chunk grid; we mark a column "covered" only when all 4
		// chunks have markers. This matches getMissingChunksForSection's logic
		// so the dispatcher won't redundantly try them.
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap markerCount =
			new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		store.iterateMarkersInBounds(minX, maxX, minZ, maxZ, (chunkX, chunkZ) -> {
			int sx = chunkX >> 1;
			int sz = chunkZ >> 1;
			long colKey = MerkleHashUtil.packColumnKey(sx, sz);
			markerCount.addTo(colKey, 1);
			return 0;
		});
		for (var e : markerCount.long2IntEntrySet()) {
			if (e.getIntValue() >= 4) {
				tree.markedFullColumns.add(e.getLongKey());
			} else {
				tree.markerCounts.put(e.getLongKey(), e.getIntValue());
			}
		}

		// Dense L1: iterate the full XZ grid. Columns with no L0 entries get hash 0.
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				long colKey = MerkleHashUtil.packColumnKey(x, z);
				LongArrayList sectionKeys = tree.columnToSections.get(colKey);

				long colHash;
				if (sectionKeys != null && !sectionKeys.isEmpty()) {
					long[] hashes = new long[sectionKeys.size()];
					for (int i = 0; i < sectionKeys.size(); i++) {
						hashes[i] = tree.l0Hashes.get(sectionKeys.getLong(i));
					}
					colHash = MerkleHashUtil.hashColumn(hashes);
				} else {
					colHash = 0;
				}
				tree.l1Hashes.put(colKey, colHash);

				// Detect dangling columns: fully marker-covered yet no L0 data.
				// See danglingColumns field comment for rationale.
				if (tree.markedFullColumns.contains(colKey) && colHash == 0L) {
					tree.danglingColumns.add(colKey);
				}

				int rx = MerkleHashUtil.sectionToRegionX(x);
				int rz = MerkleHashUtil.sectionToRegionZ(z);
				long regKey = MerkleHashUtil.packRegionKey(rx, rz);
				LongArrayList cols = tree.regionToColumns.computeIfAbsent(regKey, k -> new LongArrayList());
				if (!cols.contains(colKey)) {
					cols.add(colKey);
				}
			}
		}

		// Compute L2 hashes from the full (dense) set of L1 columns
		for (var entry : tree.regionToColumns.long2ObjectEntrySet()) {
			long regKey = entry.getLongKey();
			LongArrayList colKeys = entry.getValue();
			long[] hashes = new long[colKeys.size()];
			for (int i = 0; i < colKeys.size(); i++) {
				hashes[i] = tree.l1Hashes.get(colKeys.getLong(i));
			}
			tree.l2Hashes.put(regKey, MerkleHashUtil.hashRegion(hashes));
		}

		// Compute L3 root
		long[] l2Values = tree.l2Hashes.values().toLongArray();
		tree.l3Hash = MerkleHashUtil.hashRoot(l2Values);

		return tree;
	}

	/**
	 * Update the tree when a single L0 hash changes. Recomputes affected L1, L2, L3.
	 */
	public void onL0HashChanged(long sectionKey, long newHash) {
		long oldHash = l0Hashes.put(sectionKey, newHash);
		if (oldHash == newHash) return;

		int x = WorldEngine.getX(sectionKey);
		int z = WorldEngine.getZ(sectionKey);
		long colKey = MerkleHashUtil.packColumnKey(x, z);

		// Ensure the section is in the column index
		LongArrayList sectionKeys = columnToSections.computeIfAbsent(colKey, k -> new LongArrayList());
		if (!sectionKeys.contains(sectionKey)) {
			sectionKeys.add(sectionKey);
		}

		// Recompute L1 for this column
		long[] hashes = new long[sectionKeys.size()];
		for (int i = 0; i < sectionKeys.size(); i++) {
			hashes[i] = l0Hashes.get(sectionKeys.getLong(i));
		}
		long newL1 = MerkleHashUtil.hashColumn(hashes);
		l1Hashes.put(colKey, newL1);

		// Real L0 data has arrived for this column -- it is no longer dangling.
		if (newL1 != 0L) {
			danglingColumns.remove(colKey);
		}

		// Ensure column is in region index
		int rx = MerkleHashUtil.sectionToRegionX(x);
		int rz = MerkleHashUtil.sectionToRegionZ(z);
		long regKey = MerkleHashUtil.packRegionKey(rx, rz);
		LongArrayList colKeys = regionToColumns.computeIfAbsent(regKey, k -> new LongArrayList());
		if (!colKeys.contains(colKey)) {
			colKeys.add(colKey);
		}

		// Recompute L2 for this region
		long[] l1h = new long[colKeys.size()];
		for (int i = 0; i < colKeys.size(); i++) {
			l1h[i] = l1Hashes.get(colKeys.getLong(i));
		}
		l2Hashes.put(regKey, MerkleHashUtil.hashRegion(l1h));

		// Recompute L3 root
		long[] l2Values = l2Hashes.values().toLongArray();
		l3Hash = MerkleHashUtil.hashRoot(l2Values);
	}

	/**
	 * Get all L2 hashes for sending to client.
	 */
	public Long2LongOpenHashMap getL2Hashes() {
		return l2Hashes;
	}

	/**
	 * Get L1 hashes for a specific L2 region.
	 */
	public Long2LongOpenHashMap getL1HashesForRegion(long regionKey) {
		Long2LongOpenHashMap result = new Long2LongOpenHashMap();
		result.defaultReturnValue(0);
		LongArrayList colKeys = regionToColumns.get(regionKey);
		if (colKeys != null) {
			for (int i = 0; i < colKeys.size(); i++) {
				long colKey = colKeys.getLong(i);
				result.put(colKey, l1Hashes.get(colKey));
			}
		}
		return result;
	}

	/**
	 * Result of a Merkle diff: sections that need syncing vs columns that need generation.
	 */
	public record MerkleDiffResult(
		List<Long> sectionsToSync,     // server has real hash, client differs -- send data
		List<long[]> columnsToGenerate // [sectionX, sectionZ] pairs where server hash is 0 -- generate chunks
	) {}

	/**
	 * Compare client's L1 hashes against ours for given regions.
	 * Returns a MerkleDiffResult distinguishing sections to sync from columns to generate.
	 */
	public MerkleDiffResult findDifferingL0Sections(Long2ObjectOpenHashMap<Long2LongOpenHashMap> clientL1ByRegion) {
		List<Long> sectionsToSync = new ArrayList<>();
		List<long[]> columnsToGenerate = new ArrayList<>();

		for (var regionEntry : clientL1ByRegion.long2ObjectEntrySet()) {
			long regionKey = regionEntry.getLongKey();
			Long2LongOpenHashMap clientL1 = regionEntry.getValue();
			Long2LongOpenHashMap serverL1 = getL1HashesForRegion(regionKey);

			// Find columns that differ
			for (var colEntry : serverL1.long2LongEntrySet()) {
				long colKey = colEntry.getLongKey();
				long serverHash = colEntry.getLongValue();
				long clientHash = clientL1.get(colKey);

				if (serverHash == clientHash) continue;

				if (serverHash == 0) {
					// Server doesn't have complete data -- needs generation
					int cx = MerkleHashUtil.columnKeyX(colKey);
					int cz = MerkleHashUtil.columnKeyZ(colKey);
					columnsToGenerate.add(new long[]{cx, cz});
				} else {
					// Server has data the client needs -- sync
					LongArrayList sectionKeys = columnToSections.get(colKey);
					if (sectionKeys != null) {
						for (int i = 0; i < sectionKeys.size(); i++) {
							sectionsToSync.add(sectionKeys.getLong(i));
						}
					}
				}
			}
		}

		return new MerkleDiffResult(sectionsToSync, columnsToGenerate);
	}

	/**
	 * Columns inside the tree's radius whose L1 hash is 0 -- the server has no L0 data
	 * for any section in that column. Candidates for chunk generation.
	 * Returned as [sectionX, sectionZ] pairs to match MerkleDiffResult.columnsToGenerate.
	 */
	/**
	 * Count of columns inside the tree's radius that need generation:
	 * L1 hash is 0 (no server data) AND not marked as fully voxelized.
	 */
	public int getEmptyColumnCount() {
		int n = 0;
		for (var entry : l1Hashes.long2LongEntrySet()) {
			if (entry.getLongValue() != 0) continue;
			if (markedFullColumns.contains(entry.getLongKey())) continue;
			n++;
		}
		return n;
	}

	/**
	 * Iterate every empty column (L1 hash = 0 and not in markedFullColumns).
	 * The consumer receives the packed column key. Used by the inline-voxelize
	 * pass that runs after tree (re)build to drain already-loaded chunks
	 * without going through the slow dispatcher path.
	 */
	public void forEachEmptyColumnKey(java.util.function.LongConsumer consumer) {
		for (var entry : l1Hashes.long2LongEntrySet()) {
			if (entry.getLongValue() != 0) continue;
			long colKey = entry.getLongKey();
			if (markedFullColumns.contains(colKey)) continue;
			consumer.accept(colKey);
		}
	}

	/**
	 * Notify the tree that a chunk marker was just written. Tracks per-column
	 * marker count; when a column reaches 4 it's added to markedFullColumns
	 * and getNearestEmptyColumns will skip it from then on.
	 */
	public void notifyChunkMarkerSet(int chunkX, int chunkZ) {
		int sx = chunkX >> 1;
		int sz = chunkZ >> 1;
		long colKey = MerkleHashUtil.packColumnKey(sx, sz);
		if (markedFullColumns.contains(colKey)) return;
		int newCount = markerCounts.addTo(colKey, 1) + 1;
		if (newCount >= 4) {
			markedFullColumns.add(colKey);
			markerCounts.remove(colKey);
			// If we just became fully marker-covered but the column still has
			// no L0 data, this is the dangling fingerprint -- record it so
			// the dispatcher can force-revoxelize. The 20-tick dirty-section
			// debounce will eventually unset this naturally if the data
			// pipeline is healthy (onL0HashChanged removes from the set).
			if (l1Hashes.get(colKey) == 0L) {
				danglingColumns.add(colKey);
			}
		}
	}

	/**
	 * Returns at most `limit` empty-or-dangling columns sorted by squared
	 * distance to (centerX, centerZ). A column is "empty" if its L1 hash is 0
	 * and it is not in markedFullColumns; a column is "dangling" if it IS in
	 * markedFullColumns but its L1 hash is still 0 (markers exist but no L0
	 * data). Both cases need work scheduled, but the dispatcher must call
	 * {@code revoxelizeChunk} for dangling columns (force=true) since
	 * {@code voxelizeNewChunk} would short-circuit on the existing markers.
	 *
	 * Returns triples {x, z, isDangling ? 1L : 0L} so the dispatcher can
	 * dispatch the right code path per column without a second lookup.
	 *
	 * Used by SyncService.dispatchGeneration to find the nearest unscheduled
	 * candidate to the player's CURRENT section -- so generation tracks
	 * player movement, not where they were when the tree was first built.
	 */
	public List<long[]> getNearestEmptyColumns(int centerX, int centerZ, int limit,
			it.unimi.dsi.fastutil.longs.LongOpenHashSet exclude) {
		if (limit <= 0) return java.util.Collections.emptyList();
		// Bounded min-heap keyed by squared distance. Walk all empties, push into heap,
		// pop the worst when over `limit`. O(N log K) where K = limit, much cheaper
		// than sorting the whole set when N >> K (typical: ~263k empties, K=32).
		// Entries are {x, z, d2, isDangling}; we strip isDangling in the
		// output array as the third element after sorting on d2.
		java.util.PriorityQueue<long[]> heap = new java.util.PriorityQueue<>(
			limit + 1,
			(a, b) -> Long.compare(b[2], a[2])  // max-heap: largest dist at top so we can evict
		);
		for (var entry : l1Hashes.long2LongEntrySet()) {
			if (entry.getLongValue() != 0) continue;
			long colKey = entry.getLongKey();
			// Note: we deliberately do NOT skip markedFullColumns here -- we
			// want them when they are dangling. Real-data columns have l1!=0
			// so the entry-value check above already filtered them out.
			boolean isDangling = danglingColumns.contains(colKey);
			if (exclude != null && exclude.contains(colKey)) continue;
			// Skip "covered" columns that aren't dangling (markers set, no
			// data, but no recovery needed -- the data is en route).
			if (markedFullColumns.contains(colKey) && !isDangling) continue;
			int x = MerkleHashUtil.columnKeyX(colKey);
			int z = MerkleHashUtil.columnKeyZ(colKey);
			long dx = x - centerX;
			long dz = z - centerZ;
			long d2 = dx * dx + dz * dz;
			long danglingFlag = isDangling ? 1L : 0L;
			if (heap.size() < limit) {
				heap.add(new long[]{x, z, d2, danglingFlag});
			} else if (d2 < heap.peek()[2]) {
				heap.poll();
				heap.add(new long[]{x, z, d2, danglingFlag});
			}
		}
		// Drain heap; result will be in worst->best order, so reverse for nicer logs.
		long[][] arr = heap.toArray(new long[0][]);
		java.util.Arrays.sort(arr, (a, b) -> Long.compare(a[2], b[2]));
		List<long[]> out = new ArrayList<>(arr.length);
		for (long[] e : arr) {
			out.add(new long[]{e[0], e[1], e[3]});
		}
		return out;
	}

	/**
	 * Whether the given column is currently classified as dangling (markers
	 * fully set but no L0 data). Useful for diagnostics and for
	 * isDangling-aware code paths outside the dispatcher.
	 */
	public boolean isDanglingColumn(long colKey) {
		return danglingColumns.contains(colKey);
	}

	/**
	 * Optimistically clear a column's dangling flag after a successful
	 * force-revoxelize dispatch. The dispatcher otherwise re-picks dangling
	 * columns ~20 times/sec until the 20-tick onSectionDirty debounce
	 * recomputes L1 -- inflating the cps metric and burning genExecutor
	 * cycles on already-recovering chunks. Voxy writes L0 within
	 * milliseconds when its queue is empty, so this clearance is safe in
	 * practice. If voxy unexpectedly fails to write (only possible via
	 * an exception path inside its worker), the column becomes
	 * "marker-covered, no data" until manual {@code /voxysv revoxelize}.
	 */
	public void markDanglingResolved(long colKey) {
		danglingColumns.remove(colKey);
	}

	/**
	 * Total number of dangling columns currently tracked in this tree.
	 * Surfaced via SyncService telemetry.
	 */
	public int getDanglingColumnCount() {
		return danglingColumns.size();
	}

	/**
	 * Find ALL L0 section keys within the tree (for full sync to fresh clients).
	 */
	public List<Long> getAllL0SectionKeys() {
		List<Long> keys = new ArrayList<>();
		for (long key : l0Hashes.keySet()) {
			keys.add(key);
		}
		return keys;
	}

	public long getL3Hash() {
		return l3Hash;
	}

	public long getL0Hash(long sectionKey) {
		return l0Hashes.get(sectionKey);
	}

	public long getL1HashForColumn(long columnKey) {
		return l1Hashes.get(columnKey);
	}

	public boolean isInBounds(int sectionX, int sectionZ) {
		return sectionX >= centerX - radius && sectionX <= centerX + radius
			&& sectionZ >= centerZ - radius && sectionZ <= centerZ + radius;
	}

	public int getCenterX() { return centerX; }
	public int getCenterZ() { return centerZ; }
	public int getRadius() { return radius; }

	/**
	 * Slide the tree's center to a new (sectionX, sectionZ) without doing a
	 * full rebuild. Computes the strips of columns entering and exiting the
	 * radius rectangle, removes the exits, loads the entries from RocksDB,
	 * and recomputes only the L2 regions touched by either set. Falls back
	 * to a full rebuild when the delta exceeds {@code teleportThreshold}
	 * (e.g. /tp, portals) -- the strip-update cost would exceed a from-scratch
	 * rebuild past that point.
	 *
	 * Designed to be invoked from the streamWorker thread (RocksDB iteration
	 * is blocking).
	 */
	public void slideBounds(SectionHashStore store, int newCenterX, int newCenterZ, int teleportThreshold) {
		int delta = Math.max(Math.abs(newCenterX - centerX), Math.abs(newCenterZ - centerZ));
		if (delta == 0) return;
		if (delta > teleportThreshold) {
			rebuildInPlace(store, newCenterX, newCenterZ);
			return;
		}

		int oldMinX = centerX - radius;
		int oldMaxX = centerX + radius;
		int oldMinZ = centerZ - radius;
		int oldMaxZ = centerZ + radius;
		int newMinX = newCenterX - radius;
		int newMaxX = newCenterX + radius;
		int newMinZ = newCenterZ - radius;
		int newMaxZ = newCenterZ + radius;

		int overlapMinX = Math.max(oldMinX, newMinX);
		int overlapMaxX = Math.min(oldMaxX, newMaxX);
		int overlapMinZ = Math.max(oldMinZ, newMinZ);
		int overlapMaxZ = Math.min(oldMaxZ, newMaxZ);

		it.unimi.dsi.fastutil.longs.LongOpenHashSet affectedRegions =
			new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

		// Exit strips: in old bounds but not in new.
		if (oldMaxZ > overlapMaxZ) removeStrip(oldMinX, oldMaxX, overlapMaxZ + 1, oldMaxZ, affectedRegions);
		if (oldMinZ < overlapMinZ) removeStrip(oldMinX, oldMaxX, oldMinZ, overlapMinZ - 1, affectedRegions);
		if (oldMaxX > overlapMaxX) removeStrip(overlapMaxX + 1, oldMaxX, overlapMinZ, overlapMaxZ, affectedRegions);
		if (oldMinX < overlapMinX) removeStrip(oldMinX, overlapMinX - 1, overlapMinZ, overlapMaxZ, affectedRegions);

		// Entry strips: in new bounds but not in old.
		if (newMaxZ > overlapMaxZ) loadStripFromStore(store, newMinX, newMaxX, overlapMaxZ + 1, newMaxZ, affectedRegions);
		if (newMinZ < overlapMinZ) loadStripFromStore(store, newMinX, newMaxX, newMinZ, overlapMinZ - 1, affectedRegions);
		if (newMaxX > overlapMaxX) loadStripFromStore(store, overlapMaxX + 1, newMaxX, overlapMinZ, overlapMaxZ, affectedRegions);
		if (newMinX < overlapMinX) loadStripFromStore(store, newMinX, overlapMinX - 1, overlapMinZ, overlapMaxZ, affectedRegions);

		this.centerX = newCenterX;
		this.centerZ = newCenterZ;

		for (long regKey : affectedRegions) {
			recomputeL2Region(regKey);
		}
		recomputeL3();
	}

	/**
	 * Discard everything and rebuild as if {@link #build} had been called with
	 * the given center. Used as the slideBounds fallback for large deltas.
	 */
	private void rebuildInPlace(SectionHashStore store, int newCenterX, int newCenterZ) {
		l0Hashes.clear();
		l1Hashes.clear();
		l2Hashes.clear();
		columnToSections.clear();
		regionToColumns.clear();
		markedFullColumns.clear();
		markerCounts.clear();
		danglingColumns.clear();
		this.centerX = newCenterX;
		this.centerZ = newCenterZ;

		int minX = newCenterX - radius;
		int maxX = newCenterX + radius;
		int minZ = newCenterZ - radius;
		int maxZ = newCenterZ + radius;

		it.unimi.dsi.fastutil.longs.LongOpenHashSet affectedRegions =
			new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		loadStripFromStore(store, minX, maxX, minZ, maxZ, affectedRegions);
		for (long regKey : affectedRegions) {
			recomputeL2Region(regKey);
		}
		recomputeL3();
	}

	private void removeStrip(int minX, int maxX, int minZ, int maxZ,
			it.unimi.dsi.fastutil.longs.LongOpenHashSet affectedRegions) {
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				removeColumn(x, z, affectedRegions);
			}
		}
	}

	private void removeColumn(int x, int z,
			it.unimi.dsi.fastutil.longs.LongOpenHashSet affectedRegions) {
		long colKey = MerkleHashUtil.packColumnKey(x, z);
		LongArrayList sectionKeys = columnToSections.remove(colKey);
		if (sectionKeys != null) {
			for (int i = 0; i < sectionKeys.size(); i++) {
				l0Hashes.remove(sectionKeys.getLong(i));
			}
		}
		l1Hashes.remove(colKey);
		markedFullColumns.remove(colKey);
		markerCounts.remove(colKey);
		danglingColumns.remove(colKey);

		int rx = MerkleHashUtil.sectionToRegionX(x);
		int rz = MerkleHashUtil.sectionToRegionZ(z);
		long regKey = MerkleHashUtil.packRegionKey(rx, rz);
		LongArrayList cols = regionToColumns.get(regKey);
		if (cols != null) {
			cols.rem(colKey);
			if (cols.isEmpty()) regionToColumns.remove(regKey);
		}
		affectedRegions.add(regKey);
	}

	/**
	 * Load every L0 hash and every chunk marker in a rectangular section-coord
	 * strip from RocksDB into the tree's in-memory maps, populate L1 hashes
	 * (densely, including columns with no L0 data so {@code forEachEmptyColumnKey}
	 * sees them), and mark each touched L2 region for later recompute.
	 *
	 * Mirrors the body of {@link #build} but scoped to the given strip.
	 */
	private void loadStripFromStore(SectionHashStore store, int minX, int maxX, int minZ, int maxZ,
			it.unimi.dsi.fastutil.longs.LongOpenHashSet affectedRegions) {
		store.iterateInBounds(minX, maxX, minZ, maxZ, (sectionKey, hash) -> {
			l0Hashes.put(sectionKey.longValue(), hash.longValue());
			int x = WorldEngine.getX(sectionKey);
			int z = WorldEngine.getZ(sectionKey);
			long colKey = MerkleHashUtil.packColumnKey(x, z);
			columnToSections.computeIfAbsent(colKey, k -> new LongArrayList()).add(sectionKey.longValue());
		});

		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap stripMarkerCount =
			new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		store.iterateMarkersInBounds(minX, maxX, minZ, maxZ, (chunkX, chunkZ) -> {
			int sx = chunkX >> 1;
			int sz = chunkZ >> 1;
			long colKey = MerkleHashUtil.packColumnKey(sx, sz);
			stripMarkerCount.addTo(colKey, 1);
			return 0;
		});
		for (var e : stripMarkerCount.long2IntEntrySet()) {
			if (e.getIntValue() >= 4) {
				markedFullColumns.add(e.getLongKey());
			} else {
				markerCounts.put(e.getLongKey(), e.getIntValue());
			}
		}

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				long colKey = MerkleHashUtil.packColumnKey(x, z);
				LongArrayList sectionKeys = columnToSections.get(colKey);

				long colHash;
				if (sectionKeys != null && !sectionKeys.isEmpty()) {
					long[] hashes = new long[sectionKeys.size()];
					for (int i = 0; i < sectionKeys.size(); i++) {
						hashes[i] = l0Hashes.get(sectionKeys.getLong(i));
					}
					colHash = MerkleHashUtil.hashColumn(hashes);
				} else {
					colHash = 0;
				}
				l1Hashes.put(colKey, colHash);

				// A column is "dangling" iff it is fully marker-covered yet
				// has no L0 data anywhere. Detect now so the dispatcher can
				// force-revoxelize on the next pass.
				if (markedFullColumns.contains(colKey) && colHash == 0L) {
					danglingColumns.add(colKey);
				} else {
					danglingColumns.remove(colKey);
				}

				int rx = MerkleHashUtil.sectionToRegionX(x);
				int rz = MerkleHashUtil.sectionToRegionZ(z);
				long regKey = MerkleHashUtil.packRegionKey(rx, rz);
				LongArrayList cols = regionToColumns.computeIfAbsent(regKey, k -> new LongArrayList());
				if (!cols.contains(colKey)) {
					cols.add(colKey);
				}
				affectedRegions.add(regKey);
			}
		}
	}

	private void recomputeL2Region(long regKey) {
		LongArrayList colKeys = regionToColumns.get(regKey);
		if (colKeys == null || colKeys.isEmpty()) {
			l2Hashes.remove(regKey);
			regionToColumns.remove(regKey);
			return;
		}
		long[] hashes = new long[colKeys.size()];
		for (int i = 0; i < colKeys.size(); i++) {
			hashes[i] = l1Hashes.get(colKeys.getLong(i));
		}
		l2Hashes.put(regKey, MerkleHashUtil.hashRegion(hashes));
	}

	private void recomputeL3() {
		long[] l2Values = l2Hashes.values().toLongArray();
		l3Hash = MerkleHashUtil.hashRoot(l2Values);
	}
}
