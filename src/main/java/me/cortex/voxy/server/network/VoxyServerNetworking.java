package me.cortex.voxy.server.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public class VoxyServerNetworking {

	public static void register() {
		// S2C payloads
		PayloadTypeRegistry.clientboundPlay().register(LODSectionPayload.TYPE, LODSectionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LODBulkPayload.TYPE, LODBulkPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PreSerializedLodPayload.TYPE, PreSerializedLodPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LODClearPayload.TYPE, LODClearPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MerkleSettingsPayload.TYPE, MerkleSettingsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MerkleL2HashesPayload.TYPE, MerkleL2HashesPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MerkleHashUpdatePayload.TYPE, MerkleHashUpdatePayload.CODEC);

		// C2S payloads
		PayloadTypeRegistry.serverboundPlay().register(MerkleReadyPayload.TYPE, MerkleReadyPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MerkleClientL1Payload.TYPE, MerkleClientL1Payload.CODEC);
	}
}
