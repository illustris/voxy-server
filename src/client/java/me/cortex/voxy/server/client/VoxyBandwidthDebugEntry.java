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

		displayer.addLine(String.format("[Voxy] %.1f KB/s in | %d sections synced | %d LOD entities",
			kbps, sections, entities));
	}
}
//?} else {
/*
// DebugScreenEntry does not exist before MC 1.21.11.
// This class is intentionally empty for older versions.
public class VoxyBandwidthDebugEntry {
}
*///?}
