package me.cortex.voxy.server.client;

//? if HAS_RENDER_PIPELINES {
/**
 * Stub on MC 26.1+: GuiGraphics + HudRenderCallback were removed. The
 * data plumbing (TelemetrySnapshotPayload + VoxyTelemetryHistory) still
 * works; only the visual overlay is missing here. Re-implement against
 * the new GuiRenderer / GuiRenderState API to restore.
 */
public class VoxyTelemetryOverlay {}
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class VoxyTelemetryOverlay {
	private static final int WIDTH = 220;
	private static final int BAR_HEIGHT = 24;
	private static final int LABEL_HEIGHT = 9;
	private static final int ROW_HEIGHT = BAR_HEIGHT + LABEL_HEIGHT + 2;
	private static final int PADDING = 4;
	private static final int BACKGROUND_COLOR = 0x80000000;
	private static final int BAR_COLOR = 0xFF7FCB7F;
	private static final int BAR_PEAK_COLOR = 0xFFCB7F7F;
	private static final int LABEL_COLOR = 0xFFFFFFFF;

	public static void render(GuiGraphics g, float tickDelta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.font == null) return;

		VoxyServerClientConfig cfg = VoxyServerClientConfig.get();
		if (!cfg.telemetryOverlayEnabled) return;
		if (cfg.telemetryOverlayRequiresF3 && !isDebugScreenOpen(mc)) return;
		if (VoxyTelemetryHistory.getSampleCount() == 0) return;

		Font font = mc.font;
		int screenW = g.guiWidth();

		int x = screenW - WIDTH - PADDING;
		int y = PADDING;

		for (VoxyTelemetryHistory.Metric metric : VoxyTelemetryHistory.Metric.values()) {
			if (!cfg.isGraphMetricEnabled(metric.key)) continue;

			float[] history = VoxyTelemetryHistory.snapshot(metric);
			float max = 0f;
			for (float v : history) {
				if (Float.isNaN(v)) continue;
				if (v > max) max = v;
			}
			float current = VoxyTelemetryHistory.latest(metric);

			g.fill(x, y, x + WIDTH, y + ROW_HEIGHT, BACKGROUND_COLOR);

			String label = metric.label;
			String valueStr = formatValue(current);
			g.drawString(font, label, x + 2, y + 1, LABEL_COLOR, false);
			int valW = font.width(valueStr);
			g.drawString(font, valueStr, x + WIDTH - valW - 2, y + 1, LABEL_COLOR, false);

			int barTop = y + LABEL_HEIGHT + 1;
			int barBottom = barTop + BAR_HEIGHT;
			int slots = history.length;
			float slotWidthF = (float) (WIDTH - 4) / (float) slots;

			for (int i = 0; i < slots; i++) {
				float v = history[i];
				if (Float.isNaN(v) || v <= 0f || max <= 0f) continue;
				float frac = v / max;
				int barH = Math.max(1, Math.round(frac * BAR_HEIGHT));
				int slotX0 = x + 2 + Math.round(i * slotWidthF);
				int slotX1 = x + 2 + Math.round((i + 1) * slotWidthF) - 1;
				if (slotX1 <= slotX0) slotX1 = slotX0 + 1;
				int color = (i == slots - 1) ? BAR_PEAK_COLOR : BAR_COLOR;
				g.fill(slotX0, barBottom - barH, slotX1, barBottom, color);
			}

			y += ROW_HEIGHT + 1;
			if (y + ROW_HEIGHT > g.guiHeight() - PADDING) break;
		}
	}

	private static boolean isDebugScreenOpen(Minecraft mc) {
		return mc.getDebugOverlay() != null && mc.getDebugOverlay().showDebugScreen();
	}

	private static String formatValue(float v) {
		if (Float.isNaN(v)) return "-";
		float abs = Math.abs(v);
		if (abs >= 1000f) return String.format("%.0f", v);
		if (abs >= 100f)  return String.format("%.0f", v);
		if (abs >= 10f)   return String.format("%.1f", v);
		return String.format("%.2f", v);
	}
}
*///?}
