package me.cortex.voxy.server.client;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.server.network.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Client-side handler for receiving sections and exchanging Merkle hashes with the server.
 */
public class ClientSyncHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger("voxy-server-client");
	private static final ClientMerkleState merkleState = new ClientMerkleState();
	private static volatile int maxRadius = 0;

	public static int getMaxRadius() {
		return maxRadius;
	}

	public static void register() {
		// Send ready handshake when joining a server
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			LOGGER.info("[ClientSync] Sending MerkleReadyPayload to server");
			ClientPlayNetworking.send(new MerkleReadyPayload());
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			// Do NOT clear merkleState on disconnect -- we need it for efficient
			// reconnection. The hashes are still valid for unchanged sections.
			// Only clear on dimension change (LODClearPayload).
			VoxyBandwidthTracker.reset();
			maxRadius = 0;
			LOGGER.info("[ClientSync] Disconnected, preserving Merkle state for reconnection");
		});

		//? if HAS_NEW_NETWORKING {
		// Handle server settings
		ClientPlayNetworking.registerGlobalReceiver(MerkleSettingsPayload.TYPE, (payload, context) -> {
			VoxyBandwidthTracker.recordBytes("merkle", 8);
			maxRadius = payload.maxRadius();
			LOGGER.info("[ClientSync] Received settings: radius={} maxSections={}",
				payload.maxRadius(), payload.maxSectionsPerTick());
		});

		// Handle L2 hashes from server - compare and respond with L1 for mismatches
		ClientPlayNetworking.registerGlobalReceiver(MerkleL2HashesPayload.TYPE, (payload, context) -> {
			VoxyBandwidthTracker.recordBytes("merkle", payload.regionKeys().length * 16);
			LOGGER.info("[ClientSync] Received {} L2 hashes from server", payload.regionKeys().length);
			context.client().execute(() -> handleL2Hashes(payload));
		});

		// Handle section data from server
		ClientPlayNetworking.registerGlobalReceiver(PreSerializedLodPayload.TYPE, (payload, context) -> {
			VoxyBandwidthTracker.recordBytes("sections", payload.data().length);
			context.client().execute(() -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level == null) return;
				LODBulkPayload bulk = payload.decodeBulk(level.registryAccess());
				VoxyBandwidthTracker.recordSections(bulk.sections().size());
				LOGGER.info("[ClientSync] Received {} sections from server", bulk.sections().size());
				for (LODSectionPayload section : bulk.sections()) {
					handleSection(section);
				}
			});
		});

		// Handle hash updates from server
		ClientPlayNetworking.registerGlobalReceiver(MerkleHashUpdatePayload.TYPE, (payload, context) -> {
			VoxyBandwidthTracker.recordBytes("merkle", payload.columnKeys().length * 16);
			context.client().execute(() -> {
				merkleState.updateL1Hashes(payload.columnKeys(), payload.columnHashes());
			});
		});

		// Handle clear
		ClientPlayNetworking.registerGlobalReceiver(LODClearPayload.TYPE, (payload, context) -> {
			context.client().execute(merkleState::clear);
		});

		// Handle sync status from server
		ClientPlayNetworking.registerGlobalReceiver(SyncStatusPayload.TYPE, (payload, context) -> {
			VoxyBandwidthTracker.updateServerStatus(
				payload.queueSize(), payload.syncState(), payload.pendingGenCount()
			);
		});

		// Handle telemetry snapshots from server (1 Hz). Push into the per-metric
		// rolling window for the HUD overlay to read.
		ClientPlayNetworking.registerGlobalReceiver(TelemetrySnapshotPayload.TYPE, (payload, context) -> {
			VoxyTelemetryHistory.pushSnapshot(decodeSnapshot(payload));
			// Mirror the three SyncStatusPayload fields into the bandwidth
			// tracker so the existing F3 entry continues to show fresh values
			// (telemetry replaces SyncStatusPayload's role).
			VoxyBandwidthTracker.updateServerStatus(
				payload.sendQueueSize(), -1, payload.pendingGenCount()
			);
		});

		// Server pushed its config; cache for the ModMenu config screen.
		ClientPlayNetworking.registerGlobalReceiver(ConfigSnapshotPayload.TYPE, (payload, context) -> {
			ServerConfigState.update(payload);
		});

		// Result of a config edit (success/failure + reason).
		ClientPlayNetworking.registerGlobalReceiver(ConfigEditResultPayload.TYPE, (payload, context) -> {
			ServerConfigEditFeedback.publish(payload);
		});
		//?} else {
		/*// Handle server settings
		ClientPlayNetworking.registerGlobalReceiver(MerkleSettingsPayload.TYPE, (packet, player, sender) -> {
			VoxyBandwidthTracker.recordBytes("merkle", 8);
			maxRadius = packet.maxRadius();
			LOGGER.info("[ClientSync] Received settings: radius={} maxSections={}",
				packet.maxRadius(), packet.maxSectionsPerTick());
		});

		// Handle L2 hashes from server - compare and respond with L1 for mismatches
		ClientPlayNetworking.registerGlobalReceiver(MerkleL2HashesPayload.TYPE, (packet, player, sender) -> {
			VoxyBandwidthTracker.recordBytes("merkle", packet.regionKeys().length * 16);
			LOGGER.info("[ClientSync] Received {} L2 hashes from server", packet.regionKeys().length);
			Minecraft.getInstance().execute(() -> handleL2Hashes(packet));
		});

		// Handle section data from server
		ClientPlayNetworking.registerGlobalReceiver(PreSerializedLodPayload.TYPE, (packet, player, sender) -> {
			VoxyBandwidthTracker.recordBytes("sections", packet.data().length);
			Minecraft.getInstance().execute(() -> {
				ClientLevel level = Minecraft.getInstance().level;
				if (level == null) return;
				LODBulkPayload bulk = packet.decodeBulk();
				VoxyBandwidthTracker.recordSections(bulk.sections().size());
				LOGGER.info("[ClientSync] Received {} sections from server", bulk.sections().size());
				for (LODSectionPayload section : bulk.sections()) {
					handleSection(section);
				}
			});
		});

		// Handle hash updates from server
		ClientPlayNetworking.registerGlobalReceiver(MerkleHashUpdatePayload.TYPE, (packet, player, sender) -> {
			VoxyBandwidthTracker.recordBytes("merkle", packet.columnKeys().length * 16);
			Minecraft.getInstance().execute(() -> {
				merkleState.updateL1Hashes(packet.columnKeys(), packet.columnHashes());
			});
		});

		// Handle clear
		ClientPlayNetworking.registerGlobalReceiver(LODClearPayload.TYPE, (packet, player, sender) -> {
			Minecraft.getInstance().execute(merkleState::clear);
		});

		// Handle sync status from server
		ClientPlayNetworking.registerGlobalReceiver(SyncStatusPayload.TYPE, (packet, player, sender) -> {
			VoxyBandwidthTracker.updateServerStatus(
				packet.queueSize(), packet.syncState(), packet.pendingGenCount()
			);
		});

		// Handle telemetry snapshots from server (1 Hz)
		ClientPlayNetworking.registerGlobalReceiver(TelemetrySnapshotPayload.TYPE, (packet, player, sender) -> {
			VoxyTelemetryHistory.pushSnapshot(decodeSnapshot(packet));
			VoxyBandwidthTracker.updateServerStatus(
				packet.sendQueueSize(), -1, packet.pendingGenCount()
			);
		});

		ClientPlayNetworking.registerGlobalReceiver(ConfigSnapshotPayload.TYPE, (packet, player, sender) -> {
			ServerConfigState.update(packet);
		});
		ClientPlayNetworking.registerGlobalReceiver(ConfigEditResultPayload.TYPE, (packet, player, sender) -> {
			ServerConfigEditFeedback.publish(packet);
		});
		*///?}
	}

	private static void handleL2Hashes(MerkleL2HashesPayload payload) {
		// Find mismatched regions
		List<Long> mismatched = merkleState.findMismatchedRegions(
			payload.regionKeys(), payload.regionHashes()
		);

		LOGGER.info("[ClientSync] L2 comparison: {} total regions, {} mismatched",
			payload.regionKeys().length, mismatched.size());

		if (mismatched.isEmpty()) {
			LOGGER.info("[ClientSync] Fully in sync with server");
			return;
		}

		// Collect our L1 hashes for mismatched regions
		LongArrayList regionKeys = new LongArrayList();
		LongArrayList columnKeys = new LongArrayList();
		LongArrayList columnHashes = new LongArrayList();

		for (long regionKey : mismatched) {
			Long2LongOpenHashMap l1 = merkleState.getL1HashesForRegion(regionKey);
			for (var entry : l1.long2LongEntrySet()) {
				regionKeys.add(regionKey);
				columnKeys.add(entry.getLongKey());
				columnHashes.add(entry.getLongValue());
			}
			// If we have no L1 data for this region, send a zero entry
			// so server knows we have nothing
			if (l1.isEmpty()) {
				regionKeys.add(regionKey);
				columnKeys.add(0L);
				columnHashes.add(0L);
			}
		}

		// Send to server
		ClientPlayNetworking.send(new MerkleClientL1Payload(
			payload.dimension(),
			regionKeys.toLongArray(),
			columnKeys.toLongArray(),
			columnHashes.toLongArray()
		));
	}

	private static void handleSection(LODSectionPayload payload) {
		LODSectionHighlightTracker.onSectionReceived(payload.sectionKey());

		var instance = VoxyCommon.getInstance();
		if (instance == null) return;

		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;

		WorldIdentifier worldId = WorldIdentifier.of(level);
		if (worldId == null) return;

		WorldEngine engine = instance.getOrCreate(worldId);
		if (engine == null) return;
		Mapper mapper = engine.getMapper();

		long[] remappedLut = remapLut(payload.lutBlockStateIds(), payload.lutBiomeIds(),
			payload.lutLight(), mapper, level);

		int secX = WorldEngine.getX(payload.sectionKey());
		int secY = WorldEngine.getY(payload.sectionKey());
		int secZ = WorldEngine.getZ(payload.sectionKey());

		short[] indexArray = payload.indexArray();

		// Split 32x32x32 world section into 8 VoxelizedSections (16x16x16 each)
		for (int oy = 0; oy < 2; oy++) {
			for (int oz = 0; oz < 2; oz++) {
				for (int ox = 0; ox < 2; ox++) {
					VoxelizedSection vs = VoxelizedSection.createEmpty();
					vs.setPosition(secX * 2 + ox, secY * 2 + oy, secZ * 2 + oz);

					int nonAirCount = 0;
					for (int vy = 0; vy < 16; vy++) {
						for (int vz = 0; vz < 16; vz++) {
							for (int vx = 0; vx < 16; vx++) {
								int wsIdx = ((oy * 16 + vy) << 10) | ((oz * 16 + vz) << 5) | (ox * 16 + vx);
								int vsIdx = (vy << 8) | (vz << 4) | vx;
								long id = remappedLut[indexArray[wsIdx] & 0xFFFF];
								vs.section[vsIdx] = id;
								if (!Mapper.isAir(id)) nonAirCount++;
							}
						}
					}
					vs.lvl0NonAirCount = nonAirCount;

					WorldConversionFactory.mipSection(vs, mapper);
					WorldUpdater.insertUpdate(engine, vs);
				}
			}
		}
	}

	private static long[] remapLut(int[] blockStateIds, int[] biomeIds, byte[] light,
									Mapper mapper, ClientLevel level) {
		//? if HAS_LOOKUP_OR_THROW {
		Registry<Biome> biomeRegistry = level.registryAccess().lookupOrThrow(Registries.BIOME);
		//?} else {
		/*Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
		*///?}
		long[] remapped = new long[blockStateIds.length];

		for (int i = 0; i < blockStateIds.length; i++) {
			BlockState state = Block.BLOCK_STATE_REGISTRY.byId(blockStateIds[i]);
			int clientBlockId = (state != null) ? mapper.getIdForBlockState(state) : 0;

			//? if HAS_LOOKUP_OR_THROW {
			Optional<Holder.Reference<Biome>> biomeHolder = biomeRegistry.get(biomeIds[i]);
			int clientBiomeId = biomeHolder.map(mapper::getIdForBiome).orElse(0);
			//?} else {
			/*Holder<Biome> biomeHolder = biomeRegistry.getHolder(biomeIds[i]).orElse(null);
			int clientBiomeId = (biomeHolder != null) ? mapper.getIdForBiome(biomeHolder) : 0;
			*///?}

			remapped[i] = Mapper.composeMappingId(light[i], clientBlockId, clientBiomeId);
		}

		return remapped;
	}

	/**
	 * Unpack a {@link TelemetrySnapshotPayload} into the float[] order expected
	 * by {@link VoxyTelemetryHistory#pushSnapshot}. Indices follow
	 * {@link VoxyTelemetryHistory.Metric#ordinal()} so a single line in
	 * {@link VoxyTelemetryHistory.Metric} is the only place this contract
	 * lives.
	 */
	private static float[] decodeSnapshot(TelemetrySnapshotPayload p) {
		float[] vals = new float[VoxyTelemetryHistory.Metric.values().length];
		vals[VoxyTelemetryHistory.Metric.CHUNKS_PER_SEC.ordinal()] = p.chunksPerSecX10() / 10f;
		vals[VoxyTelemetryHistory.Metric.FAILED_CHUNKS.ordinal()] = p.failedChunks();
		vals[VoxyTelemetryHistory.Metric.IN_FLIGHT.ordinal()] = p.inFlightChunks();
		vals[VoxyTelemetryHistory.Metric.GEN_QUEUE_DEPTH.ordinal()] = p.genQueueDepth();
		vals[VoxyTelemetryHistory.Metric.GET_CHUNK_AVG_MS.ordinal()] = p.getChunkAvgMs();
		vals[VoxyTelemetryHistory.Metric.VOXELIZE_AVG_MS.ordinal()] = p.voxelizeAvgMs();
		vals[VoxyTelemetryHistory.Metric.MC_TICK_EMA_MS.ordinal()] = p.mcTickEmaMs();
		vals[VoxyTelemetryHistory.Metric.DISPATCH_BUDGET.ordinal()] = p.dispatchBudgetX100() / 100f;
		vals[VoxyTelemetryHistory.Metric.SECTIONS_SENT.ordinal()] = p.sectionsSent();
		vals[VoxyTelemetryHistory.Metric.HEARTBEATS_EMITTED.ordinal()] = p.heartbeatsEmitted();
		vals[VoxyTelemetryHistory.Metric.HEARTBEATS_SKIPPED.ordinal()] = p.heartbeatsSkipped();
		vals[VoxyTelemetryHistory.Metric.SEND_QUEUE_SIZE.ordinal()] = p.sendQueueSize();
		vals[VoxyTelemetryHistory.Metric.PENDING_GEN.ordinal()] = p.pendingGenCount();
		vals[VoxyTelemetryHistory.Metric.DANGLING_COLUMNS.ordinal()] = p.danglingColumns();
		vals[VoxyTelemetryHistory.Metric.SESSIONS_COUNT.ordinal()] = p.sessionsCount();
		vals[VoxyTelemetryHistory.Metric.CLIENT_L1_BATCHES_RX.ordinal()] = p.clientL1BatchesRx();
		vals[VoxyTelemetryHistory.Metric.SECTIONS_ENQUEUED.ordinal()] = p.sectionsEnqueued();
		vals[VoxyTelemetryHistory.Metric.SECTION_COMMITS.ordinal()] = p.sectionCommits();
		vals[VoxyTelemetryHistory.Metric.VOXY_INGEST_QUEUE.ordinal()] = p.voxyIngestQueueSize();
		return vals;
	}
}
