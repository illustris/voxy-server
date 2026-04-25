package me.cortex.voxy.server.merkle;

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
 */
public class SectionHashStore {
	private final RocksDB db;

	public SectionHashStore(Path storagePath) {
		try {
			Files.createDirectories(storagePath);
			var options = new Options()
				.setCreateIfMissing(true)
				.setMaxOpenFiles(64);
			this.db = RocksDB.open(options, storagePath.toString());
		} catch (Exception e) {
			throw new RuntimeException("Failed to open SectionHashStore at " + storagePath, e);
		}
	}

	public void putHash(long sectionKey, long hash) {
		try {
			db.put(longToBytes(sectionKey), longToBytes(hash));
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to store section hash for key {}", sectionKey, e);
		}
	}

	/**
	 * Returns the stored hash, or 0 if absent.
	 */
	public long getHash(long sectionKey) {
		try {
			byte[] value = db.get(longToBytes(sectionKey));
			if (value != null && value.length >= 8) {
				return ByteBuffer.wrap(value).getLong();
			}
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to read section hash for key {}", sectionKey, e);
		}
		return 0;
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
				if (getHash(markerKey) == 0) {
					missing.add(new ChunkPos(chunkX, chunkZ));
				}
			}
		}
		return missing;
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
