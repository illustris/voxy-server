package me.cortex.voxy.server.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Renders LOD-tracked entities that are outside vanilla's entity tracking range.
 * Supports two rendering modes:
 * - "billboard": colored camera-facing quads (cheap, always visible)
 * - "model": full vanilla entity models via EntityRenderDispatcher
 *
 * Entity types that fail to create or render as models automatically fall back
 * to billboard rendering so they remain visible.
 */
public class LODEntityRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("voxy-server-client");
	private static final Identifier PLAYER_TYPE = Identifier.parse("minecraft:player");

	private static final RenderType DEBUG_QUADS_TYPE = RenderType.create(
		"lod_entity_billboard",
		RenderSetup.builder(RenderPipelines.DEBUG_QUADS).createRenderSetup()
	);

	private final LODEntityManager manager;
	private final VoxyServerClientConfig config;

	// Cached entity instances for model rendering mode (entityId -> Entity)
	private final Int2ObjectOpenHashMap<Entity> entityCache = new Int2ObjectOpenHashMap<>();

	// Entity types that failed to create or render as models -- fall back to billboard
	private final Set<Identifier> failedEntityTypes = new HashSet<>();
	private long failedTypesClearTimeMs = System.currentTimeMillis();
	private static final long FAILED_TYPES_RETRY_MS = 60_000;

	public LODEntityRenderer(LODEntityManager manager, VoxyServerClientConfig config) {
		this.manager = manager;
		this.config = config;
	}

	public void render(LevelRenderContext context) {
		if (manager.size() == 0) return;

		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;

		Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource bufferSource = context.bufferSource();

		if ("model".equals(config.entityRenderMode)) {
			renderModels(mc, level, poseStack, context.submitNodeCollector(), bufferSource, cameraPos);
		} else {
			renderBillboards(poseStack, bufferSource, cameraPos, manager.getEntities());
		}

		// Clean up cached entities that are no longer tracked
		entityCache.keySet().removeIf(id -> !manager.hasEntity(id));
	}

	private void renderBillboards(PoseStack poseStack, MultiBufferSource bufferSource,
								   Vec3 cameraPos, Iterable<LODEntityManager.LODEntity> entities) {
		VertexConsumer consumer = bufferSource.getBuffer(DEBUG_QUADS_TYPE);

		for (LODEntityManager.LODEntity entity : entities) {
			// Skip if vanilla is already rendering this entity
			if (isVanillaTracked(entity.entityId())) continue;

			double x = entity.blockX() + 0.5 - cameraPos.x;
			double y = entity.blockY() - cameraPos.y;
			double z = entity.blockZ() + 0.5 - cameraPos.z;

			float[] color = getEntityColor(entity.entityType());
			float halfWidth = 0.3f;
			float height = isPlayerType(entity.entityType()) ? 1.8f : 1.0f;

			// Camera-facing billboard
			poseStack.pushPose();
			poseStack.translate(x, y, z);

			// Face the camera by rotating around Y axis
			float cameraYaw = (float) Math.atan2(x, z);
			poseStack.mulPose(Axis.YP.rotation(cameraYaw));

			Matrix4f matrix = poseStack.last().pose();

			// Draw a filled quad (4 vertices for QUADS mode)
			consumer.addVertex(matrix, -halfWidth, 0, 0)
				.setColor(color[0], color[1], color[2], 0.8f);
			consumer.addVertex(matrix, halfWidth, 0, 0)
				.setColor(color[0], color[1], color[2], 0.8f);
			consumer.addVertex(matrix, halfWidth, height, 0)
				.setColor(color[0], color[1], color[2], 0.8f);
			consumer.addVertex(matrix, -halfWidth, height, 0)
				.setColor(color[0], color[1], color[2], 0.8f);

			poseStack.popPose();
		}
	}

	private void renderModels(Minecraft mc, ClientLevel level, PoseStack poseStack,
							   net.minecraft.client.renderer.SubmitNodeCollector submitCollector,
							   MultiBufferSource bufferSource, Vec3 cameraPos) {
		// Periodically allow retries for failed entity types
		long now = System.currentTimeMillis();
		if (now - failedTypesClearTimeMs > FAILED_TYPES_RETRY_MS) {
			failedEntityTypes.clear();
			failedTypesClearTimeMs = now;
		}

		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		CameraRenderState cameraState = new CameraRenderState();
		cameraState.pos = cameraPos;
		cameraState.initialized = true;

		// Collect entities that need billboard fallback
		List<LODEntityManager.LODEntity> billboardFallback = null;

		for (LODEntityManager.LODEntity lodEntity : manager.getEntities()) {
			if (isVanillaTracked(lodEntity.entityId())) continue;

			// Skip known-failed types and render as billboard instead
			if (failedEntityTypes.contains(lodEntity.entityType())) {
				if (billboardFallback == null) billboardFallback = new ArrayList<>();
				billboardFallback.add(lodEntity);
				continue;
			}

			Entity entity = entityCache.get(lodEntity.entityId());

			// Create or reuse cached entity instance
			if (entity == null) {
				entity = createEntityInstance(level, lodEntity);
				if (entity == null) {
					failedEntityTypes.add(lodEntity.entityType());
					if (billboardFallback == null) billboardFallback = new ArrayList<>();
					billboardFallback.add(lodEntity);
					continue;
				}
				entityCache.put(lodEntity.entityId(), entity);
			}

			// Update position and rotation
			double posX = lodEntity.blockX() + 0.5;
			double posY = lodEntity.blockY();
			double posZ = lodEntity.blockZ() + 0.5;
			float yaw = lodEntity.yaw() * 360.0f / 256.0f;
			float pitch = lodEntity.pitch() * 360.0f / 256.0f;
			float headYaw = lodEntity.headYaw() * 360.0f / 256.0f;

			entity.setPos(posX, posY, posZ);
			entity.setYRot(yaw);
			entity.yRotO = yaw;
			entity.setXRot(pitch);
			entity.xRotO = pitch;
			if (entity instanceof LivingEntity le) {
				le.yBodyRot = yaw;
				le.yBodyRotO = yaw;
				le.yHeadRot = headYaw;
				le.yHeadRotO = headYaw;
			}

			double rx = posX - cameraPos.x;
			double ry = posY - cameraPos.y;
			double rz = posZ - cameraPos.z;

			try {
				// Extract render state and submit to the render pipeline
				EntityRenderState renderState = dispatcher.extractEntity(entity, 0.0f);
				poseStack.pushPose();
				dispatcher.submit(renderState, cameraState, rx, ry, rz, poseStack, submitCollector);
				poseStack.popPose();
			} catch (Exception e) {
				LOGGER.warn("[LODEntity] Failed to render model for {}, falling back to billboard: {}",
					lodEntity.entityType(), e.getMessage());
				entityCache.remove(lodEntity.entityId());
				failedEntityTypes.add(lodEntity.entityType());
				if (billboardFallback == null) billboardFallback = new ArrayList<>();
				billboardFallback.add(lodEntity);
			}
		}

		// Render billboard fallback for entities that couldn't be rendered as models
		if (billboardFallback != null) {
			renderBillboards(poseStack, bufferSource, cameraPos, billboardFallback);
		}
	}

	private Entity createEntityInstance(ClientLevel level, LODEntityManager.LODEntity lodEntity) {
		if (isPlayerType(lodEntity.entityType())) {
			return createPlayerInstance(level, lodEntity);
		}

		Optional<EntityType<?>> typeOpt = BuiltInRegistries.ENTITY_TYPE.getOptional(lodEntity.entityType());
		if (typeOpt.isEmpty()) {
			LOGGER.warn("[LODEntity] Unknown entity type: {}", lodEntity.entityType());
			return null;
		}

		try {
			Entity entity = typeOpt.get().create(level, EntitySpawnReason.LOAD);
			if (entity == null) {
				LOGGER.warn("[LODEntity] EntityType.create() returned null for {}", lodEntity.entityType());
			}
			return entity;
		} catch (Exception e) {
			LOGGER.warn("[LODEntity] Failed to create entity instance for {}: {}", lodEntity.entityType(), e.getMessage());
			return null;
		}
	}

	private Entity createPlayerInstance(ClientLevel level, LODEntityManager.LODEntity lodEntity) {
		try {
			com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(
				lodEntity.uuid(), "LODPlayer"
			);
			return new RemotePlayer(level, profile);
		} catch (Exception e) {
			LOGGER.warn("[LODEntity] Failed to create player instance: {}", e.getMessage());
			return null;
		}
	}

	private boolean isVanillaTracked(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		return mc.level.getEntity(entityId) != null;
	}

	private static boolean isPlayerType(Identifier type) {
		return PLAYER_TYPE.equals(type);
	}

	private static float[] getEntityColor(Identifier entityType) {
		if (isPlayerType(entityType)) {
			return new float[]{1.0f, 1.0f, 1.0f}; // White for players
		}

		String path = entityType.getPath();

		// Hostile mobs
		if (path.contains("zombie") || path.contains("skeleton") || path.contains("creeper") ||
			path.contains("spider") || path.contains("enderman") || path.contains("witch") ||
			path.contains("phantom") || path.contains("blaze") || path.contains("ghast") ||
			path.contains("wither") || path.contains("slime") || path.contains("pillager") ||
			path.contains("vindicator") || path.contains("ravager") || path.contains("hoglin") ||
			path.contains("piglin_brute") || path.contains("warden") || path.contains("breeze")) {
			return new float[]{1.0f, 0.2f, 0.2f}; // Red for hostile
		}

		// Passive mobs
		if (path.contains("cow") || path.contains("sheep") || path.contains("pig") ||
			path.contains("chicken") || path.contains("horse") || path.contains("donkey") ||
			path.contains("rabbit") || path.contains("cat") || path.contains("dog") ||
			path.contains("wolf") || path.contains("parrot") || path.contains("turtle") ||
			path.contains("bee") || path.contains("fox") || path.contains("frog") ||
			path.contains("sniffer") || path.contains("armadillo") || path.contains("camel")) {
			return new float[]{0.2f, 1.0f, 0.2f}; // Green for passive
		}

		return new float[]{0.4f, 0.6f, 1.0f}; // Blue for other
	}
}
