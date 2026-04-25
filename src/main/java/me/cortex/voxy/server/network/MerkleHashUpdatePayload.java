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
 * S2C: Server sends updated L1 column hashes so client can maintain its local tree.
 */
//? if HAS_NEW_NETWORKING {
public record MerkleHashUpdatePayload(
	Identifier dimension,
	long[] columnKeys,
	long[] columnHashes
) implements CustomPacketPayload {

	public static final Type<MerkleHashUpdatePayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:merkle_hash_update"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MerkleHashUpdatePayload> CODEC =
		StreamCodec.of(MerkleHashUpdatePayload::write, MerkleHashUpdatePayload::read);

	private static void write(RegistryFriendlyByteBuf buf, MerkleHashUpdatePayload payload) {
		buf.writeIdentifier(payload.dimension);
		buf.writeVarInt(payload.columnKeys.length);
		for (int i = 0; i < payload.columnKeys.length; i++) {
			buf.writeLong(payload.columnKeys[i]);
			buf.writeLong(payload.columnHashes[i]);
		}
	}

	private static MerkleHashUpdatePayload read(RegistryFriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int count = buf.readVarInt();
		long[] keys = new long[count];
		long[] hashes = new long[count];
		for (int i = 0; i < count; i++) {
			keys[i] = buf.readLong();
			hashes[i] = buf.readLong();
		}
		return new MerkleHashUpdatePayload(dimension, keys, hashes);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record MerkleHashUpdatePayload(
	ResourceLocation dimension,
	long[] columnKeys,
	long[] columnHashes
) implements FabricPacket {

	public static final PacketType<MerkleHashUpdatePayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "merkle_hash_update"), MerkleHashUpdatePayload::new);

	public MerkleHashUpdatePayload(FriendlyByteBuf buf) {
		this(buf.readResourceLocation(), readKeys(buf), readHashes(buf));
	}

	private static long[] readKeys(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		long[] keys = new long[count];
		for (int i = 0; i < count; i++) {
			keys[i] = buf.readLong();
		}
		return keys;
	}

	private static long[] readHashes(FriendlyByteBuf buf) {
		long[] hashes = new long[0];
		return hashes;
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeResourceLocation(dimension);
		buf.writeVarInt(columnKeys.length);
		for (int i = 0; i < columnKeys.length; i++) {
			buf.writeLong(columnKeys[i]);
			buf.writeLong(columnHashes[i]);
		}
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
