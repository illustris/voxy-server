package me.cortex.voxy.server.client;

import me.cortex.voxy.server.network.ConfigEditResultPayload;

import java.util.function.Consumer;

/**
 * Pub/sub for {@link ConfigEditResultPayload} so the open
 * {@code VoxyServerConfigScreen} can show success/failure feedback inline
 * without scraping chat or polling.
 */
public class ServerConfigEditFeedback {
	private static final java.util.List<Consumer<ConfigEditResultPayload>> listeners =
		new java.util.concurrent.CopyOnWriteArrayList<>();

	public static void publish(ConfigEditResultPayload result) {
		for (Consumer<ConfigEditResultPayload> l : listeners) {
			try { l.accept(result); } catch (Exception ignored) {}
		}
	}

	public static void addListener(Consumer<ConfigEditResultPayload> l) { listeners.add(l); }
	public static void removeListener(Consumer<ConfigEditResultPayload> l) { listeners.remove(l); }
}
