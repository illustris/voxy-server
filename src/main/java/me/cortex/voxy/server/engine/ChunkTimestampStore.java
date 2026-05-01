package me.cortex.voxy.server.engine;

import me.cortex.voxy.server.VoxyServerMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.lwjgl.system.MemoryUtil;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists per-chunk timestamps for block updates and voxelization.
 * Key: packed (chunkX, chunkZ) as 8 bytes
 * Value: lastBlockUpdateTick (8 bytes) + lastVoxelizationTick (8 bytes)
 */
public class ChunkTimestampStore {
	public record BlockChangeInfo(int x, int y, int z, String oldState, String newState, int count) {}

	private static volatile ChunkTimestampStore GLOBAL_INSTANCE;

	private final RocksDB db;
	private final Path dbPath;
	private final ConcurrentHashMap<Long, BlockChangeInfo> lastBlockChanges = new ConcurrentHashMap<>();

	public ChunkTimestampStore(Path storagePath) {
		this.dbPath = storagePath;
		try {
			Files.createDirectories(storagePath);
			var options = new Options()
				.setCreateIfMissing(true)
				.setMaxOpenFiles(64);
			this.db = RocksDB.open(options, storagePath.toString());
		} catch (Exception e) {
			throw new RuntimeException("Failed to open ChunkTimestampStore at " + storagePath, e);
		}
	}

	public static void setGlobalInstance(ChunkTimestampStore instance) {
		GLOBAL_INSTANCE = instance;
	}

	/**
	 * Called from the mixin when a block changes.
	 */
	public static void onBlockChanged(ServerLevel level, BlockPos pos, String oldState, String newState) {
		ChunkTimestampStore store = GLOBAL_INSTANCE;
		if (store == null) return;
		int chunkX = pos.getX() >> 4;
		int chunkZ = pos.getZ() >> 4;
		long currentTick = level.getServer().getTickCount();
		store.markBlockUpdated(chunkX, chunkZ, currentTick);
		store.recordBlockChange(chunkX, chunkZ, pos.getX(), pos.getY(), pos.getZ(), oldState, newState);
	}

	public void markBlockUpdated(int chunkX, int chunkZ, long currentTick) {
		byte[] key = packKey(chunkX, chunkZ);
		try {
			byte[] existing = db.get(key);
			long lastVoxTick = 0;
			if (existing != null && existing.length >= 16) {
				lastVoxTick = ByteBuffer.wrap(existing, 8, 8).getLong();
			}
			byte[] value = new byte[16];
			ByteBuffer.wrap(value).putLong(currentTick).putLong(lastVoxTick);
			db.put(key, value);
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to mark block updated at chunk ({}, {})", chunkX, chunkZ, e);
		}
	}

	/**
	 * Called at voxelization invocation time (BEFORE it runs, not on completion).
	 * Only updates if there's an existing record (i.e., a block change was previously
	 * recorded). This prevents creating records for chunks during initial generation,
	 * where setBlockState fires from worldgen but isn't a player edit.
	 */
	public void markVoxelizationStarted(int chunkX, int chunkZ, long invocationTick) {
		byte[] key = packKey(chunkX, chunkZ);
		try {
			byte[] existing = db.get(key);
			if (existing == null || existing.length < 16) {
				// No prior block update recorded -- this is initial generation, skip
				return;
			}
			long lastBlockTick = ByteBuffer.wrap(existing, 0, 8).getLong();
			byte[] value = new byte[16];
			ByteBuffer.wrap(value).putLong(lastBlockTick).putLong(invocationTick);
			db.put(key, value);
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to mark voxelization started at chunk ({}, {})", chunkX, chunkZ, e);
		}
	}

	/**
	 * On voxelization completion: remove record if no block change since invocation.
	 */
	public void tryCleanRecord(int chunkX, int chunkZ) {
		byte[] key = packKey(chunkX, chunkZ);
		try {
			byte[] existing = db.get(key);
			if (existing == null || existing.length < 16) return;
			ByteBuffer buf = ByteBuffer.wrap(existing);
			long lastBlockTick = buf.getLong();
			long lastVoxTick = buf.getLong();
			if (lastBlockTick <= lastVoxTick) {
				db.delete(key);
			}
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to clean timestamp record at chunk ({}, {})", chunkX, chunkZ, e);
		}
	}

