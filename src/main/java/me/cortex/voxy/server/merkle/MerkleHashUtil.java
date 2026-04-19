package me.cortex.voxy.server.merkle;

import me.cortex.voxy.common.world.WorldEngine;
import net.openhft.hashing.LongHashFunction;

import java.nio.ByteBuffer;

/**
 * Utility methods for computing Merkle tree hashes at each level.
 * Uses xxHash64 for fast non-cryptographic hashing.
 */
public final class MerkleHashUtil {
	private static final LongHashFunction XX_HASH = LongHashFunction.xx();

	private MerkleHashUtil() {}

	/**
	 * Compute L0 hash from serialized section data bytes.
	 */
	public static long hashSectionData(byte[] data, int offset, int length) {
		return XX_HASH.hashBytes(data, offset, length);
	}

	/**
	 * Compute L0 hash from a direct memory buffer.
	 */
	public static long hashSectionData(long address, int length) {
		return XX_HASH.hashMemory(address, length);
	}

	/**
	 * Compute L1 (column) hash from all L0 hashes at a given (x,z) position across all Y values.
	 * Input: array of L0 hashes sorted by Y coordinate.
	 */
	public static long hashColumn(long[] l0Hashes) {
		if (l0Hashes == null || l0Hashes.length == 0) return 0;
		byte[] buf = new byte[l0Hashes.length * 8];
		ByteBuffer bb = ByteBuffer.wrap(buf);
		for (long h : l0Hashes) {
			bb.putLong(h);
		}
		return XX_HASH.hashBytes(buf);
	}

	/**
	 * Compute L2 (region) hash from L1 hashes.
	 * The region covers a 32x32 grid of L1 columns.
	 */
	public static long hashRegion(long[] l1Hashes) {
		if (l1Hashes == null || l1Hashes.length == 0) return 0;
		byte[] buf = new byte[l1Hashes.length * 8];
		ByteBuffer bb = ByteBuffer.wrap(buf);
		for (long h : l1Hashes) {
			bb.putLong(h);
		}
		return XX_HASH.hashBytes(buf);
	}

	/**
	 * Compute L3 (root) hash from all L2 region hashes.
	 */
	public static long hashRoot(long[] l2Hashes) {
		if (l2Hashes == null || l2Hashes.length == 0) return 0;
		byte[] buf = new byte[l2Hashes.length * 8];
		ByteBuffer bb = ByteBuffer.wrap(buf);
		for (long h : l2Hashes) {
			bb.putLong(h);
		}
		return XX_HASH.hashBytes(buf);
	}

	/**
	 * Pack an (x, z) position into a column key for L1.
	 * x, z are in level-0 section coordinates.
	 */
	public static long packColumnKey(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static int columnKeyX(long key) {
		return (int) (key >> 32);
	}

	public static int columnKeyZ(long key) {
		return (int) key;
	}

	/**
	 * Pack region coordinates into a region key for L2.
	 * Region coords are section coords >> 5 (each region = 32x32 L1 columns).
	 * L1 column = 2x2 chunks = 32x32 blocks. 32 columns = 64x64 chunks = 1024x1024 blocks.
	 */
	public static long packRegionKey(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
	}

	public static int regionKeyX(long key) {
		return (int) (key >> 32);
	}

	public static int regionKeyZ(long key) {
		return (int) key;
	}

	/**
	 * Convert section coordinates to region coordinates.
	 * Each region covers 32 columns in each XZ direction.
	 */
	public static int sectionToRegionX(int sectionX) {
		return sectionX >> 5;
	}

	public static int sectionToRegionZ(int sectionZ) {
		return sectionZ >> 5;
	}
}
