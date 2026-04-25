package me.cortex.voxy.server.client;

//? if HAS_RENDER_PIPELINES {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.voxy.common.world.WorldEngine;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
//?}

/**
 * Renders debug visualizations:
 * - LOD sync radius border (toggled by keybind)
 * - Section highlight boxes when LODs are updated (toggled by /voxyhighlight)
 *
 * On MC versions before 26.1, the render pipeline APIs are not available and
 * the render method is a no-op.
 */
public class VoxyDebugRenderer {
	//? if HAS_RENDER_PIPELINES {

	private static final RenderType LINE_TYPE = RenderType.create(
		"voxy_debug_lines",
		RenderSetup.builder(RenderPipelines.LINES).createRenderSetup()
	);

	private static volatile boolean borderEnabled = false;

	public static boolean isBorderEnabled() {
		return borderEnabled;
	}

	public static void toggleBorder() {
		borderEnabled = !borderEnabled;
	}

	public void render(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) return;

		boolean doBorder = borderEnabled && ClientSyncHandler.getMaxRadius() > 0;
		boolean doHighlights = LODSectionHighlightTracker.isEnabled();

		if (!doBorder && !doHighlights) return;

		Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.poseStack();
		MultiBufferSource bufferSource = context.bufferSource();
		VertexConsumer consumer = bufferSource.getBuffer(LINE_TYPE);
		Matrix4f matrix = poseStack.last().pose();

		if (doBorder) {
			renderBorder(consumer, matrix, cameraPos, mc, level);
		}

		if (doHighlights) {
			renderHighlights(consumer, matrix, cameraPos);
		}
	}

	private void renderBorder(VertexConsumer consumer, Matrix4f matrix,
							   Vec3 cameraPos, Minecraft mc, ClientLevel level) {
		int radius = ClientSyncHandler.getMaxRadius();
		double radiusBlocks = radius * 32.0;

		double playerX = mc.player.getX();
		double playerZ = mc.player.getZ();

		float x0 = (float) (playerX - radiusBlocks - cameraPos.x);
		float z0 = (float) (playerZ - radiusBlocks - cameraPos.z);
		float x1 = (float) (playerX + radiusBlocks - cameraPos.x);
		float z1 = (float) (playerZ + radiusBlocks - cameraPos.z);
		float y0 = (float) (level.getMinY() - cameraPos.y);
		float y1 = (float) (level.getMaxY() - cameraPos.y);

		float r = 0.0f, g = 1.0f, b = 1.0f, a = 0.8f;
		drawWireframeBox(consumer, matrix, x0, y0, z0, x1, y1, z1, r, g, b, a);
	}

	private void renderHighlights(VertexConsumer consumer, Matrix4f matrix,
								   Vec3 cameraPos) {
		long[] highlights = LODSectionHighlightTracker.getActiveHighlights();
		if (highlights.length == 0) return;

		long now = System.currentTimeMillis();

		for (int i = 0; i < highlights.length; i += 2) {
			long sectionKey = highlights[i];
			long timestamp = highlights[i + 1];

			float age = (now - timestamp) / 2500.0f;
			float alpha = Math.max(0, 1.0f - age) * 0.6f;
			if (alpha <= 0.01f) continue;

			int sx = WorldEngine.getX(sectionKey);
			int sy = WorldEngine.getY(sectionKey);
			int sz = WorldEngine.getZ(sectionKey);

			float x0 = (float) (sx * 32.0 - cameraPos.x);
			float y0 = (float) (sy * 32.0 - cameraPos.y);
			float z0 = (float) (sz * 32.0 - cameraPos.z);
			float x1 = x0 + 32.0f;
			float y1 = y0 + 32.0f;
			float z1 = z0 + 32.0f;

			drawWireframeBox(consumer, matrix, x0, y0, z0, x1, y1, z1, 0.2f, 1.0f, 0.2f, alpha);
		}
	}

	/**
	 * Draws a wireframe box as 12 independent line segments (24 vertices).
	 * Uses LINES mode where each pair of vertices forms one line segment.
	 */
	private static void drawWireframeBox(VertexConsumer consumer, Matrix4f matrix,
										  float x0, float y0, float z0,
										  float x1, float y1, float z1,
										  float r, float g, float b, float a) {
		// Bottom face (4 edges along X and Z)
		line(consumer, matrix, x0, y0, z0, x1, y0, z0, r, g, b, a, 1, 0, 0);
		line(consumer, matrix, x1, y0, z0, x1, y0, z1, r, g, b, a, 0, 0, 1);
		line(consumer, matrix, x1, y0, z1, x0, y0, z1, r, g, b, a, 1, 0, 0);
		line(consumer, matrix, x0, y0, z1, x0, y0, z0, r, g, b, a, 0, 0, 1);
		// Top face (4 edges along X and Z)
		line(consumer, matrix, x0, y1, z0, x1, y1, z0, r, g, b, a, 1, 0, 0);
		line(consumer, matrix, x1, y1, z0, x1, y1, z1, r, g, b, a, 0, 0, 1);
		line(consumer, matrix, x1, y1, z1, x0, y1, z1, r, g, b, a, 1, 0, 0);
		line(consumer, matrix, x0, y1, z1, x0, y1, z0, r, g, b, a, 0, 0, 1);
		// Vertical pillars (4 edges along Y)
		line(consumer, matrix, x0, y0, z0, x0, y1, z0, r, g, b, a, 0, 1, 0);
		line(consumer, matrix, x1, y0, z0, x1, y1, z0, r, g, b, a, 0, 1, 0);
		line(consumer, matrix, x1, y0, z1, x1, y1, z1, r, g, b, a, 0, 1, 0);
		line(consumer, matrix, x0, y0, z1, x0, y1, z1, r, g, b, a, 0, 1, 0);
	}

	private static final float LINE_WIDTH = 2.0f;

	private static void line(VertexConsumer consumer, Matrix4f matrix,
							  float x0, float y0, float z0,
							  float x1, float y1, float z1,
							  float r, float g, float b, float a,
							  float nx, float ny, float nz) {
		consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(LINE_WIDTH);
		consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a).setNormal(nx, ny, nz).setLineWidth(LINE_WIDTH);
	}

	//?} else {

	/*
	// Render pipeline APIs are not available before MC 26.1.
	// The renderer is a no-op stub -- keybind and command still register
	// but nothing is drawn.

	public static boolean isBorderEnabled() { return false; }
	public static void toggleBorder() {}

	public void render(Object context) {}
	*///?}
}
