package me.cortex.voxy.server.engine;

import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.merkle.SectionHashStore;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class ServerLodEngine extends VoxyInstance {
	@FunctionalInterface
	public interface DirtySectionListener {
		void onSectionDirty(Identifier dimension, long sectionKey);
	}

	private final Path basePath;
	private final SectionSerializationStorage.Config storageConfig;
	private final ConcurrentHashMap<WorldIdentifier, Identifier> dimensionsByWorld = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<WorldIdentifier, StoredSectionPresenceIndex> presenceIndexes = new ConcurrentHashMap<>();
	private final ExecutorService presenceIndexExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "VoxyServer Presence Index");
		t.setDaemon(true);
		return t;
	});
	private volatile DirtySectionListener dirtySectionListener;

	private final SectionHashStore sectionHashStore;
	private final ChunkTimestampStore chunkTimestampStore;

	public ServerLodEngine(Path worldFolder) {
		super();
		this.basePath = worldFolder.resolve("voxy-server");
		this.storageConfig = StorageConfigUtil.createDefaultSerializer();

		Path metadataPath = this.basePath.resolve("metadata");
		this.sectionHashStore = new SectionHashStore(metadataPath.resolve("section_hashes"));
		this.chunkTimestampStore = new ChunkTimestampStore(metadataPath.resolve("chunk_timestamps"));
		ChunkTimestampStore.setGlobalInstance(this.chunkTimestampStore);

		this.updateDedicatedThreads();
		VoxyServerMod.LOGGER.info("Server LoD engine started, storage at {}", this.basePath);
	}

	public void updateDedicatedThreadsCount(int threads) {
		this.setNumThreads(threads);
	}

	public void setDirtySectionListener(DirtySectionListener listener) {
		this.dirtySectionListener = listener;
	}

	public SectionHashStore getSectionHashStore() {
		return this.sectionHashStore;
	}

	public ChunkTimestampStore getChunkTimestampStore() {
		return this.chunkTimestampStore;
	}

	public WorldEngine getOrCreate(ServerLevel level) {
		WorldIdentifier worldId = WorldIdentifier.of(level);
		if (worldId == null) {
			return null;
		}
		return this.getOrCreate(worldId, level.dimension().identifier());
	}

	public WorldEngine getOrCreate(WorldIdentifier identifier, Identifier dimension) {
		if (identifier == null || !this.isRunning()) {
			return null;
		}
		this.dimensionsByWorld.put(identifier, dimension);
		WorldEngine world;
		try {
			world = super.getOrCreate(identifier);
		} catch (Exception e) {
			VoxyServerMod.LOGGER.error("Could not get or create world for {}", identifier, e);
			return null;
		}
		if (world == null) {
			return null;
		}
		this.attachDirtyCallback(identifier, world);
		this.ensurePresenceIndex(identifier, world);
		return world;
	}

	@Override
	public WorldEngine getOrCreate(WorldIdentifier identifier) {
		if (!this.isRunning()) {
			return null;
		}
		WorldEngine world;
		try {
			world = super.getOrCreate(identifier);
		} catch (Exception e) {
			VoxyServerMod.LOGGER.error("Could not get or create world for {}", identifier, e);
			return null;
		}
		if (world == null) {
			return null;
		}
		this.attachDirtyCallback(identifier, world);
		this.ensurePresenceIndex(identifier, world);
		return world;
	}

	public boolean mayHaveStoredSection(WorldIdentifier identifier, WorldEngine world, long sectionKey) {
		if (identifier == null || world == null || WorldEngine.getLevel(sectionKey) != 0) {
			return true;
		}
		return this.ensurePresenceIndex(identifier, world).mayContain(sectionKey);
	}

	public void markChunkPossiblyPresent(ServerLevel level, LevelChunk chunk) {
		WorldIdentifier identifier = WorldIdentifier.of(level);
		if (identifier == null) {
			return;
		}

		StoredSectionPresenceIndex index = this.presenceIndexes.get(identifier);
		if (index == null) {
			return;
		}

		int worldSecX = chunk.getPos().x() >> 1;
		int worldSecZ = chunk.getPos().z() >> 1;
		int chunkSectionY = chunk.getMinSectionY() - 1;
		int lastWorldSecY = Integer.MIN_VALUE;
		for (var ignored : chunk.getSections()) {
			chunkSectionY++;
			int worldSecY = chunkSectionY >> 1;
			if (worldSecY == lastWorldSecY) {
				continue;
			}
			lastWorldSecY = worldSecY;
			index.add(WorldEngine.getWorldSectionId(0, worldSecX, worldSecY, worldSecZ));
		}
	}

	public Identifier getDimensionForWorld(WorldIdentifier worldId) {
		return this.dimensionsByWorld.get(worldId);
	}

	/**
	 * Get a WorldEngine by dimension Identifier.
	 * Looks up the WorldIdentifier from the dimension mapping and returns the engine.
	 */
	public WorldEngine getWorldEngineForDimension(Identifier dimension) {
		for (var entry : this.dimensionsByWorld.entrySet()) {
			if (entry.getValue().equals(dimension)) {
				return this.getNullable(entry.getKey());
			}
		}
		return null;
	}

	@Override
	public void shutdown() {
		ChunkTimestampStore.setGlobalInstance(null);
		this.presenceIndexExecutor.shutdownNow();
		try {
			this.presenceIndexExecutor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		super.shutdown();
		this.sectionHashStore.close();
		this.chunkTimestampStore.close();
	}

	@Override
	protected SectionStorage createStorage(WorldIdentifier identifier) {
		var ctx = new ConfigBuildCtx();
		ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
		ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
		ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
		return this.storageConfig.build(ctx);
	}

	private void attachDirtyCallback(WorldIdentifier identifier, WorldEngine world) {
		if (world == null) {
			return;
		}

		Identifier dimension = this.dimensionsByWorld.get(identifier);
		DirtySectionListener listener = this.dirtySectionListener;
		if (dimension == null || listener == null) {
			return;
		}

		world.setDirtyCallback((section, updateFlags, neighborMsk) -> {
			if (section.lvl != 0) {
				return;
			}
			listener.onSectionDirty(dimension, section.key);
		});
	}

	private StoredSectionPresenceIndex ensurePresenceIndex(WorldIdentifier identifier, WorldEngine world) {
		StoredSectionPresenceIndex index = this.presenceIndexes.computeIfAbsent(identifier, ignored -> new StoredSectionPresenceIndex());
		if (!index.isReady()) {
			this.schedulePresenceIndexBuild(identifier, world, index);
		}
		return index;
	}

	private void schedulePresenceIndexBuild(WorldIdentifier identifier, WorldEngine world, StoredSectionPresenceIndex index) {
		if (world == null || !index.tryScheduleBuild()) {
			return;
		}

		var filter = index.createBuildFilter();
		world.acquireRef();
		try {
			this.presenceIndexExecutor.execute(() -> {
				try {
					world.storage.iteratePositions(0, key -> index.addTo(filter, key));
					index.completeBuild(filter);
				} catch (Exception e) {
					VoxyServerMod.LOGGER.warn("Failed to build presence index for {}", identifier.getLongHash(), e);
					index.failBuild();
				} finally {
					world.releaseRef();
				}
			});
		} catch (RejectedExecutionException e) {
			world.releaseRef();
			index.failBuild();
		}
	}
}
