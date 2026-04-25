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

import java.util.UUID;

/**
 * S2C: Batch of entity position/type updates for LOD-range entities.
 * Uses a LUT for entity types to avoid repeating full Identifier strings.
 */
//? if HAS_NEW_NETWORKING {
public record LODEntityUpdatePayload(
	Identifier dimension,
	int[] entityIds,
	Identifier[] entityTypes,
	int[] blockX,
	int[] blockY,
	int[] blockZ,
	byte[] yaw,
	byte[] pitch,
	byte[] headYaw,
	long[] uuidMost,
	long[] uuidLeast
) implements CustomPacketPayload {

	public static final Type<LODEntityUpdatePayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:lod_entity_update"));

	public static final StreamCodec<RegistryFriendlyByteBuf, LODEntityUpdatePayload> CODEC =
		StreamCodec.of(LODEntityUpdatePayload::write, LODEntityUpdatePayload::read);

	public int count() {
		return entityIds.length;
	}

	public UUID uuid(int index) {
		return new UUID(uuidMost[index], uuidLeast[index]);
	}

	private static void write(RegistryFriendlyByteBuf buf, LODEntityUpdatePayload payload) {
		buf.writeIdentifier(payload.dimension);
		int count = payload.entityIds.length;
		buf.writeVarInt(count);

		if (count == 0) return;

		// Build and write entity type LUT
		// Collect unique types preserving first-seen order
		java.util.List<Identifier> lut = new java.util.ArrayList<>();
		java.util.Map<Identifier, Integer> lutIndex = new java.util.HashMap<>();
		for (Identifier type : payload.entityTypes) {
			if (!lutIndex.containsKey(type)) {
				lutIndex.put(type, lut.size());
				lut.add(type);
			}
		}

		buf.writeVarInt(lut.size());
		for (Identifier type : lut) {
			buf.writeIdentifier(type);
		}

		// Write per-entity data
		for (int i = 0; i < count; i++) {
			buf.writeVarInt(payload.entityIds[i]);
			buf.writeVarInt(lutIndex.get(payload.entityTypes[i]));
			buf.writeInt(payload.blockX[i]);
			buf.writeShort(payload.blockY[i]);
			buf.writeInt(payload.blockZ[i]);
			buf.writeByte(payload.yaw[i]);
			buf.writeByte(payload.pitch[i]);
			buf.writeByte(payload.headYaw[i]);
			buf.writeLong(payload.uuidMost[i]);
			buf.writeLong(payload.uuidLeast[i]);
		}
	}

	private static LODEntityUpdatePayload read(RegistryFriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		int count = buf.readVarInt();

		if (count == 0) {
			return new LODEntityUpdatePayload(dimension,
				new int[0], new Identifier[0], new int[0], new int[0], new int[0],
				new byte[0], new byte[0], new byte[0], new long[0], new long[0]);
		}

		// Read entity type LUT
		int lutSize = buf.readVarInt();
		Identifier[] lut = new Identifier[lutSize];
		for (int i = 0; i < lutSize; i++) {
			lut[i] = buf.readIdentifier();
		}

		// Read per-entity data
		int[] entityIds = new int[count];
		Identifier[] entityTypes = new Identifier[count];
		int[] blockX = new int[count];
		int[] blockY = new int[count];
		int[] blockZ = new int[count];
		byte[] yaw = new byte[count];
		byte[] pitch = new byte[count];
		byte[] headYaw = new byte[count];
		long[] uuidMost = new long[count];
		long[] uuidLeast = new long[count];

		for (int i = 0; i < count; i++) {
			entityIds[i] = buf.readVarInt();
			entityTypes[i] = lut[buf.readVarInt()];
			blockX[i] = buf.readInt();
			blockY[i] = buf.readShort();
			blockZ[i] = buf.readInt();
			yaw[i] = buf.readByte();
			pitch[i] = buf.readByte();
			headYaw[i] = buf.readByte();
			uuidMost[i] = buf.readLong();
			uuidLeast[i] = buf.readLong();
		}

		return new LODEntityUpdatePayload(dimension, entityIds, entityTypes,
			blockX, blockY, blockZ, yaw, pitch, headYaw, uuidMost, uuidLeast);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record LODEntityUpdatePayload(
	ResourceLocation dimension,
	int[] entityIds,
	ResourceLocation[] entityTypes,
	int[] blockX,
	int[] blockY,
	int[] blockZ,
	byte[] yaw,
	byte[] pitch,
	byte[] headYaw,
	long[] uuidMost,
	long[] uuidLeast
) implements FabricPacket {

	public static final PacketType<LODEntityUpdatePayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "lod_entity_update"), LODEntityUpdatePayload::read);

	public int count() {
		return entityIds.length;
	}

	public UUID uuid(int index) {
		return new UUID(uuidMost[index], uuidLeast[index]);
	}

	private static LODEntityUpdatePayload read(FriendlyByteBuf buf) {
		ResourceLocation dimension = buf.readResourceLocation();
		int count = buf.readVarInt();

		if (count == 0) {
			return new LODEntityUpdatePayload(dimension,
				new int[0], new ResourceLocation[0], new int[0], new int[0], new int[0],
				new byte[0], new byte[0], new byte[0], new long[0], new long[0]);
		}

		// Read entity type LUT
		int lutSize = buf.readVarInt();
		ResourceLocation[] lut = new ResourceLocation[lutSize];
		for (int i = 0; i < lutSize; i++) {
			lut[i] = buf.readResourceLocation();
		}

		// Read per-entity data
		int[] entityIds = new int[count];
		ResourceLocation[] entityTypes = new ResourceLocation[count];
		int[] blockX = new int[count];
		int[] blockY = new int[count];
		int[] blockZ = new int[count];
		byte[] yaw = new byte[count];
		byte[] pitch = new byte[count];
		byte[] headYaw = new byte[count];
		long[] uuidMost = new long[count];
		long[] uuidLeast = new long[count];

		for (int i = 0; i < count; i++) {
			entityIds[i] = buf.readVarInt();
			entityTypes[i] = lut[buf.readVarInt()];
			blockX[i] = buf.readInt();
			blockY[i] = buf.readShort();
			blockZ[i] = buf.readInt();
			yaw[i] = buf.readByte();
			pitch[i] = buf.readByte();
			headYaw[i] = buf.readByte();
			uuidMost[i] = buf.readLong();
			uuidLeast[i] = buf.readLong();
		}

		return new LODEntityUpdatePayload(dimension, entityIds, entityTypes,
			blockX, blockY, blockZ, yaw, pitch, headYaw, uuidMost, uuidLeast);
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeResourceLocation(dimension);
		int count = entityIds.length;
		buf.writeVarInt(count);

		if (count == 0) return;

		// Build and write entity type LUT
		java.util.List<ResourceLocation> lut = new java.util.ArrayList<>();
		java.util.Map<ResourceLocation, Integer> lutIndex = new java.util.HashMap<>();
		for (ResourceLocation type : entityTypes) {
			if (!lutIndex.containsKey(type)) {
				lutIndex.put(type, lut.size());
				lut.add(type);
			}
		}

		buf.writeVarInt(lut.size());
		for (ResourceLocation type : lut) {
			buf.writeResourceLocation(type);
		}

		// Write per-entity data
		for (int i = 0; i < count; i++) {
			buf.writeVarInt(entityIds[i]);
			buf.writeVarInt(lutIndex.get(entityTypes[i]));
			buf.writeInt(blockX[i]);
			buf.writeShort(blockY[i]);
			buf.writeInt(blockZ[i]);
			buf.writeByte(yaw[i]);
			buf.writeByte(pitch[i]);
			buf.writeByte(headYaw[i]);
			buf.writeLong(uuidMost[i]);
			buf.writeLong(uuidLeast[i]);
		}
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
