package me.cortex.voxy.server.client;

//? if HAS_NEW_NETWORKING {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
//?}
//? if HAS_SUBMIT_NODE_COLLECTOR {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
//? if HAS_RENDER_PIPELINES {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.LightTexture;
*///?}
//?}
//? if HAS_NEW_NETWORKING && !HAS_SUBMIT_NODE_COLLECTOR {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.LightTexture;
*///?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders entities vanilla's LevelRenderer drops. Vanilla skips an entity when
 * either its chunk isn't in the loaded/render-distance region or it fails the
 * per-entity {@link Entity#shouldRender} cull (bounding-box size * 64 * Entity
 * Distance setting). We catch both gaps and submit via EntityRenderDispatcher.
 *
 * Geometry past vanilla's projection far plane (renderDistanceBlocks * 4) is
 * GPU-clipped regardless of how we draw -- {@link
 * me.cortex.voxy.server.mixin.client.GameRendererMixin} lifts that to
 * {@code lodRadius * 32} blocks so this whole pipeline is actually visible.
 *
 * Render-API branches (per-MC differences):
 * - 26.1+: submit/extract via LevelRenderContext + SubmitNodeCollector.
 * - 1.21.11: same submit/extract API, WorldRenderContext from .world.
 * - 1.21.1: immediate-mode dispatcher.render via legacy WorldRenderContext.
 * - 1.20.1: stub (different VertexConsumer API; not worth porting).
 */
