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
 * S2C: Server periodically (~1 Hz) sends the current per-session metric
 * snapshot for the client's HUD telemetry overlay. A single packet carries
 * the full measurement set so the client can populate / update each metric's
 * ring buffer in one place.
 *
 * Field semantics (all per-second windows or instantaneous samples):
 * - chunksPerSecX10: completed chunk generations / s, multiplied by 10 to
 *   keep one decimal place over the wire as a varint.
 * - failedChunks: failed chunk generations in window.
 * - inFlightChunks: instantaneous in-flight chunk-load count.
 * - genQueueDepth: instantaneous genExecutor queue depth.
 * - getChunkAvgMs / voxelizeAvgMs: average per chunk over window.
 * - mcTickEmaMs: server tick time EMA (~5-sample @ 20 TPS).
 * - dispatchBudgetX100: AIMD budget * 100 to retain two decimals.
 * - sectionsSent: sections actually wire-shipped to this player.
 * - heartbeatsEmitted / heartbeatsSkipped: L2 heartbeat counts.
 * - sendQueueSize / pendingGenCount: instantaneous per-session.
 * - danglingColumns: instantaneous per-session.
 * - sessionsCount: total connected sessions.
 * - clientL1BatchesRx: count of MerkleClientL1Payload arrivals in window
 *   (how often the client is rounding-tripping the diff).
 * - sectionsEnqueued: sections added to send queues from diff results in
 *   window. Together with sectionsSent, exposes the streaming pipeline.
 */
//? if HAS_NEW_NETWORKING {
public record TelemetrySnapshotPayload(
	int chunksPerSecX10,
	int failedChunks,
	int inFlightChunks,
	int genQueueDepth,
	int getChunkAvgMs,
	int voxelizeAvgMs,
	int mcTickEmaMs,
	int dispatchBudgetX100,
	int sectionsSent,
	int heartbeatsEmitted,
	int heartbeatsSkipped,
	int sendQueueSize,
	int pendingGenCount,
	int danglingColumns,
	int sessionsCount,
	int clientL1BatchesRx,
	int sectionsEnqueued,
	int sectionCommits,
	int voxyIngestQueueSize
) implements CustomPacketPayload {

	public static final Type<TelemetrySnapshotPayload> TYPE =
		new Type<>(Identifier.parse("voxy-server:telemetry_snapshot"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TelemetrySnapshotPayload> CODEC =
		StreamCodec.of(TelemetrySnapshotPayload::write, TelemetrySnapshotPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, TelemetrySnapshotPayload p) {
		buf.writeVarInt(p.chunksPerSecX10);
		buf.writeVarInt(p.failedChunks);
		buf.writeVarInt(p.inFlightChunks);
		buf.writeVarInt(p.genQueueDepth);
		buf.writeVarInt(p.getChunkAvgMs);
		buf.writeVarInt(p.voxelizeAvgMs);
		buf.writeVarInt(p.mcTickEmaMs);
		buf.writeVarInt(p.dispatchBudgetX100);
		buf.writeVarInt(p.sectionsSent);
		buf.writeVarInt(p.heartbeatsEmitted);
		buf.writeVarInt(p.heartbeatsSkipped);
		buf.writeVarInt(p.sendQueueSize);
		buf.writeVarInt(p.pendingGenCount);
		buf.writeVarInt(p.danglingColumns);
		buf.writeVarInt(p.sessionsCount);
		buf.writeVarInt(p.clientL1BatchesRx);
		buf.writeVarInt(p.sectionsEnqueued);
		buf.writeVarInt(p.sectionCommits);
		buf.writeVarInt(p.voxyIngestQueueSize);
	}

	private static TelemetrySnapshotPayload read(RegistryFriendlyByteBuf buf) {
		return new TelemetrySnapshotPayload(
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
//?} else {
/*public record TelemetrySnapshotPayload(
	int chunksPerSecX10,
	int failedChunks,
	int inFlightChunks,
	int genQueueDepth,
	int getChunkAvgMs,
	int voxelizeAvgMs,
	int mcTickEmaMs,
	int dispatchBudgetX100,
	int sectionsSent,
	int heartbeatsEmitted,
	int heartbeatsSkipped,
	int sendQueueSize,
	int pendingGenCount,
	int danglingColumns,
	int sessionsCount,
	int clientL1BatchesRx,
	int sectionsEnqueued,
	int sectionCommits,
	int voxyIngestQueueSize
) implements FabricPacket {

	public static final PacketType<TelemetrySnapshotPayload> TYPE =
		PacketType.create(new ResourceLocation("voxy-server", "telemetry_snapshot"), TelemetrySnapshotPayload::new);

	public TelemetrySnapshotPayload(FriendlyByteBuf buf) {
		this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt());
	}

	@Override
	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(chunksPerSecX10);
		buf.writeVarInt(failedChunks);
		buf.writeVarInt(inFlightChunks);
		buf.writeVarInt(genQueueDepth);
		buf.writeVarInt(getChunkAvgMs);
		buf.writeVarInt(voxelizeAvgMs);
		buf.writeVarInt(mcTickEmaMs);
		buf.writeVarInt(dispatchBudgetX100);
		buf.writeVarInt(sectionsSent);
		buf.writeVarInt(heartbeatsEmitted);
		buf.writeVarInt(heartbeatsSkipped);
		buf.writeVarInt(sendQueueSize);
		buf.writeVarInt(pendingGenCount);
		buf.writeVarInt(danglingColumns);
		buf.writeVarInt(sessionsCount);
		buf.writeVarInt(clientL1BatchesRx);
		buf.writeVarInt(sectionsEnqueued);
		buf.writeVarInt(sectionCommits);
		buf.writeVarInt(voxyIngestQueueSize);
	}

	@Override
	public PacketType<?> getType() {
		return TYPE;
	}
}
*///?}
