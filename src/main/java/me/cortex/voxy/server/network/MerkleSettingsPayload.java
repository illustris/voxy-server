package me.cortex.voxy.server.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: Server sends configuration to client.
 */
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
