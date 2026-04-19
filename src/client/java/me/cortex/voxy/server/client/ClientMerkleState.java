package me.cortex.voxy.server.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.server.merkle.MerkleHashUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side Merkle state. Maintains L0/L1/L2 hashes locally so the client
 * can compare against server hashes and respond with mismatches.
 */
public class ClientMerkleState {
	// L0: sectionKey -> hash
	private final Long2LongOpenHashMap l0Hashes = new Long2LongOpenHashMap();
	// L1: columnKey -> hash
	private final Long2LongOpenHashMap l1Hashes = new Long2LongOpenHashMap();
	// L2: regionKey -> hash
	private final Long2LongOpenHashMap l2Hashes = new Long2LongOpenHashMap();

	// Index: columnKey -> list of sectionKeys
	private final Long2ObjectOpenHashMap<LongArrayList> columnToSections = new Long2ObjectOpenHashMap<>();
	// Index: regionKey -> list of columnKeys
	private final Long2ObjectOpenHashMap<LongArrayList> regionToColumns = new Long2ObjectOpenHashMap<>();

	public ClientMerkleState() {
		l0Hashes.defaultReturnValue(0);
		l1Hashes.defaultReturnValue(0);
		l2Hashes.defaultReturnValue(0);
	}

	/**
	 * Update a single L0 hash and recompute affected L1/L2.
	 */
	public void updateL0Hash(long sectionKey, long hash) {
		l0Hashes.put(sectionKey, hash);

		int x = WorldEngine.getX(sectionKey);
		int z = WorldEngine.getZ(sectionKey);
		long colKey = MerkleHashUtil.packColumnKey(x, z);

		LongArrayList sectionKeys = columnToSections.computeIfAbsent(colKey, k -> new LongArrayList());
		if (!sectionKeys.contains(sectionKey)) {
			sectionKeys.add(sectionKey);
		}

		// Recompute L1
		long[] hashes = new long[sectionKeys.size()];
		for (int i = 0; i < sectionKeys.size(); i++) {
			hashes[i] = l0Hashes.get(sectionKeys.getLong(i));
		}
		l1Hashes.put(colKey, MerkleHashUtil.hashColumn(hashes));

		// Recompute L2
		int rx = MerkleHashUtil.sectionToRegionX(x);
		int rz = MerkleHashUtil.sectionToRegionZ(z);
		long regKey = MerkleHashUtil.packRegionKey(rx, rz);
		LongArrayList colKeys = regionToColumns.computeIfAbsent(regKey, k -> new LongArrayList());
		if (!colKeys.contains(colKey)) {
			colKeys.add(colKey);
		}
		long[] l1h = new long[colKeys.size()];
		for (int i = 0; i < colKeys.size(); i++) {
			l1h[i] = l1Hashes.get(colKeys.getLong(i));
		}
		l2Hashes.put(regKey, MerkleHashUtil.hashRegion(l1h));
	}

	/**
	 * Batch update L1 hashes from server (MerkleHashUpdatePayload).
	 */
	public void updateL1Hashes(long[] columnKeys, long[] columnHashes) {
		for (int i = 0; i < columnKeys.length; i++) {
			l1Hashes.put(columnKeys[i], columnHashes[i]);
		}
		// Recompute affected L2 regions
		// (simplified: just recompute all affected regions)
		for (int i = 0; i < columnKeys.length; i++) {
			long colKey = columnKeys[i];
			int x = MerkleHashUtil.columnKeyX(colKey);
			int z = MerkleHashUtil.columnKeyZ(colKey);
			int rx = MerkleHashUtil.sectionToRegionX(x);
			int rz = MerkleHashUtil.sectionToRegionZ(z);
			long regKey = MerkleHashUtil.packRegionKey(rx, rz);

			LongArrayList colKeys = regionToColumns.computeIfAbsent(regKey, k -> new LongArrayList());
			if (!colKeys.contains(colKey)) {
				colKeys.add(colKey);
			}
			long[] l1h = new long[colKeys.size()];
			for (int j = 0; j < colKeys.size(); j++) {
				l1h[j] = l1Hashes.get(colKeys.getLong(j));
			}
			l2Hashes.put(regKey, MerkleHashUtil.hashRegion(l1h));
		}
	}

	/**
	 * Compare server L2 hashes against ours. Returns mismatched region keys.
	 */
	public List<Long> findMismatchedRegions(long[] serverRegionKeys, long[] serverRegionHashes) {
		List<Long> mismatched = new ArrayList<>();
		for (int i = 0; i < serverRegionKeys.length; i++) {
			long ourHash = l2Hashes.get(serverRegionKeys[i]);
			if (ourHash != serverRegionHashes[i]) {
				mismatched.add(serverRegionKeys[i]);
			}
		}
		return mismatched;
	}

	/**
	 * Get our L1 hashes for a specific region (to send to server for comparison).
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

	public void clear() {
		l0Hashes.clear();
		l1Hashes.clear();
		l2Hashes.clear();
		columnToSections.clear();
		regionToColumns.clear();
	}
}
