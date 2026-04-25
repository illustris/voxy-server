package me.cortex.voxy.server.streaming;

import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.server.merkle.MerkleHashUtil;
import me.cortex.voxy.server.network.LODSectionPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/**
 * Converts a WorldSection into an LODSectionPayload suitable for network transmission.
 * Remaps Voxy internal block/biome IDs to vanilla registry IDs.
 */
public class SectionSerializer {

	/**
	 * Serialize a WorldSection into an LODSectionPayload.
	 * The section must be acquired (loaded) before calling this.
	 */
	public static LODSectionPayload serialize(WorldSection section, Mapper mapper, Identifier dimension) {
		long[] data = section._unsafeGetRawDataArray();

		// Build LUT of unique entries
		int lutSize = 0;
		long[] uniqueEntries = new long[Math.min(data.length, 65536)];
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lutMap = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(256);
		lutMap.defaultReturnValue(-1);

		short[] indexArray = new short[WorldSection.SECTION_VOLUME];
		for (int i = 0; i < data.length; i++) {
			long entry = data[i];
			int idx = lutMap.get(entry);
			if (idx == -1) {
				idx = lutSize;
				lutMap.put(entry, idx);
				if (lutSize < uniqueEntries.length) {
					uniqueEntries[lutSize] = entry;
				}
				lutSize++;
			}
			indexArray[i] = (short) idx;
		}

		// Convert LUT entries to vanilla registry IDs
		int[] blockStateIds = new int[lutSize];
		int[] biomeIds = new int[lutSize];
		byte[] light = new byte[lutSize];

		var stateEntries = mapper.getStateEntries();
		var biomeEntries = mapper.getBiomeEntries();

		for (int i = 0; i < lutSize; i++) {
			long entry = uniqueEntries[i];
			int blockId = Mapper.getBlockId(entry);
			int biomeId = Mapper.getBiomeId(entry);
			int lightVal = Mapper.getLightId(entry);

			// Get BlockState from mapper and convert to vanilla registry ID
			if (blockId >= 0 && blockId < stateEntries.length && stateEntries[blockId] != null) {
				var stateEntry = stateEntries[blockId];
				if (stateEntry.state != null) {
					blockStateIds[i] = Block.BLOCK_STATE_REGISTRY.getId(stateEntry.state);
				}
			}

			// Store biome ID for client remapping
			biomeIds[i] = biomeId;

			light[i] = (byte) lightVal;
		}

		return new LODSectionPayload(dimension, section.key, blockStateIds, biomeIds, light, indexArray);
	}

	/**
	 * Compute the xxHash64 of the serialized form of a section.
	 */
	public static long computeSectionHash(WorldSection section) {
		var serialized = SaveLoadSystem3.serialize(section);
		return MerkleHashUtil.hashSectionData(serialized.address, (int) serialized.size);
	}
}
