package me.cortex.voxy.server.network;

import io.netty.buffer.Unpooled;
//? if HAS_NEW_NETWORKING {
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/*import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;
*///?}
import net.minecraft.resources.Identifier;

//? if HAS_NEW_NETWORKING {
public record PreSerializedLodPayload(byte[] data) implements CustomPacketPayload {

	public static final Type<PreSerializedLodPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:lod_preserialized"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PreSerializedLodPayload> CODEC =
		StreamCodec.of(PreSerializedLodPayload::write, PreSerializedLodPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, PreSerializedLodPayload payload) {
		buf.writeVarInt(payload.data.length);
		buf.writeBytes(payload.data);
	}

	private static PreSerializedLodPayload read(RegistryFriendlyByteBuf buf) {
		int len = buf.readVarInt();
		byte[] data = new byte[len];
		buf.readBytes(data);
		return new PreSerializedLodPayload(data);
	}

	public static PreSerializedLodPayload fromBulk(LODBulkPayload bulk, RegistryAccess registryAccess) {
		var raw = Unpooled.buffer();
		try {
			var rfb = new RegistryFriendlyByteBuf(raw, registryAccess);
			LODBulkPayload.CODEC.encode(rfb, bulk);
			byte[] bytes = new byte[rfb.readableBytes()];
			rfb.readBytes(bytes);
			return new PreSerializedLodPayload(bytes);
		} finally {
			raw.release();
		}
	}

	public LODBulkPayload decodeBulk(RegistryAccess registryAccess) {
		var raw = Unpooled.wrappedBuffer(data);
		try {
			var rfb = new RegistryFriendlyByteBuf(raw, registryAccess);
			return LODBulkPayload.CODEC.decode(rfb);
		} finally {
			raw.release();
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record PreSerializedLodPayload(byte[] data) implements FabricPacket {

	public static final PacketType<PreSerializedLodPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "lod_preserialized"), PreSerializedLodPayload::read);

	private static PreSerializedLodPayload read(FriendlyByteBuf buf) {
		int len = buf.readVarInt();
		byte[] data = new byte[len];
		buf.readBytes(data);
		return new PreSerializedLodPayload(data);
	}

	public static PreSerializedLodPayload fromBulk(LODBulkPayload bulk) {
		var raw = Unpooled.buffer();
		try {
			var buf = new FriendlyByteBuf(raw);
			bulk.write(buf);
			byte[] bytes = new byte[buf.readableBytes()];
			buf.readBytes(bytes);
			return new PreSerializedLodPayload(bytes);
		} finally {
			raw.release();
		}
	}

	public LODBulkPayload decodeBulk() {
		var raw = Unpooled.wrappedBuffer(data);
		try {
			var buf = new FriendlyByteBuf(raw);
			return LODBulkPayload.TYPE.read(buf);
		} finally {
			raw.release();
		}
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(data.length);
		buf.writeBytes(data);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
