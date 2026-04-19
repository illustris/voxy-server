package me.cortex.voxy.server.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S: Client sends its L1 column hashes for mismatched L2 regions.
 * Structure: dimension + list of (regionKey, columnKey, columnHash) tuples.
 */
public record MerkleClientL1Payload(
	Identifier dimension,
	long[] regionKeys,
	long[] columnKeys,
	long[] columnHashes
) implements CustomPacketPayload {

	public static final Type<MerkleClientL1Payload> TYPE =
		new Type<>(Identifier.parse("voxy-server:merkle_client_l1"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MerkleClientL1Payload> CODEC =
		StreamCodec.of(MerkleClientL1Payload::write, MerkleClientL1Payload::read);

	private static void write(RegistryFriendlyByteBuf buf, MerkleClientL1Payload payload) {
		buf.writeIdentifier(payload.dimension);
		buf.writeVarInt(payload.regionKeys.length);
		for (int i = 0; i < payload.regionKeys.length; i++) {
			buf.writeLong(payload.regionKeys[i]);
			buf.writeLong(payload.columnKeys[i]);
			buf.writeLong(payload.columnHashes[i]);
		}
	}

	private static MerkleClientL1Payload read(RegistryFriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int count = buf.readVarInt();
		long[] regionKeys = new long[count];
		long[] columnKeys = new long[count];
		long[] columnHashes = new long[count];
		for (int i = 0; i < count; i++) {
			regionKeys[i] = buf.readLong();
			columnKeys[i] = buf.readLong();
			columnHashes[i] = buf.readLong();
		}
		return new MerkleClientL1Payload(dimension, regionKeys, columnKeys, columnHashes);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
