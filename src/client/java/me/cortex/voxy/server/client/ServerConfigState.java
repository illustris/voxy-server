package me.cortex.voxy.server.client;

import me.cortex.voxy.server.network.ConfigSnapshotPayload;

import java.util.function.Consumer;

/**
 * Client-side mirror of the most recent {@link ConfigSnapshotPayload} the
 * server pushed. Read by {@code VoxyServerConfigScreen} (ModMenu config
 * screen); written by {@code ClientSyncHandler} when a snapshot lands.
 *
 * Listeners can subscribe to be notified when a fresh snapshot replaces the
 * stored one (e.g. so an open config screen refreshes its widgets after
 * another admin edits a value).
 *
 * Thread-safety: stored snapshot is volatile; listener list is read-mostly
 * and modifications are rare (open/close screen) so we serialize on
 * the listener list itself.
 */
public class ServerConfigState {
	private static volatile ConfigSnapshotPayload latest;
	private static final java.util.List<Consumer<ConfigSnapshotPayload>> listeners =
		new java.util.concurrent.CopyOnWriteArrayList<>();

	public static void update(ConfigSnapshotPayload snap) {
		latest = snap;
		for (Consumer<ConfigSnapshotPayload> l : listeners) {
			try {
				l.accept(snap);
			} catch (Exception ignored) {
			}
		}
	}

	public static ConfigSnapshotPayload get() {
		return latest;
	}

	public static boolean isAuthorized() {
		ConfigSnapshotPayload s = latest;
		return s != null && s.authorized();
	}

	public static void addListener(Consumer<ConfigSnapshotPayload> l) {
		listeners.add(l);
	}

	public static void removeListener(Consumer<ConfigSnapshotPayload> l) {
		listeners.remove(l);
	}

	public static void clear() {
		latest = null;
	}
}
