package me.cortex.voxy.server.network;

import me.cortex.voxy.server.VoxyServerMod;
import me.cortex.voxy.server.config.VoxyServerConfig;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//? if HAS_PERMISSIONS {
import net.minecraft.server.permissions.Permissions;
//?}
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * Wires the server side of the config-edit network protocol.
 *
 * - On client connect: send a {@link ConfigSnapshotPayload} so the
 *   client UI can display current values without a request.
 * - On {@link ConfigEditPayload}: validate authorization (op4 /
 *   {@code COMMANDS_ADMIN}), validate the field's type+range, apply,
 *   persist via {@link VoxyServerConfig#save()}, optionally trigger
 *   side effects (tree rebuilds, config-driven services), then
 *   broadcast a fresh snapshot to every connected client.
 *
 * Field validation lives in a small dispatch table inside this class
 * rather than smearing it across the rest of the codebase. Keep it
 * narrow: every accepted key here corresponds to one
 * {@link VoxyServerConfig} field.
 */
public class ConfigSyncHandler {

	private final VoxyServerConfig config;
	/** Optional callback fired after a successful edit, e.g. to rebuild trees on lodRadius change. */
	private final Consumer<String> onSuccessfulEdit;

	public ConfigSyncHandler(VoxyServerConfig config, Consumer<String> onSuccessfulEdit) {
		this.config = config;
		this.onSuccessfulEdit = onSuccessfulEdit;
	}

	public void register() {
		// Push the snapshot to each newly-joined client.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			ServerPlayNetworking.send(player, buildSnapshot(player));
		});

		//? if HAS_NEW_NETWORKING {
		ServerPlayNetworking.registerGlobalReceiver(ConfigEditPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			MinecraftServer server = context.server();
			server.execute(() -> handleEdit(server, player, payload));
		});
		//?} else {
		/*ServerPlayNetworking.registerGlobalReceiver(ConfigEditPayload.TYPE, (packet, player, sender) -> {
			MinecraftServer server = player.getServer();
			if (server == null) return;
			server.execute(() -> handleEdit(server, player, packet));
		});
		*///?}
	}

	private void handleEdit(MinecraftServer server, ServerPlayer player, ConfigEditPayload payload) {
		if (!isAuthorized(player)) {
			ServerPlayNetworking.send(player, new ConfigEditResultPayload(
				payload.key(), false, "Not authorized (need op level 4)"));
			return;
		}

		String result = applyEdit(payload.key(), payload.value());
		if (result != null) {
			ServerPlayNetworking.send(player, new ConfigEditResultPayload(
				payload.key(), false, result));
			return;
		}

		config.save();
		VoxyServerMod.LOGGER.info("[Config] {} set {}={} via GUI",
			player.getName().getString(), payload.key(), payload.value());
		// Apply syncservice debug bit when changed.
		if ("debugLogging".equals(payload.key())) {
			VoxyServerMod.setDebugEnabled(config.debugLogging);
		}
		// Side effects (e.g. rebuild trees on lodRadius change).
		if (onSuccessfulEdit != null) onSuccessfulEdit.accept(payload.key());

		ServerPlayNetworking.send(player, new ConfigEditResultPayload(
			payload.key(), true, ""));
		// Broadcast new snapshot to every connected client so all GUIs update.
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(p, buildSnapshot(p));
		}
	}

	private boolean isAuthorized(ServerPlayer player) {
		// Op level 4 = full server admin. Same threshold as the /voxysv
		// command requirements.
		//? if HAS_PERMISSIONS {
		return player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN);
		//?} else {
		/*return player.createCommandSourceStack().hasPermission(4);
		*///?}
	}

	/** Returns null on success, or a human-readable error message on failure. */
	private String applyEdit(String key, String raw) {
		try {
			switch (key) {
				case "lodStreamRadius" -> config.lodStreamRadius = parseIntInRange(raw, 8, 1024);
				case "maxSectionsPerTickPerPlayer" -> config.maxSectionsPerTickPerPlayer = parseIntInRange(raw, 1, 100000);
				case "sectionsPerPacket" -> config.sectionsPerPacket = parseIntInRange(raw, 1, 1000);
				case "generateOnChunkLoad" -> config.generateOnChunkLoad = parseBoolean(raw);
				case "workerThreads" -> config.workerThreads = parseIntInRange(raw, 1, 64);
				case "maxInFlightChunks" -> config.maxInFlightChunks = parseIntInRange(raw, 1, 512);
				case "dirtyScanInterval" -> config.dirtyScanInterval = parseIntInRange(raw, 1, 1200);
				case "maxDirtyChunksPerScan" -> config.maxDirtyChunksPerScan = parseIntInRange(raw, 1, 10000);
				case "debugLogging" -> config.debugLogging = parseBoolean(raw);
				case "targetTps" -> config.targetTps = parseIntInRange(raw, 1, 20);
				case "enableEntitySync" -> config.enableEntitySync = parseBoolean(raw);
				case "entitySyncIntervalTicks" -> config.entitySyncIntervalTicks = parseIntInRange(raw, 1, 200);
				case "maxLODEntitiesPerPlayer" -> config.maxLODEntitiesPerPlayer = parseIntInRange(raw, 1, 10000);
				case "entitySyncMode" -> {
					switch (raw) {
						case "living", "non_trivial", "all", "players_only" -> config.entitySyncMode = raw;
						default -> { return "entitySyncMode must be one of: living, non_trivial, all, players_only"; }
					}
				}
				case "compatSableAutoTrackingRange" -> config.compatSableAutoTrackingRange = parseBoolean(raw);
				case "l0HashCacheCapBytes" -> config.l0HashCacheCapBytes = parseLongInRange(raw,
					1L * 1024 * 1024, 64L * 1024 * 1024 * 1024);
				case "merkleHeartbeatTicks" -> config.merkleHeartbeatTicks = parseIntInRange(raw, 0, 12000);
				case "merkleSlideTeleportThreshold" -> config.merkleSlideTeleportThreshold = parseIntInRange(raw, 1, 1024);
				default -> { return "Unknown config key: " + key; }
			}
		} catch (NumberFormatException e) {
			return "Could not parse '" + raw + "': " + e.getMessage();
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
		return null;
	}

	private static int parseIntInRange(String s, int min, int max) {
		int v = Integer.parseInt(s);
		if (v < min || v > max) {
			throw new IllegalArgumentException("Value " + v + " out of range [" + min + "," + max + "]");
		}
		return v;
	}

	private static long parseLongInRange(String s, long min, long max) {
		long v = Long.parseLong(s);
		if (v < min || v > max) {
			throw new IllegalArgumentException("Value " + v + " out of range [" + min + "," + max + "]");
		}
		return v;
	}

	private static boolean parseBoolean(String s) {
		String l = s.toLowerCase();
		if (l.equals("true") || l.equals("1") || l.equals("yes")) return true;
		if (l.equals("false") || l.equals("0") || l.equals("no")) return false;
		throw new IllegalArgumentException("Not a boolean: " + s);
	}

	public ConfigSnapshotPayload buildSnapshot(ServerPlayer player) {
		boolean authorized = isAuthorized(player);
		return new ConfigSnapshotPayload(
			authorized,
			config.lodStreamRadius,
			config.maxSectionsPerTickPerPlayer,
			config.sectionsPerPacket,
			config.generateOnChunkLoad,
			config.workerThreads,
			config.dirtyScanInterval,
			config.maxDirtyChunksPerScan,
			config.debugLogging,
			config.targetTps,
			config.enableEntitySync,
			config.entitySyncIntervalTicks,
			config.maxLODEntitiesPerPlayer,
			config.entitySyncMode,
			config.compatSableAutoTrackingRange,
			config.l0HashCacheCapBytes,
			config.merkleHeartbeatTicks,
			config.merkleSlideTeleportThreshold,
			config.maxInFlightChunks);
	}
}
