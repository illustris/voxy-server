package me.cortex.voxy.server.client;

//? if HAS_DEBUG_SCREEN {
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * F3 debug screen entry showing voxy network bandwidth and sync stats.
 */
public class VoxyBandwidthDebugEntry implements DebugScreenEntry {

	@Override
	public void display(DebugScreenDisplayer displayer, Level level, LevelChunk chunk, LevelChunk chunk2) {
		double kbps = VoxyBandwidthTracker.getBytesPerSecond() / 1024.0;
		int sections = VoxyBandwidthTracker.getTotalSectionsReceived();
		int entities = ClientSyncHandler.getLODEntityManager().size();

		StringBuilder sb = new StringBuilder();
		sb.append(String.format("[Voxy] %.1f KB/s in | %d sections synced | %d LOD entities",
			kbps, sections, entities));

		int queueSize = VoxyBandwidthTracker.getServerQueueSize();
		int stateOrd = VoxyBandwidthTracker.getServerSyncState();
		if (queueSize >= 0 && stateOrd >= 0) {
			String stateName = switch (stateOrd) {
				case 0 -> "AWAITING";
				case 1 -> "TREE_BUILT";
				case 2 -> "L2_SENT";
				case 3 -> "SYNCING";
				case 4 -> "IDLE";
				default -> "?";
			};
			sb.append(String.format(" | %d queued (%s)", queueSize, stateName));

			int genCount = VoxyBandwidthTracker.getServerPendingGenCount();
			if (genCount > 0) {
				sb.append(String.format(" +%d gen", genCount));
			}
		}

		displayer.addLine(sb.toString());
	}
}
//?} else {
/*
// DebugScreenEntry does not exist before MC 1.21.11.
// This class is intentionally empty for older versions.
public class VoxyBandwidthDebugEntry {
}
*///?}
