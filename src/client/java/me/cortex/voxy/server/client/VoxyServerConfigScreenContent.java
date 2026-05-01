package me.cortex.voxy.server.client;

//? if HAS_DEBUG_SCREEN && !HAS_RENDER_PIPELINES {
/*import me.cortex.voxy.server.network.ConfigEditPayload;
import me.cortex.voxy.server.network.ConfigEditResultPayload;
import me.cortex.voxy.server.network.ConfigSnapshotPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

// 1.21.11-only ModMenu editor body. Owner screen forwards init/render/onClose
// /mouseScrolled to this helper. On 1.21.1 / 26.1.2 the file compiles to an
// empty stub class via stonecutter; the entry-point screen there falls back
// to a minimal Done button.
public class VoxyServerConfigScreenContent {

	// Layout constants (internal GUI coords).
	private static final int TABS_Y = 28;
	private static final int TAB_W = 90;
	private static final int TAB_H = 20;
	private static final int CONTENT_TOP = 56;
	private static final int FOOTER_RESERVE = 35;
	private static final int ROW_H = 22;
	private static final int LABEL_W = 130;
	private static final int WIDGET_W = 120;
	private static final int SET_W = 40;
	private static final int WIDGET_H = 18;
	private static final int GAP = 4;

	// Server-config validation ranges -- mirrored from ConfigSyncHandler so
	// the client can offer cheap feedback before the round-trip.
	private static final long MB = 1024L * 1024L;
	private static final long GB = MB * 1024L;

	private enum Tab { SERVER, CLIENT, GRAPHS }
	private Tab activeTab = Tab.SERVER;
	private Button tabServer;
	private Button tabClient;
	private Button tabGraphs;
	private Button doneButton;

	// One row in a tab. `key` is non-null only for server-config rows so
	// ConfigEditResultPayload feedback can be matched back to the row.
	private static class Row {
		final String key;
		final String label;
		final List<AbstractWidget> widgets = new ArrayList<>();
		String statusMsg = "";
		boolean statusOk = true;
		Row(String key, String label) {
			this.key = key;
			this.label = label;
		}
	}

	private final List<Row> serverRows = new ArrayList<>();
	private final List<Row> clientRows = new ArrayList<>();
	private final List<Row> graphRows = new ArrayList<>();
	private final Map<String, Row> serverRowsByKey = new HashMap<>();

	// Refreshers run after each ConfigSnapshotPayload arrives, to push the
	// new server-side values into widgets the user isn't actively editing.
	private final List<Consumer<ConfigSnapshotPayload>> snapshotRefreshers = new ArrayList<>();

	private final Consumer<ConfigSnapshotPayload> snapshotListener = this::onSnapshotUpdated;
	private final Consumer<ConfigEditResultPayload> resultListener = this::onEditResult;

	private final Screen owner;
	private final Runnable closer;

	private boolean editsEnabled;
	private int scrollY = 0;

	public VoxyServerConfigScreenContent(Screen owner, Runnable closer) {
		this.owner = owner;
		this.closer = closer;
	}

	public void init() {
		int cx = owner.width / 2;

		tabServer = Button.builder(Component.literal("Server"), b -> setTab(Tab.SERVER))
			.bounds(cx - (TAB_W * 3 / 2) - GAP, TABS_Y, TAB_W, TAB_H).build();
		tabClient = Button.builder(Component.literal("Client"), b -> setTab(Tab.CLIENT))
			.bounds(cx - TAB_W / 2, TABS_Y, TAB_W, TAB_H).build();
		tabGraphs = Button.builder(Component.literal("Graphs"), b -> setTab(Tab.GRAPHS))
			.bounds(cx + (TAB_W / 2) + GAP, TABS_Y, TAB_W, TAB_H).build();
		registerWidget(tabServer);
		registerWidget(tabClient);
		registerWidget(tabGraphs);

		ConfigSnapshotPayload snap = ServerConfigState.get();
		this.editsEnabled = snap != null && snap.authorized();

		buildServerSection(snap);
		buildClientSection();
		buildGraphSection();

		doneButton = Button.builder(Component.literal("Done"), b -> closer.run())
			.bounds(cx - 75, owner.height - 30, 150, 20).build();
		registerWidget(doneButton);

		ServerConfigState.addListener(snapshotListener);
		ServerConfigEditFeedback.addListener(resultListener);

		applyTabAndLayout();
	}

	private void registerWidget(AbstractWidget w) {
		// Same package as Screen, so protected addRenderableWidget is reachable
		// from here in Java; routed through a public forwarder on
		// VoxyServerConfigScreen so we don't depend on JLS protected nuances.
		((VoxyServerConfigScreen) owner).addContentWidget(w);
	}

	private void buildServerSection(ConfigSnapshotPayload snap) {
		addServerInt("lodStreamRadius",                "LOD radius",          8, 1024,
			snap == null ? 256 : snap.lodStreamRadius());
		addServerInt("maxSectionsPerTickPerPlayer",    "Max sections/tick",   1, 100000,
			snap == null ? 100 : snap.maxSectionsPerTickPerPlayer());
		addServerInt("sectionsPerPacket",              "Sections/packet",     1, 1000,
			snap == null ? 50 : snap.sectionsPerPacket());
		addServerBool("generateOnChunkLoad",           "Gen on chunk load",
			snap != null && snap.generateOnChunkLoad());
		addServerInt("workerThreads",                  "Worker threads",      1, 64,
			snap == null ? 8 : snap.workerThreads());
		addServerInt("maxInFlightChunks",              "Max in-flight",       1, 512,
			snap == null ? 16 : snap.maxInFlightChunks());
		addServerInt("dirtyScanInterval",              "Dirty scan ticks",    1, 1200,
			snap == null ? 10 : snap.dirtyScanInterval());
		addServerInt("maxDirtyChunksPerScan",          "Max dirty/scan",      1, 10000,
			snap == null ? 64 : snap.maxDirtyChunksPerScan());
		addServerBool("debugLogging",                  "Debug logging",
			snap != null && snap.debugLogging());
		addServerInt("targetTps",                      "Target TPS",          1, 20,
			snap == null ? 15 : snap.targetTps());
		addServerBool("enableEntitySync",              "Entity sync",
			snap != null && snap.enableEntitySync());
		addServerInt("entitySyncIntervalTicks",        "Entity sync ticks",   1, 200,
			snap == null ? 10 : snap.entitySyncIntervalTicks());
		addServerInt("maxLODEntitiesPerPlayer",        "Max LOD entities",    1, 10000,
			snap == null ? 200 : snap.maxLODEntitiesPerPlayer());
		addServerEnum("entitySyncMode",                "Entity mode",
			new String[] { "living", "non_trivial", "all", "players_only" },
			snap == null ? "non_trivial" : snap.entitySyncMode());
		addServerBool("compatSableAutoTrackingRange",  "Sable compat",
			snap == null || snap.compatSableAutoTrackingRange());
		addServerCacheBytes("l0HashCacheCapBytes",     "L0 cache (MB)",       MB, 64L * GB,
			snap == null ? 200L * MB : snap.l0HashCacheCapBytes());
		addServerInt("merkleHeartbeatTicks",           "Heartbeat ticks",     0, 12000,
			snap == null ? 100 : snap.merkleHeartbeatTicks());
		addServerInt("merkleSlideTeleportThreshold",   "Slide TP threshold",  1, 1024,
			snap == null ? 64 : snap.merkleSlideTeleportThreshold());
	}

	private void buildClientSection() {
		VoxyServerClientConfig cfg = VoxyServerClientConfig.get();
		addClientBool("Telemetry overlay",     () -> cfg.telemetryOverlayEnabled,     v -> cfg.telemetryOverlayEnabled = v);
		addClientBool("Overlay needs F3",      () -> cfg.telemetryOverlayRequiresF3,  v -> cfg.telemetryOverlayRequiresF3 = v);
		addClientBool("Section highlights",    () -> cfg.sectionHighlightsEnabled,    v -> cfg.sectionHighlightsEnabled = v);
		addClientEnum("Entity render style",
			new String[] { "billboard", "model" },
			() -> cfg.lodEntityRenderStyle,
			v -> cfg.lodEntityRenderStyle = v);
		addClientBool("LOD entity culling",    () -> cfg.lodEntityCullingEnabled,    v -> cfg.lodEntityCullingEnabled = v);
		addClientInt("Cull refresh (ms)",      20, 5000,
			() -> cfg.lodEntityCullingRefreshMs, v -> cfg.lodEntityCullingRefreshMs = v);
		addClientInt("Cull max ray (blk)",     64, 65536,
			() -> cfg.lodEntityCullingMaxRayBlocks, v -> cfg.lodEntityCullingMaxRayBlocks = v);
		addClientBool("Client debug log",      () -> cfg.debugLogging,                v -> cfg.debugLogging = v);
	}

	private void buildGraphSection() {
		VoxyServerClientConfig cfg = VoxyServerClientConfig.get();
		for (VoxyTelemetryHistory.Metric m : VoxyTelemetryHistory.Metric.values()) {
			Row row = new Row(null, m.label);
			Button btn = Button.builder(boolText(cfg.isGraphMetricEnabled(m.key)), b -> {
				boolean cur = cfg.isGraphMetricEnabled(m.key);
				boolean next = !cur;
				cfg.setGraphMetricEnabled(m.key, next);
				b.setMessage(boolText(next));
			})
				.bounds(rowWidgetX(), 0, WIDGET_W, WIDGET_H)
				.build();
			row.widgets.add(btn);
			registerWidget(btn);
			graphRows.add(row);
		}
	}

	// ---------- Row builders ----------

	private void addServerInt(String key, String label, int min, int max, int initial) {
		Row row = new Row(key, label);
		EditBox box = new EditBox(Minecraft.getInstance().font,
			rowWidgetX(), 0, WIDGET_W, WIDGET_H, Component.literal(label));
		box.setMaxLength(16);
		box.setValue(Integer.toString(initial));
		box.setEditable(editsEnabled);
		row.widgets.add(box);

		Button set = Button.builder(Component.literal("Set"), b -> {
			String raw = box.getValue();
			try {
				int v = Integer.parseInt(raw.trim());
				if (v < min || v > max) {
					row.statusOk = false;
					row.statusMsg = "out of range " + min + ".." + max;
					return;
				}
			} catch (NumberFormatException e) {
				row.statusOk = false;
				row.statusMsg = "not a number";
				return;
			}
			row.statusOk = true;
			row.statusMsg = "sending...";
			ClientPlayNetworking.send(new ConfigEditPayload(key, raw.trim()));
		})
			.bounds(rowSetX(), 0, SET_W, WIDGET_H).build();
		set.active = editsEnabled;
		row.widgets.add(set);

		registerWidget(box);
		registerWidget(set);
		serverRows.add(row);
		serverRowsByKey.put(key, row);
		snapshotRefreshers.add(s -> {
			if (s == null) return;
			int v = readServerInt(s, key);
			if (!box.isFocused()) box.setValue(Integer.toString(v));
		});
	}

	private void addServerBool(String key, String label, boolean initial) {
		Row row = new Row(key, label);
		boolean[] state = new boolean[] { initial };
		Button btn = Button.builder(boolText(initial), b -> {
			state[0] = !state[0];
			b.setMessage(boolText(state[0]));
			row.statusOk = true;
			row.statusMsg = "sending...";
			ClientPlayNetworking.send(new ConfigEditPayload(key, Boolean.toString(state[0])));
		}).bounds(rowWidgetX(), 0, WIDGET_W, WIDGET_H).build();
		btn.active = editsEnabled;
		row.widgets.add(btn);
		registerWidget(btn);
		serverRows.add(row);
		serverRowsByKey.put(key, row);
		snapshotRefreshers.add(s -> {
			if (s == null) return;
			boolean v = readServerBool(s, key);
			state[0] = v;
			btn.setMessage(boolText(v));
		});
	}

	private void addServerEnum(String key, String label, String[] values, String initial) {
		Row row = new Row(key, label);
		int[] idx = new int[] { Math.max(0, indexOf(values, initial)) };
		Button btn = Button.builder(Component.literal(values[idx[0]]), b -> {
			idx[0] = (idx[0] + 1) % values.length;
			b.setMessage(Component.literal(values[idx[0]]));
			row.statusOk = true;
			row.statusMsg = "sending...";
			ClientPlayNetworking.send(new ConfigEditPayload(key, values[idx[0]]));
		}).bounds(rowWidgetX(), 0, WIDGET_W, WIDGET_H).build();
		btn.active = editsEnabled;
		row.widgets.add(btn);
		registerWidget(btn);
		serverRows.add(row);
		serverRowsByKey.put(key, row);
		snapshotRefreshers.add(s -> {
			if (s == null) return;
			String v = readServerString(s, key);
			int i = indexOf(values, v);
			if (i >= 0) {
				idx[0] = i;
				btn.setMessage(Component.literal(values[i]));
			}
		});
	}

	// l0HashCacheCapBytes is presented in MB to keep the textbox short.
	private void addServerCacheBytes(String key, String label, long minBytes, long maxBytes, long initialBytes) {
		Row row = new Row(key, label);
		EditBox box = new EditBox(Minecraft.getInstance().font,
			rowWidgetX(), 0, WIDGET_W, WIDGET_H, Component.literal(label));
		box.setMaxLength(16);
		box.setValue(Long.toString(Math.max(1L, initialBytes / MB)));
		box.setEditable(editsEnabled);
		row.widgets.add(box);

		Button set = Button.builder(Component.literal("Set"), b -> {
			String raw = box.getValue();
			long mb;
			try {
				mb = Long.parseLong(raw.trim());
			} catch (NumberFormatException e) {
				row.statusOk = false;
				row.statusMsg = "not a number (MB)";
				return;
			}
			long bytes = mb * MB;
			if (bytes < minBytes || bytes > maxBytes) {
				row.statusOk = false;
				row.statusMsg = "out of range " + (minBytes / MB) + ".." + (maxBytes / MB) + " MB";
				return;
			}
			row.statusOk = true;
			row.statusMsg = "sending...";
			ClientPlayNetworking.send(new ConfigEditPayload(key, Long.toString(bytes)));
		}).bounds(rowSetX(), 0, SET_W, WIDGET_H).build();
		set.active = editsEnabled;
		row.widgets.add(set);

		registerWidget(box);
		registerWidget(set);
		serverRows.add(row);
		serverRowsByKey.put(key, row);
		snapshotRefreshers.add(s -> {
			if (s == null) return;
			long bytes = s.l0HashCacheCapBytes();
			if (!box.isFocused()) box.setValue(Long.toString(Math.max(1L, bytes / MB)));
		});
	}

	private void addClientBool(String label, BooleanSupplier getter, Consumer<Boolean> setter) {
		Row row = new Row(null, label);
		boolean[] state = new boolean[] { getter.getAsBoolean() };
		Button btn = Button.builder(boolText(state[0]), b -> {
			state[0] = !state[0];
			b.setMessage(boolText(state[0]));
			setter.accept(state[0]);
		}).bounds(rowWidgetX(), 0, WIDGET_W, WIDGET_H).build();
		row.widgets.add(btn);
		registerWidget(btn);
		clientRows.add(row);
	}

	// Client-side int knob with an EditBox + Set button. Same shape as the
	// server-side variant but no wire round-trip; setter writes straight to
	// VoxyServerClientConfig and the autosave-on-close picks it up.
	private void addClientInt(String label, int min, int max,
							   IntSupplier getter, IntConsumer setter) {
		Row row = new Row(null, label);
		EditBox box = new EditBox(Minecraft.getInstance().font,
			rowWidgetX(), 0, WIDGET_W, WIDGET_H, Component.literal(label));
		box.setMaxLength(16);
		box.setValue(Integer.toString(getter.getAsInt()));
		row.widgets.add(box);

		Button set = Button.builder(Component.literal("Set"), b -> {
			String raw = box.getValue();
			try {
				int v = Integer.parseInt(raw.trim());
				if (v < min || v > max) {
					row.statusOk = false;
					row.statusMsg = "out of range " + min + ".." + max;
					return;
				}
				setter.accept(v);
				row.statusOk = true;
				row.statusMsg = "saved";
			} catch (NumberFormatException e) {
				row.statusOk = false;
				row.statusMsg = "not a number";
			}
		}).bounds(rowSetX(), 0, SET_W, WIDGET_H).build();
		row.widgets.add(set);

		registerWidget(box);
		registerWidget(set);
		clientRows.add(row);
	}

	private void addClientEnum(String label, String[] values,
								Supplier<String> getter,
								Consumer<String> setter) {
		Row row = new Row(null, label);
		int[] idx = new int[] { Math.max(0, indexOf(values, getter.get())) };
		Button btn = Button.builder(Component.literal(values[idx[0]]), b -> {
			idx[0] = (idx[0] + 1) % values.length;
			b.setMessage(Component.literal(values[idx[0]]));
			setter.accept(values[idx[0]]);
		}).bounds(rowWidgetX(), 0, WIDGET_W, WIDGET_H).build();
		row.widgets.add(btn);
		registerWidget(btn);
		clientRows.add(row);
	}

	// ---------- Lifecycle ----------

	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		applyTabAndLayout();

		var font = Minecraft.getInstance().font;
		int cx = owner.width / 2;

		// Top header.
		g.drawCenteredString(font, owner.getTitle(), cx, 8, 0xFFFFFFFF);

		// Authorization banner under tabs (server tab only).
		if (activeTab == Tab.SERVER) {
			ConfigSnapshotPayload s = ServerConfigState.get();
			if (s == null) {
				g.drawCenteredString(font, "Waiting for server config...", cx, CONTENT_TOP - 12, 0xFFAAAAAA);
			} else if (!s.authorized()) {
				g.drawCenteredString(font, "Read-only -- requires op level 4", cx, CONTENT_TOP - 12, 0xFFCC8844);
			}
		}

		// Active tab indicator: highlight active button text.
		highlightActive(tabServer, activeTab == Tab.SERVER);
		highlightActive(tabClient, activeTab == Tab.CLIENT);
		highlightActive(tabGraphs, activeTab == Tab.GRAPHS);

		// Per-row labels and status text. Widgets paint themselves.
		List<Row> rows = activeRows();
		int top = CONTENT_TOP;
		int bottom = owner.height - FOOTER_RESERVE;
		int labelX = cx - (LABEL_W + GAP + WIDGET_W / 2);
		int statusX = cx + (WIDGET_W / 2 + GAP + SET_W + GAP);

		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			int y = top + i * ROW_H - scrollY;
			if (y + ROW_H < top || y > bottom) continue;
			g.drawString(font, r.label, labelX, y + 5, 0xFFFFFFFF, false);
			if (!r.statusMsg.isEmpty()) {
				int color = r.statusOk ? 0xFF77DD77 : 0xFFDD7777;
				g.drawString(font, r.statusMsg, statusX, y + 5, color, false);
			}
		}
	}

	private void highlightActive(Button btn, boolean active) {
		// Cheap visual cue: button is greyed-out when not the active tab.
		btn.active = !active;
	}

	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (mouseY < CONTENT_TOP || mouseY > owner.height - FOOTER_RESERVE) return false;
		int delta = -(int) Math.round(dy * ROW_H);
		int total = activeRows().size() * ROW_H;
		int visible = (owner.height - FOOTER_RESERVE) - CONTENT_TOP;
		int max = Math.max(0, total - visible);
		scrollY = Math.max(0, Math.min(max, scrollY + delta));
		return true;
	}

	public void onClose() {
		ServerConfigState.removeListener(snapshotListener);
		ServerConfigEditFeedback.removeListener(resultListener);
		VoxyServerClientConfig.get().save();
	}

	// ---------- Helpers ----------

	private void applyTabAndLayout() {
		layoutTab(serverRows, activeTab == Tab.SERVER);
		layoutTab(clientRows, activeTab == Tab.CLIENT);
		layoutTab(graphRows, activeTab == Tab.GRAPHS);
	}

	private void layoutTab(List<Row> rows, boolean active) {
		int top = CONTENT_TOP;
		int bottom = owner.height - FOOTER_RESERVE;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			int y = top + i * ROW_H - scrollY;
			boolean inside = active && y >= top - 2 && y + WIDGET_H <= bottom + 2;
			for (AbstractWidget w : r.widgets) {
				w.setY(y + 2);
				w.visible = inside;
			}
		}
	}

	private List<Row> activeRows() {
		switch (activeTab) {
			case CLIENT: return clientRows;
			case GRAPHS: return graphRows;
			case SERVER:
			default:     return serverRows;
		}
	}

	private void setTab(Tab t) {
		this.activeTab = t;
		this.scrollY = 0;
		applyTabAndLayout();
	}

	private void onSnapshotUpdated(ConfigSnapshotPayload s) {
		Minecraft.getInstance().execute(() -> {
			editsEnabled = s != null && s.authorized();
			for (Row r : serverRows) {
				for (AbstractWidget w : r.widgets) {
					if (w instanceof EditBox eb) {
						eb.setEditable(editsEnabled);
					} else {
						w.active = editsEnabled;
					}
				}
			}
			for (Consumer<ConfigSnapshotPayload> ref : snapshotRefreshers) {
				try { ref.accept(s); } catch (Exception ignored) {}
			}
		});
	}

	private void onEditResult(ConfigEditResultPayload r) {
		Minecraft.getInstance().execute(() -> {
			Row row = serverRowsByKey.get(r.key());
			if (row == null) return;
			row.statusOk = r.success();
			row.statusMsg = r.success() ? "saved" : (r.message().isEmpty() ? "error" : r.message());
		});
	}

	private int rowWidgetX() {
		return (owner.width / 2) - (WIDGET_W / 2);
	}

	private int rowSetX() {
		return rowWidgetX() + WIDGET_W + GAP;
	}

	private static Component boolText(boolean v) {
		return Component.literal(v ? "ON" : "OFF");
	}

	private static int indexOf(String[] arr, String v) {
		if (v == null) return -1;
		for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
		return -1;
	}

	private static int readServerInt(ConfigSnapshotPayload s, String key) {
		switch (key) {
			case "lodStreamRadius":             return s.lodStreamRadius();
			case "maxSectionsPerTickPerPlayer": return s.maxSectionsPerTickPerPlayer();
			case "sectionsPerPacket":           return s.sectionsPerPacket();
			case "workerThreads":               return s.workerThreads();
			case "dirtyScanInterval":           return s.dirtyScanInterval();
			case "maxDirtyChunksPerScan":       return s.maxDirtyChunksPerScan();
			case "targetTps":                   return s.targetTps();
			case "entitySyncIntervalTicks":     return s.entitySyncIntervalTicks();
			case "maxLODEntitiesPerPlayer":     return s.maxLODEntitiesPerPlayer();
			case "merkleHeartbeatTicks":        return s.merkleHeartbeatTicks();
			case "merkleSlideTeleportThreshold":return s.merkleSlideTeleportThreshold();
			case "maxInFlightChunks":           return s.maxInFlightChunks();
			default: return 0;
		}
	}

	private static boolean readServerBool(ConfigSnapshotPayload s, String key) {
		switch (key) {
			case "generateOnChunkLoad":          return s.generateOnChunkLoad();
			case "debugLogging":                 return s.debugLogging();
			case "enableEntitySync":             return s.enableEntitySync();
			case "compatSableAutoTrackingRange": return s.compatSableAutoTrackingRange();
			default: return false;
		}
	}

	private static String readServerString(ConfigSnapshotPayload s, String key) {
		switch (key) {
			case "entitySyncMode": return s.entitySyncMode();
			default: return "";
		}
	}
}
*///?} else {
public class VoxyServerConfigScreenContent {}
//?}