public class LODEntityRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("voxy-server-client");

	//? if HAS_NEW_NETWORKING {
	private static final long FAILED_TYPES_RETRY_MS = 60_000;

	private final Set<Identifier> failedEntityTypes = new HashSet<>();
	private long failedTypesClearTimeMs = System.currentTimeMillis();
	// Track game tick so we tick each far entity exactly once per game tick;
	// vanilla's ClientLevel skips entities in unloaded chunks, so without
	// this their interpolation/animations would freeze.
	private long lastTickedGameTime = -1;

	public LODEntityRenderer() {
	}

	/**
	 * Entities vanilla's LevelRenderer skips this frame. Vanilla skips when
	 * the chunk isn't loaded OR when {@link Entity#shouldRender} returns false
	 * (per-entity distance cull). Both cases land here.
	 */
	private List<Entity> collectFarEntities(Minecraft mc, ClientLevel level) {
		Vec3 cameraPos = mc.gameRenderer.getMainCamera()
			//? if HAS_LOOKUP_OR_THROW {
			.position()
			//?} else {
			/*.getPosition()
			*///?}
			;
		List<Entity> result = new ArrayList<>();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity == mc.player) continue;
			boolean chunkLoaded = level.getChunkSource().hasChunk(
				entity.getBlockX() >> 4, entity.getBlockZ() >> 4);
			if (chunkLoaded && entity.shouldRender(cameraPos.x, cameraPos.y, cameraPos.z)) continue;
			result.add(entity);
		}
		return result;
	}

	/**
	 * Tick each entity once per game tick. Vanilla's ClientLevel does not
	 * tick entities in unloaded chunks. Without this their delta-position
	 * interpolation, head rotation, and limb animations freeze.
	 *
	 * noPhysics is forced on during the tick to suppress gravity/collision
	 * (the ground isn't loaded so the entity would fall forever, and the
	 * resulting motion would feed the animation system as a permanent run
	 * cycle).
	 */
	private void tickFarEntities(ClientLevel level, List<Entity> entities) {
		long gameTime = level.getGameTime();
		if (gameTime == lastTickedGameTime) return;
		lastTickedGameTime = gameTime;
		for (Entity entity : entities) {
			boolean wasNoPhysics = entity.noPhysics;
			boolean wasOnGround = entity.onGround();
			entity.noPhysics = true;
			entity.setOnGround(true);
			entity.setDeltaMovement(Vec3.ZERO);
			try { entity.tick(); } catch (Exception ignored) {}
			finally {
				entity.noPhysics = wasNoPhysics;
				entity.setOnGround(wasOnGround);
			}
		}
	}

	private void retryFailedTypes() {
		long now = System.currentTimeMillis();
		if (now - failedTypesClearTimeMs > FAILED_TYPES_RETRY_MS) {
			failedEntityTypes.clear();
			failedTypesClearTimeMs = now;
		}
	}
	//?}

	//? if HAS_SUBMIT_NODE_COLLECTOR {
	// On MC < 26.1, LightCoordsUtil doesn't exist; LightTexture.FULL_SKY is the
	// equivalent skylight=15/blocklight=0 lightmap value.
	//? if HAS_RENDER_PIPELINES {
	private static final int FULL_SKY_LIGHT = LightCoordsUtil.FULL_SKY;
	//?} else {
	/*private static final int FULL_SKY_LIGHT = LightTexture.FULL_SKY;
	*///?}

	//? if HAS_RENDER_PIPELINES {
	public void render(LevelRenderContext context) {
		renderInternal(context.poseStack(), context.bufferSource(), context.submitNodeCollector());
	}
	//?} else {
	/*public void render(WorldRenderContext context) {
		renderInternal(context.matrices(), context.consumers(), context.commandQueue());
	}
	*///?}

	private void renderInternal(PoseStack poseStack, MultiBufferSource bufferSource,
								 SubmitNodeCollector submitCollector) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;

		List<Entity> farEntities = collectFarEntities(mc, level);
		if (farEntities.isEmpty()) return;

		tickFarEntities(level, farEntities);
		retryFailedTypes();

		Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
		CameraRenderState cameraState = new CameraRenderState();
		cameraState.pos = cameraPos;
		cameraState.initialized = true;

		for (Entity entity : farEntities) {
			Identifier entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
			if (failedEntityTypes.contains(entityType)) continue;

			double rx = entity.getX() - cameraPos.x;
			double ry = entity.getY() - cameraPos.y;
			double rz = entity.getZ() - cameraPos.z;
			var savedPose = poseStack.last();
			try {
				EntityRenderState renderState = dispatcher.extractEntity(entity, 0.0f);
				renderState.lightCoords = FULL_SKY_LIGHT;
				poseStack.pushPose();
				dispatcher.submit(renderState, cameraState, rx, ry, rz, poseStack, submitCollector);
				poseStack.popPose();
			} catch (Exception e) {
				while (poseStack.last() != savedPose) poseStack.popPose();
				LOGGER.warn("[LODEntity] Failed to render model for {}: {}",
					entityType, e.getMessage());
				failedEntityTypes.add(entityType);
			}
		}
	}
	//?}

	//? if HAS_NEW_NETWORKING && !HAS_SUBMIT_NODE_COLLECTOR {
	/*// Legacy 1.20.5..1.21.10 path. No SubmitNodeCollector; uses the old
	// immediate-mode dispatcher.render(entity, x, y, z, yaw, partialTick,
	// poseStack, bufferSource, packedLight) signature. Different
	// WorldRenderContext shape: matrixStack() + consumers() + tickCounter().
	private static final int FULL_SKY_LIGHT = LightTexture.FULL_SKY;

	public void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;

		PoseStack poseStack = context.matrixStack();
		MultiBufferSource bufferSource = context.consumers();
		if (poseStack == null || bufferSource == null) return;
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(false);

		List<Entity> farEntities = collectFarEntities(mc, level);
		if (farEntities.isEmpty()) return;

		tickFarEntities(level, farEntities);
		retryFailedTypes();

		Vec3 cameraPos = mc.gameRenderer.getMainCamera().getPosition();
		EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();

		for (Entity entity : farEntities) {
			Identifier entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
			if (failedEntityTypes.contains(entityType)) continue;

			double rx = entity.getX() - cameraPos.x;
			double ry = entity.getY() - cameraPos.y;
			double rz = entity.getZ() - cameraPos.z;
			var savedPose = poseStack.last();
			try {
				poseStack.pushPose();
				dispatcher.render(entity, rx, ry, rz, entity.getYRot(), partialTick,
					poseStack, bufferSource, FULL_SKY_LIGHT);
				poseStack.popPose();
			} catch (Exception e) {
				while (poseStack.last() != savedPose) poseStack.popPose();
				LOGGER.warn("[LODEntity] Failed to render model for {}: {}",
					entityType, e.getMessage());
				failedEntityTypes.add(entityType);
			}
		}
	}
	*///?}

	//? if !HAS_NEW_NETWORKING {
	/*// 1.20.1 stub. The chained VertexConsumer / WorldRenderContext shape
	// diverges further; not worth porting unless asked.
	public LODEntityRenderer() {
	}
	*///?}
}
