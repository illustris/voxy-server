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
 * S2C: Server periodically sends sync queue status to the client.
 */
//? if HAS_NEW_NETWORKING {
public record SyncStatusPayload(
	int queueSize,
	int syncState,
	int pendingGenCount
) implements CustomPacketPayload {

	public static final Type<SyncStatusPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:sync_status"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SyncStatusPayload> CODEC =
		StreamCodec.of(SyncStatusPayload::write, SyncStatusPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, SyncStatusPayload payload) {
		buf.writeVarInt(payload.queueSize);
		buf.writeByte(payload.syncState);
		buf.writeVarInt(payload.pendingGenCount);
	}

	private static SyncStatusPayload read(RegistryFriendlyByteBuf buf) {
		return new SyncStatusPayload(buf.readVarInt(), buf.readByte(), buf.readVarInt());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record SyncStatusPayload(
	int queueSize,
	int syncState,
	int pendingGenCount
) implements FabricPacket {

	public static final PacketType<SyncStatusPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "sync_status"), SyncStatusPayload::new);

	public SyncStatusPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), buf.readByte(), buf.readVarInt());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(queueSize);
		buf.writeByte(syncState);
		buf.writeVarInt(pendingGenCount);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
