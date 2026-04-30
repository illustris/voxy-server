package me.cortex.voxy.server.network;

//? if HAS_NEW_NETWORKING {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?}

public class VoxyServerNetworking {

	public static void register() {
		//? if HAS_NEW_NETWORKING {
		// S2C payloads
		PayloadTypeRegistry.clientboundPlay().register(LODSectionPayload.TYPE, LODSectionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LODBulkPayload.TYPE, LODBulkPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PreSerializedLodPayload.TYPE, PreSerializedLodPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LODClearPayload.TYPE, LODClearPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MerkleSettingsPayload.TYPE, MerkleSettingsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MerkleL2HashesPayload.TYPE, MerkleL2HashesPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MerkleHashUpdatePayload.TYPE, MerkleHashUpdatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SyncStatusPayload.TYPE, SyncStatusPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TelemetrySnapshotPayload.TYPE, TelemetrySnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ConfigSnapshotPayload.TYPE, ConfigSnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ConfigEditResultPayload.TYPE, ConfigEditResultPayload.CODEC);

		// C2S payloads
		PayloadTypeRegistry.serverboundPlay().register(MerkleReadyPayload.TYPE, MerkleReadyPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MerkleClientL1Payload.TYPE, MerkleClientL1Payload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ConfigEditPayload.TYPE, ConfigEditPayload.CODEC);
		//?}
		// On 1.20.1 (FabricPacket), no explicit registration is needed.
		// The PacketType.create() calls in each payload class handle it.
	}
}
