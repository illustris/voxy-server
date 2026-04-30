package me.cortex.voxy.server.merkle;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.server.VoxyServerMod;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import net.minecraft.world.level.ChunkPos;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Persists L0 section hashes in a RocksDB instance.
 * Key: sectionKey (8 bytes)
 * Value: xxHash64 (8 bytes)
 *
 * Per-chunk voxelization markers (level=15 namespace, value=1) are also stored
 * here, in addition to L0 section hashes (level=0).
 *
 * <p>Hot path: every block change re-hashes its section, calling
 * {@link #getHash} to compare against the prior value and {@link #putHash} to
 * store the new one. To keep this off RocksDB, an in-memory
 * {@link Long2LongOpenHashMap} caches level=0 entries up to a configurable byte
 * cap. Markers (level=15) and other-level hashes go straight to RocksDB.
 *
 * <p>RocksDB block cache: left at the library default. We deliberately do not
 * construct an {@link org.rocksdb.LRUCache} here -- the rocksdbjni native
 * library version actually loaded at runtime is whatever Voxy (or another
 * Fabric mod) bundled, and not every version exposes the
 * {@code newLRUCache(long,int,boolean,double,double)} JNI symbol the
 * 10.2.1 wrapper expects. Touching {@code LRUCache} would crash the server
 * on those installations. The L0 hash cache above absorbs the hot path; the
 * default block cache is fine for the residual cold-read traffic.
 */
public class SectionHashStore {
	// fastutil Long2LongOpenHashMap with default 0.75 load factor stores ~16
	// bytes/entry once you account for both arrays plus alignment overhead.
	private static final int BYTES_PER_ENTRY = 16;

	private final RocksDB db;
	private final Long2LongOpenHashMap l0Cache;
	private volatile long l0CacheCapBytes;

	public SectionHashStore(Path storagePath, long l0CacheCapBytes) {
		this.l0CacheCapBytes = l0CacheCapBytes;
		this.l0Cache = new Long2LongOpenHashMap();
		// fastutil returns 0 on missing key by default, which is convenient:
		// 0 is also our sentinel for "no stored hash", so map.get(k) returns
		// the same value whether the key is absent or stored-as-zero (which
		// shouldn't happen for real hashes -- xxHash64(real data) != 0 with
		// overwhelming probability).
		this.l0Cache.defaultReturnValue(0L);
		try {
			Files.createDirectories(storagePath);
			var options = new Options()
				.setCreateIfMissing(true)
				.setMaxOpenFiles(64);
			this.db = RocksDB.open(options, storagePath.toString());
			VoxyServerMod.LOGGER.info(
				"[SectionHashStore] Opened: l0CacheCap={} MB (~{} entries)",
				l0CacheCapBytes >> 20,
				cacheCapEntries());
		} catch (Exception e) {
			throw new RuntimeException("Failed to open SectionHashStore at " + storagePath, e);
		}
	}

	private int cacheCapEntries() {
		long cap = l0CacheCapBytes / BYTES_PER_ENTRY;
		if (cap > Integer.MAX_VALUE) return Integer.MAX_VALUE;
		return (int) cap;
	}

	public void putHash(long sectionKey, long hash) {
		try {
			db.put(longToBytes(sectionKey), longToBytes(hash));
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to store section hash for key {}", sectionKey, e);
			return;
		}
		if (WorldEngine.getLevel(sectionKey) == 0) {
			synchronized (l0Cache) {
				if (l0Cache.size() < cacheCapEntries() || l0Cache.containsKey(sectionKey)) {
					l0Cache.put(sectionKey, hash);
				}
			}
		}
	}

	/**
	 * Returns the stored hash, or 0 if absent.
	 */
	public long getHash(long sectionKey) {
		boolean isL0 = WorldEngine.getLevel(sectionKey) == 0;
		if (isL0) {
			long cached;
			synchronized (l0Cache) {
				cached = l0Cache.get(sectionKey);
			}
			if (cached != 0L) return cached;
		}
		long onDisk = readFromRocksDB(sectionKey);
		if (isL0 && onDisk != 0L) {
			synchronized (l0Cache) {
				if (l0Cache.size() < cacheCapEntries()) {
					l0Cache.put(sectionKey, onDisk);
				}
			}
		}
		return onDisk;
	}

	private long readFromRocksDB(long sectionKey) {
		try {
			byte[] value = db.get(longToBytes(sectionKey));
			if (value != null && value.length >= 8) {
				return ByteBuffer.wrap(value).getLong();
			}
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to read section hash for key {}", sectionKey, e);
		}
		return 0L;
	}

	/**
	 * Iterate all stored hashes. Consumer receives (sectionKey, hash).
	 */
	public void iterateAll(BiConsumer<Long, Long> consumer) {
		try (RocksIterator iter = db.newIterator()) {
			iter.seekToFirst();
			while (iter.isValid()) {
				byte[] key = iter.key();
				byte[] value = iter.value();
				if (key.length >= 8 && value.length >= 8) {
					long sectionKey = ByteBuffer.wrap(key).getLong();
					long hash = ByteBuffer.wrap(value).getLong();
					consumer.accept(sectionKey, hash);
				}
				iter.next();
			}
		}
	}

	/**
	 * Iterate hashes within XZ bounds (in level-0 section coordinates).
	 * Since RocksDB keys are ordered, we iterate all and filter.
	 */
	public void iterateInBounds(int minX, int maxX, int minZ, int maxZ, BiConsumer<Long, Long> consumer) {
		try (RocksIterator iter = db.newIterator()) {
			iter.seekToFirst();
			while (iter.isValid()) {
				byte[] key = iter.key();
				byte[] value = iter.value();
				if (key.length >= 8 && value.length >= 8) {
					long sectionKey = ByteBuffer.wrap(key).getLong();
					int level = WorldEngine.getLevel(sectionKey);
					if (level == 0) {
						int x = WorldEngine.getX(sectionKey);
						int z = WorldEngine.getZ(sectionKey);
						if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) {
							long hash = ByteBuffer.wrap(value).getLong();
							consumer.accept(sectionKey, hash);
						}
					}
				}
				iter.next();
			}
		}
	}

	/**
	 * Iterate per-chunk voxelization markers (level=15 namespace) inside the
	 * given level-0 SECTION bounds. Each chunk x is in [minSectionX*2 .. maxSectionX*2+1].
	 * The consumer receives (chunkX, chunkZ) for each marker present.
	 *
	 * Used by tree build to learn which chunks are already voxelized so the
	 * tree can mark fully-covered columns as "no work needed" even when their
	 * L0 hashes haven't been written yet (debounce window).
	 */
	public void iterateMarkersInBounds(int minSectionX, int maxSectionX, int minSectionZ, int maxSectionZ,
			java.util.function.IntBinaryOperator consumer) {
		int minChunkX = minSectionX * 2;
		int maxChunkX = maxSectionX * 2 + 1;
		int minChunkZ = minSectionZ * 2;
		int maxChunkZ = maxSectionZ * 2 + 1;
		try (RocksIterator iter = db.newIterator()) {
			iter.seekToFirst();
			while (iter.isValid()) {
				byte[] key = iter.key();
				if (key.length >= 8) {
					long sectionKey = ByteBuffer.wrap(key).getLong();
					int level = WorldEngine.getLevel(sectionKey);
					if (level == 15) {
						int x = WorldEngine.getX(sectionKey);
						int z = WorldEngine.getZ(sectionKey);
						if (x >= minChunkX && x <= maxChunkX && z >= minChunkZ && z <= maxChunkZ) {
							consumer.applyAsInt(x, z);
						}
					}
				}
				iter.next();
			}
		}
	}

	/**
	 * Check which of the 4 per-chunk markers (level=15 namespace) are absent
	 * for the 2x2 chunks covering a WorldSection at (sectionX, sectionZ).
	 * Returns the ChunkPos values that still need voxelization.
	 * An empty list means the section is complete.
	 */
	public List<ChunkPos> getMissingChunksForSection(int sectionX, int sectionZ) {
		List<ChunkPos> missing = new ArrayList<>();
		int baseChunkX = sectionX * 2;
		int baseChunkZ = sectionZ * 2;
		for (int dx = 0; dx < 2; dx++) {
			for (int dz = 0; dz < 2; dz++) {
				int chunkX = baseChunkX + dx;
				int chunkZ = baseChunkZ + dz;
				long markerKey = WorldEngine.getWorldSectionId(15, chunkX, 0, chunkZ);
				if (getHash(markerKey) == 0L) {
					missing.add(new ChunkPos(chunkX, chunkZ));
				}
			}
		}
		return missing;
	}

	// --- L0 cache management (driven by /voxysv l0Cache commands) ---

	public int getCachedHashCount() {
		synchronized (l0Cache) {
			return l0Cache.size();
		}
	}

	public long getCachedHashBytes() {
		return (long) getCachedHashCount() * BYTES_PER_ENTRY;
	}

	public long getCacheCapBytes() {
		return l0CacheCapBytes;
	}

	/**
	 * Adjust the cache cap. If the new cap is smaller than the current cache,
	 * evicts arbitrary entries until under the new cap. Future inserts respect
	 * the new cap.
	 */
	public void setCacheCapBytes(long newCapBytes) {
		this.l0CacheCapBytes = newCapBytes;
		int newEntryCap = cacheCapEntries();
		synchronized (l0Cache) {
			if (l0Cache.size() > newEntryCap) {
				var iter = l0Cache.keySet().iterator();
				while (l0Cache.size() > newEntryCap && iter.hasNext()) {
					iter.nextLong();
					iter.remove();
				}
			}
		}
	}

	public void flushCache() {
		synchronized (l0Cache) {
			l0Cache.clear();
		}
	}

	public void close() {
		db.close();
	}

	private static byte[] longToBytes(long value) {
		byte[] bytes = new byte[8];
		ByteBuffer.wrap(bytes).putLong(value);
		return bytes;
	}
}
