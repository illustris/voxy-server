package me.cortex.voxy.server.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Optional;

public record LODClearPayload(
	Optional<Identifier> dimension
) implements CustomPacketPayload {

	public static final Type<LODClearPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:lod_clear"));

	public static final StreamCodec<RegistryFriendlyByteBuf, LODClearPayload> CODEC =
		StreamCodec.of(LODClearPayload::write, LODClearPayload::read);

	public static LODClearPayload clearAll() {
		return new LODClearPayload(Optional.empty());
	}

	public static LODClearPayload clearDimension(Identifier dimension) {
		return new LODClearPayload(Optional.of(dimension));
	}

	private static void write(RegistryFriendlyByteBuf buf, LODClearPayload payload) {
		buf.writeBoolean(payload.dimension.isPresent());
		payload.dimension.ifPresent(buf::writeIdentifier);
	}

	private static LODClearPayload read(RegistryFriendlyByteBuf buf) {
		boolean hasDimension = buf.readBoolean();
		Optional<Identifier> dimension = hasDimension
			? Optional.of(buf.readIdentifier())
			: Optional.empty();
		return new LODClearPayload(dimension);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
