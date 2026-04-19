package me.cortex.voxy.server.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: Client signals it supports Merkle sync.
 */
public record MerkleReadyPayload() implements CustomPacketPayload {

	public static final Type<MerkleReadyPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:merkle_ready"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MerkleReadyPayload> CODEC =
		StreamCodec.of(
			(buf, payload) -> {},
			buf -> new MerkleReadyPayload()
		);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
