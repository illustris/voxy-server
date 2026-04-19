package me.cortex.voxy.server.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: Server sends L2 region hashes to client for comparison.
 */
public record MerkleL2HashesPayload(
	Identifier dimension,
	long[] regionKeys,
	long[] regionHashes
) implements CustomPacketPayload {

	public static final Type<MerkleL2HashesPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:merkle_l2_hashes"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MerkleL2HashesPayload> CODEC =
		StreamCodec.of(MerkleL2HashesPayload::write, MerkleL2HashesPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, MerkleL2HashesPayload payload) {
		buf.writeIdentifier(payload.dimension);
		buf.writeVarInt(payload.regionKeys.length);
		for (int i = 0; i < payload.regionKeys.length; i++) {
			buf.writeLong(payload.regionKeys[i]);
			buf.writeLong(payload.regionHashes[i]);
		}
	}

	private static MerkleL2HashesPayload read(RegistryFriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int count = buf.readVarInt();
		long[] keys = new long[count];
		long[] hashes = new long[count];
		for (int i = 0; i < count; i++) {
			keys[i] = buf.readLong();
			hashes[i] = buf.readLong();
		}
		return new MerkleL2HashesPayload(dimension, keys, hashes);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
