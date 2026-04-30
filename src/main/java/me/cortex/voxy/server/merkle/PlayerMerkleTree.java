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

	// Bounds
	private final int centerX, centerZ, radius;

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
		l1Hashes.put(colKey, MerkleHashUtil.hashColumn(hashes));

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
		}
	}

	/**
	 * Returns at most `limit` empty columns (l1Hash=0, not marked fully voxelized,
	 * not in `exclude`), sorted by squared distance to (centerX, centerZ).
	 *
	 * Used by SyncService.dispatchGeneration to find the nearest unscheduled
	 * empty columns to the player's CURRENT section -- so generation tracks
	 * player movement, not where they were when the tree was first built.
	 */
	public List<long[]> getNearestEmptyColumns(int centerX, int centerZ, int limit,
			it.unimi.dsi.fastutil.longs.LongOpenHashSet exclude) {
		if (limit <= 0) return java.util.Collections.emptyList();
		// Bounded min-heap keyed by squared distance. Walk all empties, push into heap,
		// pop the worst when over `limit`. O(N log K) where K = limit, much cheaper
		// than sorting the whole set when N >> K (typical: ~263k empties, K=32).
		java.util.PriorityQueue<long[]> heap = new java.util.PriorityQueue<>(
			limit + 1,
			(a, b) -> Long.compare(b[2], a[2])  // max-heap: largest dist at top so we can evict
		);
		for (var entry : l1Hashes.long2LongEntrySet()) {
			if (entry.getLongValue() != 0) continue;
			long colKey = entry.getLongKey();
			if (markedFullColumns.contains(colKey)) continue;
			if (exclude != null && exclude.contains(colKey)) continue;
			int x = MerkleHashUtil.columnKeyX(colKey);
			int z = MerkleHashUtil.columnKeyZ(colKey);
			long dx = x - centerX;
			long dz = z - centerZ;
			long d2 = dx * dx + dz * dz;
			if (heap.size() < limit) {
				heap.add(new long[]{x, z, d2});
			} else if (d2 < heap.peek()[2]) {
				heap.poll();
				heap.add(new long[]{x, z, d2});
			}
		}
		// Drain heap; result will be in worst->best order, so reverse for nicer logs.
		long[][] arr = heap.toArray(new long[0][]);
		java.util.Arrays.sort(arr, (a, b) -> Long.compare(a[2], b[2]));
		List<long[]> out = new ArrayList<>(arr.length);
		for (long[] e : arr) {
			out.add(new long[]{e[0], e[1]});
		}
		return out;
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
}
