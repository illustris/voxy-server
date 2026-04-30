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
 * C2S: client requests a single config field be set. Value is encoded as
 * a string and parsed on the server based on the field's declared type.
 * Server validates type + range and rejects malformed or unauthorized
 * requests; on success it broadcasts a fresh {@link ConfigSnapshotPayload}
 * to all connected clients so every UI sees the same state.
 */
//? if HAS_NEW_NETWORKING {
public record ConfigEditPayload(
	String key,
	String value
) implements CustomPacketPayload {

	public static final Type<ConfigEditPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:config_edit"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ConfigEditPayload> CODEC =
		StreamCodec.of(ConfigEditPayload::write, ConfigEditPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, ConfigEditPayload p) {
		buf.writeUtf(p.key, 64);
		buf.writeUtf(p.value, 256);
	}

	private static ConfigEditPayload read(RegistryFriendlyByteBuf buf) {
		return new ConfigEditPayload(buf.readUtf(64), buf.readUtf(256));
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record ConfigEditPayload(
	String key,
	String value
) implements FabricPacket {

	public static final PacketType<ConfigEditPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "config_edit"), ConfigEditPayload::new);

	public ConfigEditPayload(FriendlyByteBuf buf) {
		this(buf.readUtf(64), buf.readUtf(256));
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(key, 64);
		buf.writeUtf(value, 256);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
