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
//? if HAS_IDENTIFIER {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * C2S: Client sends its L1 column hashes for mismatched L2 regions.
 * Structure: dimension + list of (regionKey, columnKey, columnHash) tuples.
 */
//? if HAS_NEW_NETWORKING {
public record MerkleClientL1Payload(
	/*$ rl_type */Identifier dimension,
	long[] regionKeys,
	long[] columnKeys,
	long[] columnHashes
) implements CustomPacketPayload {

	public static final Type<MerkleClientL1Payload> TYPE =
		new Type<>(/*$ rl_parse */Identifier.parse("voxy-server:merkle_client_l1"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MerkleClientL1Payload> CODEC =
		StreamCodec.of(MerkleClientL1Payload::write, MerkleClientL1Payload::read);

	private static void write(RegistryFriendlyByteBuf buf, MerkleClientL1Payload payload) {
		buf./*$ write_rl */writeIdentifier(payload.dimension);
		buf.writeVarInt(payload.regionKeys.length);
		for (int i = 0; i < payload.regionKeys.length; i++) {
			buf.writeLong(payload.regionKeys[i]);
			buf.writeLong(payload.columnKeys[i]);
			buf.writeLong(payload.columnHashes[i]);
		}
	}

	private static MerkleClientL1Payload read(RegistryFriendlyByteBuf buf) {
		/*$ rl_type */Identifier dimension = buf./*$ read_rl */readIdentifier();
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
//?} else {
/*public record MerkleClientL1Payload(
	ResourceLocation dimension,
	long[] regionKeys,
	long[] columnKeys,
	long[] columnHashes
) implements FabricPacket {

	public static final PacketType<MerkleClientL1Payload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "merkle_client_l1"), MerkleClientL1Payload::new);

	public MerkleClientL1Payload(FriendlyByteBuf buf) {
		this(buf.readResourceLocation(), readTuples(buf));
	}

	private MerkleClientL1Payload(ResourceLocation dimension, long[][] tuples) {
		this(dimension, tuples[0], tuples[1], tuples[2]);
	}

	private static long[][] readTuples(FriendlyByteBuf buf) {
		int count = buf.readVarInt();
		long[] regionKeys = new long[count];
		long[] columnKeys = new long[count];
		long[] columnHashes = new long[count];
		for (int i = 0; i < count; i++) {
			regionKeys[i] = buf.readLong();
			columnKeys[i] = buf.readLong();
			columnHashes[i] = buf.readLong();
		}
		return new long[][] { regionKeys, columnKeys, columnHashes };
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeResourceLocation(dimension);
		buf.writeVarInt(regionKeys.length);
		for (int i = 0; i < regionKeys.length; i++) {
			buf.writeLong(regionKeys[i]);
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
