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
 * C2S: Client signals it supports Merkle sync.
 */
//? if HAS_NEW_NETWORKING {
public record MerkleReadyPayload() implements CustomPacketPayload {

	public static final Type<MerkleReadyPayload> TYPE =
		new Type<>(/*$ rl_parse */Identifier.parse("voxy-server:merkle_ready"));

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
//?} else {
/*public record MerkleReadyPayload() implements FabricPacket {

	public static final PacketType<MerkleReadyPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "merkle_ready"), buf -> new MerkleReadyPayload());

	@Override
	public void write(FriendlyByteBuf buf) {}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
