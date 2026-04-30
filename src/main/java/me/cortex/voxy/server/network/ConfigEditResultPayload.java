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
 * S2C: result of a {@link ConfigEditPayload} attempt. Sent to the editing
 * client only. On success {@code message} is empty; on failure it carries
 * a human-readable reason (invalid type, out of range, not authorized).
 * The full {@link ConfigSnapshotPayload} also goes out separately on
 * success so the client UI updates with the new value.
 */
//? if HAS_NEW_NETWORKING {
public record ConfigEditResultPayload(
	String key,
	boolean success,
	String message
) implements CustomPacketPayload {

	public static final Type<ConfigEditResultPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:config_edit_result"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ConfigEditResultPayload> CODEC =
		StreamCodec.of(ConfigEditResultPayload::write, ConfigEditResultPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, ConfigEditResultPayload p) {
		buf.writeUtf(p.key, 64);
		buf.writeBoolean(p.success);
		buf.writeUtf(p.message, 256);
	}

	private static ConfigEditResultPayload read(RegistryFriendlyByteBuf buf) {
		return new ConfigEditResultPayload(buf.readUtf(64), buf.readBoolean(), buf.readUtf(256));
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record ConfigEditResultPayload(
	String key,
	boolean success,
	String message
) implements FabricPacket {

	public static final PacketType<ConfigEditResultPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "config_edit_result"), ConfigEditResultPayload::new);

	public ConfigEditResultPayload(FriendlyByteBuf buf) {
		this(buf.readUtf(64), buf.readBoolean(), buf.readUtf(256));
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(key, 64);
		buf.writeBoolean(success);
		buf.writeUtf(message, 256);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
