package me.cortex.voxy.server.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C: Batch removal of LOD-tracked entities.
 * Sent when entities die, despawn, leave LOD range, or enter vanilla tracking range.
 */
public record LODEntityRemovePayload(
	int[] entityIds
) implements CustomPacketPayload {

	public static final Type<LODEntityRemovePayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:lod_entity_remove"));

	public static final StreamCodec<RegistryFriendlyByteBuf, LODEntityRemovePayload> CODEC =
		StreamCodec.of(LODEntityRemovePayload::write, LODEntityRemovePayload::read);

	private static void write(RegistryFriendlyByteBuf buf, LODEntityRemovePayload payload) {
		buf.writeVarInt(payload.entityIds.length);
		for (int id : payload.entityIds) {
			buf.writeVarInt(id);
		}
	}

	private static LODEntityRemovePayload read(RegistryFriendlyByteBuf buf) {
		int count = buf.readVarInt();
		int[] entityIds = new int[count];
		for (int i = 0; i < count; i++) {
			entityIds[i] = buf.readVarInt();
		}
		return new LODEntityRemovePayload(entityIds);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
