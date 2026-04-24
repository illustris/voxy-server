package me.cortex.voxy.server.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.server.util.DebouncedLogger;
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
import net.minecraft.util.LightCoordsUtil;
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
 * Entities are sourced from two places:
 * - LODEntityManager: entities sent via custom LOD packets (custom transport mode)
 * - ClientLevel: entities sent via vanilla tracking packets (native transport mode)
 *   that are in unloaded chunks (vanilla's LevelRenderer won't render them)
 *
 * Entity types that fail to create or render as models automatically fall back
 * to billboard rendering so they remain visible.
 */
public class LODEntityRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("voxy-server-client");
	private static final DebouncedLogger DEBUG = new DebouncedLogger(LOGGER);
	private static final Identifier PLAYER_TYPE = Identifier.parse("minecraft:player");

	private static final RenderType DEBUG_QUADS_TYPE = RenderType.create(
		"lod_entity_billboard",
		RenderSetup.builder(RenderPipelines.DEBUG_QUADS).createRenderSetup()
	);

	private final LODEntityManager manager;
	private final VoxyServerClientConfig config;

	// Cached entity instances for model rendering mode in custom transport
	// (entityId -> dummy Entity). Not used for native transport entities since
	// those already exist as real Entity instances in ClientLevel.
	private final Int2ObjectOpenHashMap<Entity> entityCache = new Int2ObjectOpenHashMap<>();

	// Entity types that failed to create or render as models -- fall back to billboard
	private final Set<Identifier> failedEntityTypes = new HashSet<>();
	private long failedTypesClearTimeMs = System.currentTimeMillis();
	private static final long FAILED_TYPES_RETRY_MS = 60_000;

	// Track game tick to ensure we tick native entities exactly once per game tick
	private long lastTickedGameTime = -1;

	public LODEntityRenderer(LODEntityManager manager, VoxyServerClientConfig config) {
		this.manager = manager;
		this.config = config;
	}

	public void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;

		// Collect native far entities: real Entity instances in ClientLevel whose
		// chunks aren't loaded on the client (vanilla's LevelRenderer skips these).
		// In native transport mode the server adds them via vanilla tracking packets;
		// in custom mode this list will be empty.
		List<Entity> nativeFarEntities = collectNativeFarEntities(mc, level);

		// Tick native far entities once per game tick. Vanilla's ClientLevel
		// doesn't tick entities in unloaded chunks, so without this their
		// interpolation, rotation, and animations would freeze. By calling
		// tick() ourselves, vanilla's own code handles everything.
		long gameTime = level.getGameTime();
		if (gameTime != lastTickedGameTime && !nativeFarEntities.isEmpty()) {
			lastTickedGameTime = gameTime;
			for (Entity entity : nativeFarEntities) {
				try {
					entity.tick();
				} catch (Exception e) {
					// Entity tick may fail without loaded chunk data -- safe to skip
				}
			}
		}

		if (manager.size() == 0 && nativeFarEntities.isEmpty()) {
			DEBUG.flush();
			return;
		}

		Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource bufferSource = context.bufferSource();

		if ("model".equals(config.entityRenderMode)) {
			renderModels(mc, level, poseStack, context.submitNodeCollector(), bufferSource, cameraPos, nativeFarEntities);
		} else {
			renderBillboards(poseStack, bufferSource, cameraPos, nativeFarEntities);
		}

		// Clean up cached dummy entities that are no longer tracked via custom mode
		entityCache.keySet().removeIf(id -> !manager.hasEntity(id));
	}

	/**
	 * Find entities in ClientLevel that exist but whose chunk section is not
	 * loaded on the client. These are entities the server is tracking via native
	 * transport -- vanilla spawned them but LevelRenderer won't render them
	 * because isSectionCompiledAndVisible() fails for unloaded chunks.
	 */
	private List<Entity> collectNativeFarEntities(Minecraft mc, ClientLevel level) {
		List<Entity> result = new ArrayList<>();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity == mc.player) continue;
			// Already tracked via custom LOD packets -- handled separately
			if (manager.hasEntity(entity.getId())) continue;
			// Entity is in a loaded chunk -- vanilla renders it, skip
			if (isInLoadedChunk(level, entity)) continue;
			result.add(entity);
		}
		return result;
	}

	/**
	 * Returns true if the entity's chunk is loaded on the client, meaning
	 * vanilla's LevelRenderer will handle rendering. We should not LOD-render
	 * entities that vanilla is already rendering.
	 */
	private static boolean isInLoadedChunk(ClientLevel level, Entity entity) {
		return level.getChunkSource().hasChunk(
			entity.getBlockX() >> 4, entity.getBlockZ() >> 4
		);
	}

	// ---- Billboard rendering ------------------------------------------------

	private void renderBillboards(PoseStack poseStack, MultiBufferSource bufferSource,
								   Vec3 cameraPos, List<Entity> nativeFarEntities) {
		VertexConsumer consumer = bufferSource.getBuffer(DEBUG_QUADS_TYPE);

		// Render custom-mode entities from LODEntityManager
		for (LODEntityManager.LODEntity entity : manager.getEntities()) {
			if (isInLoadedChunkById(entity.entityId())) continue;

			Identifier entityType = entity.entityType();
			float[] color = getEntityColor(entityType);
			float halfWidth = 0.3f;
			float height = isPlayerType(entityType) ? 1.8f : 1.0f;

			renderBillboardQuad(consumer, poseStack, cameraPos,
				entity.blockX() + 0.5, entity.blockY(), entity.blockZ() + 0.5,
				color, halfWidth, height);
		}

		// Render native-mode entities from ClientLevel
		for (Entity entity : nativeFarEntities) {
			Identifier entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
			float[] color = getEntityColor(entityType);
			float halfWidth = 0.3f;
			float height = isPlayerType(entityType) ? 1.8f : 1.0f;

			renderBillboardQuad(consumer, poseStack, cameraPos,
				entity.getX(), entity.getY(), entity.getZ(),
				color, halfWidth, height);
		}
	}

	private void renderBillboardQuad(VertexConsumer consumer, PoseStack poseStack,
									  Vec3 cameraPos, double posX, double posY, double posZ,
									  float[] color, float halfWidth, float height) {
		double x = posX - cameraPos.x;
		double y = posY - cameraPos.y;
		double z = posZ - cameraPos.z;

		poseStack.pushPose();
		poseStack.translate(x, y, z);

		float cameraYaw = (float) Math.atan2(x, z);
		poseStack.mulPose(Axis.YP.rotation(cameraYaw));

		Matrix4f matrix = poseStack.last().pose();

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

	// ---- Model rendering ----------------------------------------------------

	private void renderModels(Minecraft mc, ClientLevel level, PoseStack poseStack,
							   net.minecraft.client.renderer.SubmitNodeCollector submitCollector,
							   MultiBufferSource bufferSource, Vec3 cameraPos,
							   List<Entity> nativeFarEntities) {
		// Periodically allow retries for failed entity types
		long now = System.currentTimeMillis();
		if (now - failedTypesClearTimeMs > FAILED_TYPES_RETRY_MS) {
			if (config.debugLogging && !failedEntityTypes.isEmpty()) {
				DEBUG.log("[LODEntity] Clearing {} failed entity types for retry", failedEntityTypes.size());
			}
			failedEntityTypes.clear();
			failedTypesClearTimeMs = now;
		}

		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		CameraRenderState cameraState = new CameraRenderState();
		cameraState.pos = cameraPos;
		cameraState.initialized = true;

		// Collect entities that need billboard fallback
		List<BillboardFallback> billboardFallback = null;
		int modelCount = 0, skippedCount = 0, failedCount = 0;

		// ---- Custom-mode entities from LODEntityManager ----
		for (LODEntityManager.LODEntity lodEntity : manager.getEntities()) {
			if (isInLoadedChunkById(lodEntity.entityId())) {
				skippedCount++;
				continue;
			}

			Identifier entityType = lodEntity.entityType();

			// Skip known-failed types and render as billboard instead
			if (failedEntityTypes.contains(entityType)) {
				failedCount++;
				if (billboardFallback == null) billboardFallback = new ArrayList<>();
				billboardFallback.add(new BillboardFallback(
					lodEntity.blockX() + 0.5, lodEntity.blockY(), lodEntity.blockZ() + 0.5, entityType));
				continue;
			}

			Entity entity = entityCache.get(lodEntity.entityId());
			boolean cached = entity != null;

			if (entity == null) {
				entity = createEntityInstance(level, lodEntity);
				if (entity == null) {
					failedEntityTypes.add(entityType);
					failedCount++;
					if (billboardFallback == null) billboardFallback = new ArrayList<>();
					billboardFallback.add(new BillboardFallback(
						lodEntity.blockX() + 0.5, lodEntity.blockY(), lodEntity.blockZ() + 0.5, entityType));
					continue;
				}
				entityCache.put(lodEntity.entityId(), entity);
			}

			// Update position and rotation from LOD data
			double posX = lodEntity.blockX() + 0.5;
			double posY = lodEntity.blockY();
			double posZ = lodEntity.blockZ() + 0.5;
			float yaw = lodEntity.yaw() * 360.0f / 256.0f;
			float pitch = lodEntity.pitch() * 360.0f / 256.0f;
			float headYaw = lodEntity.headYaw() * 360.0f / 256.0f;

			entity.setPos(posX, posY, posZ);
			entity.xOld = posX;
			entity.yOld = posY;
			entity.zOld = posZ;
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

			if (submitEntityModel(dispatcher, cameraState, entity, posX, posY, posZ,
					cameraPos, poseStack, submitCollector)) {
				modelCount++;
				if (config.debugLogging) {
					DEBUG.log("[LODEntity] Rendered custom model: type={} pos=({},{},{}) cached={}",
						entityType, (int) posX, (int) posY, (int) posZ, cached);
				}
			} else {
				entityCache.remove(lodEntity.entityId());
				failedEntityTypes.add(entityType);
				failedCount++;
				if (billboardFallback == null) billboardFallback = new ArrayList<>();
				billboardFallback.add(new BillboardFallback(posX, posY, posZ, entityType));
			}
		}

		// ---- Native-mode entities from ClientLevel ----
		// These are real Entity instances with full data (sub-block position,
		// smooth interpolation, equipment, metadata). Render them directly.
		for (Entity entity : nativeFarEntities) {
			Identifier entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());

			if (failedEntityTypes.contains(entityType)) {
				failedCount++;
				if (billboardFallback == null) billboardFallback = new ArrayList<>();
				billboardFallback.add(new BillboardFallback(
					entity.getX(), entity.getY(), entity.getZ(), entityType));
				continue;
			}

			if (submitEntityModel(dispatcher, cameraState, entity, entity.getX(), entity.getY(), entity.getZ(),
					cameraPos, poseStack, submitCollector)) {
				modelCount++;
				if (config.debugLogging) {
					DEBUG.log("[LODEntity] Rendered native model: type={} pos=({},{},{})",
						entityType, (int) entity.getX(), (int) entity.getY(), (int) entity.getZ());
				}
			} else {
				failedEntityTypes.add(entityType);
				failedCount++;
				if (billboardFallback == null) billboardFallback = new ArrayList<>();
				billboardFallback.add(new BillboardFallback(
					entity.getX(), entity.getY(), entity.getZ(), entityType));
			}
		}

		// Render billboard fallback for entities that couldn't be rendered as models
		int billboardCount = 0;
		if (billboardFallback != null) {
			billboardCount = billboardFallback.size();
			VertexConsumer consumer = bufferSource.getBuffer(DEBUG_QUADS_TYPE);
			for (BillboardFallback fb : billboardFallback) {
				float[] color = getEntityColor(fb.entityType);
				float halfWidth = 0.3f;
				float height = isPlayerType(fb.entityType) ? 1.8f : 1.0f;
				renderBillboardQuad(consumer, poseStack, cameraPos,
					fb.posX, fb.posY, fb.posZ, color, halfWidth, height);
			}
		}

		if (config.debugLogging) {
			DEBUG.log("[LODEntity] Frame: models={} billboards={} skipped={} failed={}",
				modelCount, billboardCount, skippedCount, failedCount);
		}
		DEBUG.flush();
	}

	/**
	 * Submit an entity model for rendering with FULL_SKY lighting.
	 * Returns true on success, false on failure (caller should billboard-fallback).
	 */
	private boolean submitEntityModel(EntityRenderDispatcher dispatcher, CameraRenderState cameraState,
									   Entity entity, double posX, double posY, double posZ,
									   Vec3 cameraPos, PoseStack poseStack,
									   net.minecraft.client.renderer.SubmitNodeCollector submitCollector) {
		double rx = posX - cameraPos.x;
		double ry = posY - cameraPos.y;
		double rz = posZ - cameraPos.z;

		var savedPose = poseStack.last();
		try {
			EntityRenderState renderState = dispatcher.extractEntity(entity, 0.0f);
			renderState.lightCoords = LightCoordsUtil.FULL_SKY;
			poseStack.pushPose();
			dispatcher.submit(renderState, cameraState, rx, ry, rz, poseStack, submitCollector);
			poseStack.popPose();
			return true;
		} catch (Exception e) {
			while (poseStack.last() != savedPose) {
				poseStack.popPose();
			}
			LOGGER.warn("[LODEntity] Failed to render model for {}: {}",
				BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), e.getMessage());
			return false;
		}
	}

	// ---- Entity instance creation (custom mode only) ------------------------

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
			} else if (config.debugLogging) {
				DEBUG.log("[LODEntity] Created entity instance: type={}", lodEntity.entityType());
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

	// ---- Utility -------------------------------------------------------------

	/**
	 * Check if an entity (by ID) is in a loaded chunk on the client.
	 * Used for custom-mode entities whose position comes from LODEntityManager.
	 */
	private boolean isInLoadedChunkById(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		Entity entity = mc.level.getEntity(entityId);
		if (entity == null) return false;
		return isInLoadedChunk(mc.level, entity);
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

	private record BillboardFallback(double posX, double posY, double posZ, Identifier entityType) {}
}
