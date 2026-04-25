package me.cortex.voxy.server.network;

//? if HAS_NEW_NETWORKING {
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
*///?}
import net.minecraft.resources.Identifier;

/**
 * S2C: Server sends L2 region hashes to client for comparison.
 */
//? if HAS_NEW_NETWORKING {
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
//?} else {
/*public record MerkleL2HashesPayload(
	ResourceLocation dimension,
	long[] regionKeys,
	long[] regionHashes
) implements FabricPacket {

	public static final PacketType<MerkleL2HashesPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "merkle_l2_hashes"), MerkleL2HashesPayload::readBuf);

	private static MerkleL2HashesPayload readBuf(FriendlyByteBuf buf) {
		ResourceLocation dimension = buf.readResourceLocation();
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
	public void write(FriendlyByteBuf buf) {
		buf.writeResourceLocation(dimension);
		buf.writeVarInt(regionKeys.length);
		for (int i = 0; i < regionKeys.length; i++) {
			buf.writeLong(regionKeys[i]);
			buf.writeLong(regionHashes[i]);
		}
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
