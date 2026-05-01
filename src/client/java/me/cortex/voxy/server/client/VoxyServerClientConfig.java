package me.cortex.voxy.server.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persistent client-side preferences. Loaded once at client init, written back
 * via {@link #save} after every edit (the ModMenu config screen and any other
 * UI / commands).
 */
public class VoxyServerClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "voxy-server-client.json";

	// HUD telemetry overlay master switch. Off by default -- the user enables
	// it deliberately when they want to see graphs.
	public boolean telemetryOverlayEnabled = false;

	// Show overlay only when the F3 debug screen is open (default), versus
	// always-on. False = always render when enabled.
	public boolean telemetryOverlayRequiresF3 = true;

	// Per-metric on/off for the HUD overlay. Keyed by VoxyTelemetryHistory.Metric.key.
	// Missing keys default to "on" via isGraphMetricEnabled.
	public Map<String, Boolean> graphMetrics = new LinkedHashMap<>();

	// Section highlight tracker (the existing /voxyhighlight feature).
	public boolean sectionHighlightsEnabled = false;

	// Far-entity LOD rendering style: "billboard" or "model".
	public String lodEntityRenderStyle = "model";

	// LOD entity occlusion culling. Async raycast against voxy's client-side
	// LOD voxel grid; entities behind LOD-only terrain (a hill that's only a
	// far chunk to the client) are skipped.
	public boolean lodEntityCullingEnabled = true;

	// How long (ms) a per-entity visibility result is cached before being
	// recomputed. Lower = more accurate at view-edge but more CPU per second.
	// Clamped to >= 20ms by the culler so a 0 or negative value doesn't
	// melt the worker thread.
	public int lodEntityCullingRefreshMs = 100;

	// Hard cap on raycast length (in blocks). Beyond this distance we don't
	// trace -- the entity is at the LOD edge anyway, optimistically render.
	// Clamped to >= 64 by the culler.
	public int lodEntityCullingMaxRayBlocks = 8192;

	// Debug logging on the client (mirrors server's debugLogging for client-side spam).
	public boolean debugLogging = false;

	public boolean isGraphMetricEnabled(String key) {
		Boolean v = graphMetrics.get(key);
		return v == null || v;
	}

	public void setGraphMetricEnabled(String key, boolean enabled) {
		graphMetrics.put(key, enabled);
	}

	public static VoxyServerClientConfig load() {
		Path p = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (Files.exists(p)) {
			try {
				String json = Files.readString(p);
				VoxyServerClientConfig c = GSON.fromJson(json, VoxyServerClientConfig.class);
				if (c != null) {
					if (c.graphMetrics == null) c.graphMetrics = new LinkedHashMap<>();
					c.save();
					return c;
				}
			} catch (Exception ignored) {}
		}
		VoxyServerClientConfig c = new VoxyServerClientConfig();
		c.save();
		return c;
	}

	public void save() {
		Path p = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(this));
		} catch (IOException ignored) {}
	}

	// Singleton-ish accessor for code that wants the live config (e.g. HUD render
	// callback, ModMenu screen). Lazily initialized on first call.
	private static volatile VoxyServerClientConfig instance;
	public static VoxyServerClientConfig get() {
		VoxyServerClientConfig c = instance;
		if (c == null) {
			synchronized (VoxyServerClientConfig.class) {
				c = instance;
				if (c == null) {
					c = load();
					instance = c;
				}
			}
		}
		return c;
	}
}
