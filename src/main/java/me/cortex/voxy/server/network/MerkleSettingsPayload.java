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
 * S2C: Server sends configuration to client.
 */
//? if HAS_NEW_NETWORKING {
public record MerkleSettingsPayload(
	int maxRadius,
	int maxSectionsPerTick
) implements CustomPacketPayload {

	public static final Type<MerkleSettingsPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:merkle_settings"));

	public static final StreamCodec<RegistryFriendlyByteBuf, MerkleSettingsPayload> CODEC =
		StreamCodec.of(MerkleSettingsPayload::write, MerkleSettingsPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, MerkleSettingsPayload payload) {
		buf.writeVarInt(payload.maxRadius);
		buf.writeVarInt(payload.maxSectionsPerTick);
	}

	private static MerkleSettingsPayload read(RegistryFriendlyByteBuf buf) {
		return new MerkleSettingsPayload(buf.readVarInt(), buf.readVarInt());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record MerkleSettingsPayload(
	int maxRadius,
	int maxSectionsPerTick
) implements FabricPacket {

	public static final PacketType<MerkleSettingsPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "merkle_settings"), MerkleSettingsPayload::new);

	public MerkleSettingsPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), buf.readVarInt());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(maxRadius);
		buf.writeVarInt(maxSectionsPerTick);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
