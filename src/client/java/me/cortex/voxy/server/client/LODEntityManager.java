package me.cortex.voxy.server.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.server.network.LODEntityRemovePayload;
import me.cortex.voxy.server.network.LODEntityUpdatePayload;
//? if HAS_IDENTIFIER {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.UUID;

/**
 * Client-side state for LOD-tracked entities.
 * Maintains the set of entities received from the server that are
 * outside vanilla tracking range but within the LOD radius.
 */
public class LODEntityManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("voxy-server-client");

	public record LODEntity(
		int entityId,
		/*$ rl_type */Identifier entityType,
		int blockX,
		int blockY,
		int blockZ,
		byte yaw,
		byte pitch,
		byte headYaw,
		UUID uuid,
		long lastUpdateTimeMs
	) {}

	private final Int2ObjectOpenHashMap<LODEntity> entities = new Int2ObjectOpenHashMap<>();

	public void applyUpdate(LODEntityUpdatePayload payload) {
		long now = System.currentTimeMillis();
		for (int i = 0; i < payload.count(); i++) {
			int id = payload.entityIds()[i];
			entities.put(id, new LODEntity(
				id,
				payload.entityTypes()[i],
				payload.blockX()[i],
				payload.blockY()[i],
				payload.blockZ()[i],
				payload.yaw()[i],
				payload.pitch()[i],
				payload.headYaw()[i],
				payload.uuid(i),
				now
			));
		}
		LOGGER.debug("[LODEntity] Applied {} updates, total tracked: {}", payload.count(), entities.size());
	}

	public void applyRemoval(LODEntityRemovePayload payload) {
		for (int id : payload.entityIds()) {
			entities.remove(id);
		}
		LOGGER.debug("[LODEntity] Removed {} entities, total tracked: {}", payload.entityIds().length, entities.size());
	}

	public void removeByNetworkId(int entityId) {
		entities.remove(entityId);
	}

	public boolean hasEntity(int entityId) {
		return entities.containsKey(entityId);
	}

	public void clear() {
		entities.clear();
	}

	public Collection<LODEntity> getEntities() {
		return entities.values();
	}

	public int size() {
		return entities.size();
	}
}