	/**
	 * Drop the record for a chunk entirely. Called by DirtyScanService when a
	 * dirty chunk turns out to be unloaded -- otherwise the record sits at the
	 * head of the RocksDB iteration order and {@link #findDirtyChunks} keeps
	 * returning it every cycle, head-of-line-blocking the scan against fresh
	 * edits at the tail. Pairs with the {@code onChunkLoad} self-heal in
	 * ChunkVoxelizer: if the chunk loads back in later it'll be force-
	 * revoxelized then.
	 */
	public void deleteRecord(int chunkX, int chunkZ) {
		try {
			db.delete(packKey(chunkX, chunkZ));
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to delete timestamp record at chunk ({}, {})", chunkX, chunkZ, e);
		}
	}

	/**
	 * Whether the chunk has a pending block-edit dirty bit, i.e.
	 * {@code lastBlockTick > lastVoxTick}. Used by ChunkVoxelizer.onChunkLoad
	 * to self-heal records that were dropped by the scan path while the
	 * chunk was unloaded: any chunk that comes back in with a real edit
	 * waiting gets a force-revoxelize regardless of its per-chunk marker.
	 */
	public boolean isChunkDirty(int chunkX, int chunkZ) {
		try {
			byte[] existing = db.get(packKey(chunkX, chunkZ));
			if (existing == null || existing.length < 16) return false;
			ByteBuffer buf = ByteBuffer.wrap(existing);
			long lastBlockTick = buf.getLong();
			long lastVoxTick = buf.getLong();
			return lastBlockTick > lastVoxTick;
		} catch (RocksDBException e) {
			VoxyServerMod.LOGGER.warn("Failed to read timestamp record at chunk ({}, {})", chunkX, chunkZ, e);
			return false;
		}
	}

	/**
	 * Returns chunks where lastBlockUpdateTick > lastVoxelizationTick.
	 */
	public List<ChunkPos> findDirtyChunks(int limit) {
		List<ChunkPos> dirty = new ArrayList<>();
		try (RocksIterator iter = db.newIterator()) {
			iter.seekToFirst();
			while (iter.isValid() && dirty.size() < limit) {
				byte[] key = iter.key();
				byte[] value = iter.value();
				if (key.length >= 8 && value.length >= 16) {
					ByteBuffer valBuf = ByteBuffer.wrap(value);
					long lastBlockTick = valBuf.getLong();
					long lastVoxTick = valBuf.getLong();
					if (lastBlockTick > lastVoxTick) {
						ByteBuffer keyBuf = ByteBuffer.wrap(key);
						int chunkX = keyBuf.getInt();
						int chunkZ = keyBuf.getInt();
						dirty.add(new ChunkPos(chunkX, chunkZ));
					}
				}
				iter.next();
			}
		}
		return dirty;
	}

	private void recordBlockChange(int chunkX, int chunkZ, int x, int y, int z, String oldState, String newState) {
		long key = packMapKey(chunkX, chunkZ);
		lastBlockChanges.merge(key,
			new BlockChangeInfo(x, y, z, oldState, newState, 1),
			(existing, incoming) -> new BlockChangeInfo(x, y, z, oldState, newState, existing.count() + 1)
		);
	}

	/**
	 * Returns and removes the last block change info for the given chunk.
	 * Used by DirtyScanService to include trigger details in debug logs.
	 */
	public BlockChangeInfo getAndClearBlockChangeInfo(int chunkX, int chunkZ) {
		return lastBlockChanges.remove(packMapKey(chunkX, chunkZ));
	}

	public void close() {
		db.close();
	}

	private static byte[] packKey(int chunkX, int chunkZ) {
		byte[] key = new byte[8];
		ByteBuffer.wrap(key).putInt(chunkX).putInt(chunkZ);
		return key;
	}

	private static long packMapKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}
}
