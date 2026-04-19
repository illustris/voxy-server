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

		// Load all L0 hashes in bounds
		store.iterateInBounds(minX, maxX, minZ, maxZ, (sectionKey, hash) -> {
			tree.l0Hashes.put(sectionKey.longValue(), hash.longValue());

			int x = WorldEngine.getX(sectionKey);
			int z = WorldEngine.getZ(sectionKey);
			long colKey = MerkleHashUtil.packColumnKey(x, z);
			tree.columnToSections.computeIfAbsent(colKey, k -> new LongArrayList()).add(sectionKey.longValue());

			int rx = MerkleHashUtil.sectionToRegionX(x);
			int rz = MerkleHashUtil.sectionToRegionZ(z);
			long regKey = MerkleHashUtil.packRegionKey(rx, rz);
			LongArrayList cols = tree.regionToColumns.computeIfAbsent(regKey, k -> new LongArrayList());
			if (!cols.contains(colKey)) {
				cols.add(colKey);
			}
		});

		// Compute L1 hashes
		for (var entry : tree.columnToSections.long2ObjectEntrySet()) {
			long colKey = entry.getLongKey();
			LongArrayList sectionKeys = entry.getValue();
			long[] hashes = new long[sectionKeys.size()];
			for (int i = 0; i < sectionKeys.size(); i++) {
				hashes[i] = tree.l0Hashes.get(sectionKeys.getLong(i));
			}
			tree.l1Hashes.put(colKey, MerkleHashUtil.hashColumn(hashes));
		}

		// Compute L2 hashes
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
	 * Compare client's L1 hashes against ours for given regions.
	 * Returns list of L0 section keys that differ.
	 */
	public List<Long> findDifferingL0Sections(Long2ObjectOpenHashMap<Long2LongOpenHashMap> clientL1ByRegion) {
		List<Long> differing = new ArrayList<>();

		for (var regionEntry : clientL1ByRegion.long2ObjectEntrySet()) {
			long regionKey = regionEntry.getLongKey();
			Long2LongOpenHashMap clientL1 = regionEntry.getValue();
			Long2LongOpenHashMap serverL1 = getL1HashesForRegion(regionKey);

			// Find columns that differ
			for (var colEntry : serverL1.long2LongEntrySet()) {
				long colKey = colEntry.getLongKey();
				long serverHash = colEntry.getLongValue();
				long clientHash = clientL1.get(colKey);

				if (serverHash != clientHash) {
					// All L0 sections in this column need to be sent
					LongArrayList sectionKeys = columnToSections.get(colKey);
					if (sectionKeys != null) {
						for (int i = 0; i < sectionKeys.size(); i++) {
							differing.add(sectionKeys.getLong(i));
						}
					}
				}
			}

			// Also check for columns server has that client doesn't
			for (var colEntry : clientL1.long2LongEntrySet()) {
				long colKey = colEntry.getLongKey();
				if (!serverL1.containsKey(colKey) && colEntry.getLongValue() != 0) {
					// Client has data for a column server doesn't -- could signal deletion
					// For now, skip (server is authoritative)
				}
			}
		}

		return differing;
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
