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
 * S2C: server pushes its current configuration to a connecting client.
 * Resent after a successful {@link ConfigEditPayload} so all sessions
 * always see the latest values.
 *
 * The {@code authorized} flag tells the client whether *this* player has
 * permission level &gt;= 4 (or {@code Permissions.COMMANDS_ADMIN}) on the
 * server, which the GUI uses to enable/disable edit controls.
 */
//? if HAS_NEW_NETWORKING {
public record ConfigSnapshotPayload(
	boolean authorized,
	int lodStreamRadius,
	int maxSectionsPerTickPerPlayer,
	int sectionsPerPacket,
	boolean generateOnChunkLoad,
	int workerThreads,
	int dirtyScanInterval,
	int maxDirtyChunksPerScan,
	boolean debugLogging,
	int targetTps,
	boolean enableEntitySync,
	int entitySyncIntervalTicks,
	int maxLODEntitiesPerPlayer,
	String entitySyncMode,
	boolean compatSableAutoTrackingRange,
	long l0HashCacheCapBytes,
	int merkleHeartbeatTicks,
	int merkleSlideTeleportThreshold,
	int maxInFlightChunks
) implements CustomPacketPayload {

	public static final Type<ConfigSnapshotPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:config_snapshot"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSnapshotPayload> CODEC =
		StreamCodec.of(ConfigSnapshotPayload::write, ConfigSnapshotPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, ConfigSnapshotPayload p) {
		buf.writeBoolean(p.authorized);
		buf.writeVarInt(p.lodStreamRadius);
		buf.writeVarInt(p.maxSectionsPerTickPerPlayer);
		buf.writeVarInt(p.sectionsPerPacket);
		buf.writeBoolean(p.generateOnChunkLoad);
		buf.writeVarInt(p.workerThreads);
		buf.writeVarInt(p.dirtyScanInterval);
		buf.writeVarInt(p.maxDirtyChunksPerScan);
		buf.writeBoolean(p.debugLogging);
		buf.writeVarInt(p.targetTps);
		buf.writeBoolean(p.enableEntitySync);
		buf.writeVarInt(p.entitySyncIntervalTicks);
		buf.writeVarInt(p.maxLODEntitiesPerPlayer);
		buf.writeUtf(p.entitySyncMode, 32);
		buf.writeBoolean(p.compatSableAutoTrackingRange);
		buf.writeVarLong(p.l0HashCacheCapBytes);
		buf.writeVarInt(p.merkleHeartbeatTicks);
		buf.writeVarInt(p.merkleSlideTeleportThreshold);
		buf.writeVarInt(p.maxInFlightChunks);
	}

	private static ConfigSnapshotPayload read(RegistryFriendlyByteBuf buf) {
		return new ConfigSnapshotPayload(
			buf.readBoolean(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readBoolean(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readBoolean(),
			buf.readVarInt(),
			buf.readBoolean(),
			buf.readVarInt(), buf.readVarInt(),
			buf.readUtf(32),
			buf.readBoolean(),
			buf.readVarLong(),
			buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record ConfigSnapshotPayload(
	boolean authorized,
	int lodStreamRadius,
	int maxSectionsPerTickPerPlayer,
	int sectionsPerPacket,
	boolean generateOnChunkLoad,
	int workerThreads,
	int dirtyScanInterval,
	int maxDirtyChunksPerScan,
	boolean debugLogging,
	int targetTps,
	boolean enableEntitySync,
	int entitySyncIntervalTicks,
	int maxLODEntitiesPerPlayer,
	String entitySyncMode,
	boolean compatSableAutoTrackingRange,
	long l0HashCacheCapBytes,
	int merkleHeartbeatTicks,
	int merkleSlideTeleportThreshold,
	int maxInFlightChunks
) implements FabricPacket {

	public static final PacketType<ConfigSnapshotPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "config_snapshot"), ConfigSnapshotPayload::new);

	public ConfigSnapshotPayload(FriendlyByteBuf buf) {
		this(
			buf.readBoolean(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readBoolean(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readBoolean(),
			buf.readVarInt(),
			buf.readBoolean(),
			buf.readVarInt(), buf.readVarInt(),
			buf.readUtf(32),
			buf.readBoolean(),
			buf.readVarLong(),
			buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeBoolean(authorized);
		buf.writeVarInt(lodStreamRadius);
		buf.writeVarInt(maxSectionsPerTickPerPlayer);
		buf.writeVarInt(sectionsPerPacket);
		buf.writeBoolean(generateOnChunkLoad);
		buf.writeVarInt(workerThreads);
		buf.writeVarInt(dirtyScanInterval);
		buf.writeVarInt(maxDirtyChunksPerScan);
		buf.writeBoolean(debugLogging);
		buf.writeVarInt(targetTps);
		buf.writeBoolean(enableEntitySync);
		buf.writeVarInt(entitySyncIntervalTicks);
		buf.writeVarInt(maxLODEntitiesPerPlayer);
		buf.writeUtf(entitySyncMode, 32);
		buf.writeBoolean(compatSableAutoTrackingRange);
		buf.writeVarLong(l0HashCacheCapBytes);
		buf.writeVarInt(merkleHeartbeatTicks);
		buf.writeVarInt(merkleSlideTeleportThreshold);
		buf.writeVarInt(maxInFlightChunks);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
