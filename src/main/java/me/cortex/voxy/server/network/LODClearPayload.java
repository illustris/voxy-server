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

import java.util.Optional;

//? if HAS_NEW_NETWORKING {
public record LODClearPayload(
	Optional</*$ rl_type */Identifier> dimension
) implements CustomPacketPayload {

	public static final Type<LODClearPayload> TYPE =
		new Type<>(/*$ rl_parse */Identifier.parse("voxy-server:lod_clear"));

	public static final StreamCodec<RegistryFriendlyByteBuf, LODClearPayload> CODEC =
		StreamCodec.of(LODClearPayload::write, LODClearPayload::read);

	public static LODClearPayload clearAll() {
		return new LODClearPayload(Optional.empty());
	}

	public static LODClearPayload clearDimension(/*$ rl_type */Identifier dimension) {
		return new LODClearPayload(Optional.of(dimension));
	}

	private static void write(RegistryFriendlyByteBuf buf, LODClearPayload payload) {
		buf.writeBoolean(payload.dimension.isPresent());
		payload.dimension.ifPresent(buf::/*$ write_rl */writeIdentifier);
	}

	private static LODClearPayload read(RegistryFriendlyByteBuf buf) {
		boolean hasDimension = buf.readBoolean();
		Optional</*$ rl_type */Identifier> dimension = hasDimension
			? Optional.of(buf./*$ read_rl */readIdentifier())
			: Optional.empty();
		return new LODClearPayload(dimension);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record LODClearPayload(
	Optional<ResourceLocation> dimension
) implements FabricPacket {

	public static final PacketType<LODClearPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "lod_clear"), LODClearPayload::new);

	public LODClearPayload(FriendlyByteBuf buf) {
		this(buf.readBoolean() ? Optional.of(buf.readResourceLocation()) : Optional.empty());
	}

	public static LODClearPayload clearAll() {
		return new LODClearPayload(Optional.empty());
	}

	public static LODClearPayload clearDimension(ResourceLocation dimension) {
		return new LODClearPayload(Optional.of(dimension));
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeBoolean(dimension.isPresent());
		dimension.ifPresent(buf::writeResourceLocation);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
