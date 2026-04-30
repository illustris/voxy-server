package me.cortex.voxy.server;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import me.cortex.voxy.server.compat.SableIntegration;
import me.cortex.voxy.server.config.VoxyServerConfig;
import me.cortex.voxy.server.engine.ChunkVoxelizer;
import me.cortex.voxy.server.engine.DirtyScanService;
import me.cortex.voxy.server.engine.ServerLodEngine;
import me.cortex.voxy.server.network.ConfigSyncHandler;
import me.cortex.voxy.server.network.VoxyServerNetworking;
import me.cortex.voxy.server.streaming.EntitySyncService;
import me.cortex.voxy.server.streaming.SyncService;
import me.cortex.voxy.server.util.DebouncedLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//? if HAS_RENDER_PIPELINES {
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
//?} else {
/*import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
*///?}
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
//? if HAS_PERMISSIONS {
import net.minecraft.server.permissions.Permissions;
//?}
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoxyServerMod implements ModInitializer {
	public static final String MOD_ID = "voxy-server";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static VoxyServerConfig config;
	private ServerLodEngine lodEngine;
	private ChunkVoxelizer chunkVoxelizer;
	private SyncService syncService;
	private DirtyScanService dirtyScanService;
	private EntitySyncService entitySyncService;

	private static volatile boolean debugEnabled = false;
	private static volatile EntitySyncService entitySyncServiceInstance;
	private static final DebouncedLogger debugLogger = new DebouncedLogger(LOGGER);

	public static VoxyServerConfig getConfig() {
		return config;
	}

	public static EntitySyncService getEntitySyncService() {
		return entitySyncServiceInstance;
	}

	public static boolean isDebug() {
		return debugEnabled;
	}

	public static void setDebugEnabled(boolean enabled) {
		debugEnabled = enabled;
	}

	public static void debug(String msg, Object... args) {
		if (debugEnabled) {
			debugLogger.log(msg, args);
		}
	}

	/** Flush any pending debounced debug messages. Called once per tick. */
	public static void flushDebugLogs() {
		if (debugEnabled) {
			debugLogger.flush();
		}
	}

	@Override
	public void onInitialize() {
		config = VoxyServerConfig.load();
		debugEnabled = config.debugLogging;
		LOGGER.info("Voxy Server initialized (debug={})", debugEnabled);
		VoxyServerNetworking.register();
		registerCommands();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!server.isDedicatedServer()) {
				LOGGER.info("Voxy Server disabled in singleplayer.");
				return;
			}

			var worldPath = server.getWorldPath(LevelResource.ROOT);
			lodEngine = new ServerLodEngine(worldPath, config);
			lodEngine.updateDedicatedThreadsCount(config.workerThreads);
			syncService = new SyncService(lodEngine, config);
			syncService.register();
			chunkVoxelizer = new ChunkVoxelizer(lodEngine, syncService, config);
			chunkVoxelizer.register();
			// Catch chunks force-loaded before our CHUNK_LOAD listener was
			// registered (vanilla's spawn protection square). Otherwise these
			// stay un-voxelized indefinitely if no player joins close enough
			// for voxelizeAlreadyLoadedEmptyColumns to cover them.
			chunkVoxelizer.scanAlreadyLoadedSpawnChunks(server);
			// One-shot recovery: chunks left with markers but no L0 data
			// from a prior server run that crashed mid-voxelization. The
			// regular paths skip these (markedFullColumns + L1=0 collision),
			// so they need explicit force-revoxelization.
			chunkVoxelizer.detectAndRecoverDanglingSpawnMarkers(server);
			syncService.setChunkVoxelizer(chunkVoxelizer);
			dirtyScanService = new DirtyScanService(lodEngine, chunkVoxelizer, syncService, config);

			// Network-driven config edits from the client UI. Replays the
			// snapshot to every connected client on every successful change.
			ConfigSyncHandler configHandler = new ConfigSyncHandler(config, key -> {
				if ("lodStreamRadius".equals(key) && syncService != null) {
					syncService.rebuildAllTrees(server);
				}
				if ("compatSableAutoTrackingRange".equals(key) && config.compatSableAutoTrackingRange
						&& SableIntegration.isPresent()) {
					SableIntegration.liftTrackingRange(config.lodStreamRadius * 32.0);
				}
			});
			configHandler.register();
			if (config.enableEntitySync) {
				entitySyncService = new EntitySyncService(config, syncService);
				entitySyncServiceInstance = entitySyncService;
			}

			if (config.compatSableAutoTrackingRange && SableIntegration.isPresent()) {
				SableIntegration.liftTrackingRange(config.lodStreamRadius * 32.0);
			}

			ServerTickEvents.END_SERVER_TICK.register(tick -> {
				if (syncService != null) syncService.tick(tick);
				if (dirtyScanService != null) dirtyScanService.tick(tick);
				if (entitySyncService != null) entitySyncService.tick(tick);
				flushDebugLogs();
			});

			LOGGER.info("Voxy Server engine started for world: {}", worldPath);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (lodEngine != null) {
				LOGGER.info("Shutting down Voxy Server engine");
				entitySyncServiceInstance = null;
				if (entitySyncService != null) entitySyncService.shutdown();
				if (syncService != null) syncService.shutdown();
				if (dirtyScanService != null) dirtyScanService.shutdown();
				lodEngine.shutdown();
				lodEngine = null;
				chunkVoxelizer = null;
				syncService = null;
				dirtyScanService = null;
				entitySyncService = null;
			}
		});

		//? if HAS_RENDER_PIPELINES {
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
			if (syncService != null) {
				syncService.onDimensionChange(player, destination);
			}
			if (entitySyncService != null) {
				entitySyncService.onDimensionChange(player);
			}
		});
		//?} else {
		/*ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
			if (syncService != null) {
				syncService.onDimensionChange(player, destination);
			}
			if (entitySyncService != null) {
				entitySyncService.onDimensionChange(player);
			}
		});
		*///?}

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (entitySyncService != null) {
				entitySyncService.onPlayerDisconnect(handler.getPlayer().getUUID(), server);
			}
		});
	}

	private void registerCommands() {
		// Note: NOT "voxy" -- upstream voxy registers a CLIENT-side /voxy command
		// (see voxy's me.cortex.voxy.client.VoxyCommands), and client-side commands
		// intercept chat input before it reaches the server. Using "voxysv" keeps
		// our admin commands reachable from in-game chat AND from the dedicated
		// server console.
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(Commands.literal("voxysv")
				//? if HAS_PERMISSIONS {
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
				//?} else {
				/*.requires(source -> source.hasPermission(4))
				*///?}

				.then(Commands.literal("entity")
					.then(Commands.literal("interval")
						.then(Commands.argument("ticks", IntegerArgumentType.integer(1, 200))
							.executes(ctx -> {
								int ticks = IntegerArgumentType.getInteger(ctx, "ticks");
								config.entitySyncIntervalTicks = ticks;
								config.save();
								ctx.getSource().sendSuccess(
									() -> Component.literal("[Voxy] Entity sync interval set to " + ticks + " ticks ("
										+ String.format("%.1f", ticks / 20.0) + "s)"),
									true);
								return 1;
							}))
						.executes(ctx -> {
							int ticks = config.entitySyncIntervalTicks;
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] Entity sync interval: " + ticks + " ticks ("
									+ String.format("%.1f", ticks / 20.0) + "s)"),
								false);
							return 1;
						}))
					.then(Commands.literal("max")
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 10000))
							.executes(ctx -> {
								int count = IntegerArgumentType.getInteger(ctx, "count");
								config.maxLODEntitiesPerPlayer = count;
								config.save();
								ctx.getSource().sendSuccess(
									() -> Component.literal("[Voxy] Max LOD entities per player set to " + count),
									true);
								return 1;
							}))
						.executes(ctx -> {
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] Max LOD entities per player: " + config.maxLODEntitiesPerPlayer),
								false);
							return 1;
						}))
					.then(Commands.literal("mode")
						.then(Commands.argument("mode", StringArgumentType.word())
							.suggests((ctx, builder) -> {
								builder.suggest("living");
								builder.suggest("non_trivial");
								builder.suggest("all");
								builder.suggest("players_only");
								return builder.buildFuture();
							})
							.executes(ctx -> {
								String mode = StringArgumentType.getString(ctx, "mode");
								if (!mode.equals("living") && !mode.equals("non_trivial")
										&& !mode.equals("all") && !mode.equals("players_only")) {
									ctx.getSource().sendFailure(Component.literal(
										"[Voxy] Invalid mode '" + mode + "'. Use one of: living, non_trivial, all, players_only"));
									return 0;
								}
								config.entitySyncMode = mode;
								config.save();
								ctx.getSource().sendSuccess(
									() -> Component.literal("[Voxy] Entity sync mode set to " + mode),
									true);
								return 1;
							}))
						.executes(ctx -> {
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] Entity sync mode: " + config.entitySyncMode),
								false);
							return 1;
						}))
					.then(Commands.literal("enabled")
						.then(Commands.argument("enabled", BoolArgumentType.bool())
							.executes(ctx -> {
								boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
								config.enableEntitySync = enabled;
								config.save();
								ctx.getSource().sendSuccess(
									() -> Component.literal("[Voxy] Entity sync " + (enabled ? "enabled" : "disabled")
										+ " (takes effect after server restart -- the EntitySyncService is created at startup)"),
									true);
								return 1;
							}))
						.executes(ctx -> {
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] enableEntitySync: " + config.enableEntitySync
									+ (entitySyncServiceInstance == null ? " (service not running)" : " (service running)")),
								false);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] Entity sync: mode=" + config.entitySyncMode
								+ " interval=" + config.entitySyncIntervalTicks + "t"
								+ " max=" + config.maxLODEntitiesPerPlayer
								+ " enabled=" + config.enableEntitySync),
							false);
						return 1;
					}))
				.then(Commands.literal("debug")
					.executes(ctx -> {
						debugEnabled = !debugEnabled;
						config.debugLogging = debugEnabled;
						config.save();
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] Debug logging " + (debugEnabled ? "enabled" : "disabled")),
							true);
						return 1;
					}))

				.then(Commands.literal("config")
					.executes(ctx -> {
						StringBuilder summary = new StringBuilder("[Voxy] config:")
							.append(" lodRadius=").append(config.lodStreamRadius).append("(").append(config.lodStreamRadius * 32).append("b)")
							.append(" targetTps=").append(config.targetTps)
							.append(" workerThreads=").append(config.workerThreads)
							.append(" generateOnChunkLoad=").append(config.generateOnChunkLoad)
							.append(" maxSectionsPerTickPerPlayer=").append(config.maxSectionsPerTickPerPlayer)
							.append(" sectionsPerPacket=").append(config.sectionsPerPacket)
							.append(" dirtyScanInterval=").append(config.dirtyScanInterval).append("t")
							.append(" maxDirtyChunksPerScan=").append(config.maxDirtyChunksPerScan)
							.append(" debug=").append(config.debugLogging)
							.append(" entitySync=").append(config.enableEntitySync
								? (config.entitySyncMode
									+ " interval=" + config.entitySyncIntervalTicks + "t max=" + config.maxLODEntitiesPerPlayer)
								: "disabled")
							.append(" l0CacheCap=").append(config.l0HashCacheCapBytes >> 20).append("MB")
							.append(" merkleHeartbeat=").append(config.merkleHeartbeatTicks).append("t")
							.append(" slideTeleportThreshold=").append(config.merkleSlideTeleportThreshold).append("s");
						if (lodEngine != null) {
							var store = lodEngine.getSectionHashStore();
							summary.append(" l0CacheUsed=").append(store.getCachedHashBytes() >> 20).append("MB")
								.append("(").append(store.getCachedHashCount()).append(" entries)");
						}
						if (SableIntegration.isPresent()) {
							double sableRange = SableIntegration.currentRange();
							summary.append(" sable=").append(sableRange < 0 ? "?" : String.valueOf((int) sableRange)).append("b")
								.append("(autoLift=").append(config.compatSableAutoTrackingRange).append(")");
						}
						ctx.getSource().sendSuccess(() -> Component.literal(summary.toString()), false);
						return 1;
					}))

				.then(Commands.literal("targetTps")
					.then(Commands.argument("tps", IntegerArgumentType.integer(1, 20))
						.executes(ctx -> {
							int tps = IntegerArgumentType.getInteger(ctx, "tps");
							config.targetTps = tps;
							config.save();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] targetTps set to " + tps
									+ " (chunk gen will throttle when server runs slower than this)"),
								true);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] targetTps: " + config.targetTps),
							false);
						return 1;
					}))

				.then(Commands.literal("lodRadius")
					.then(Commands.argument("sections", IntegerArgumentType.integer(8, 1024))
						.executes(ctx -> {
							int radius = IntegerArgumentType.getInteger(ctx, "sections");
							int oldRadius = config.lodStreamRadius;
							config.lodStreamRadius = radius;
							config.save();
							if (syncService != null) {
								syncService.rebuildAllTrees(ctx.getSource().getServer());
							}
							if (config.compatSableAutoTrackingRange) {
								SableIntegration.liftTrackingRange(radius * 32.0);
							}
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] lodStreamRadius " + oldRadius + " -> " + radius
									+ " (" + (radius * 32) + " blocks); trees rebuilding"),
								true);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] lodStreamRadius: " + config.lodStreamRadius
								+ " sections (" + (config.lodStreamRadius * 32) + " blocks)"),
							false);
						return 1;
					}))

				.then(Commands.literal("generateOnChunkLoad")
					.then(Commands.argument("enabled", BoolArgumentType.bool())
						.executes(ctx -> {
							boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
							config.generateOnChunkLoad = enabled;
							config.save();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] generateOnChunkLoad set to " + enabled),
								true);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] generateOnChunkLoad: " + config.generateOnChunkLoad),
							false);
						return 1;
					}))

				.then(Commands.literal("maxSectionsPerTickPerPlayer")
					.then(Commands.argument("count", IntegerArgumentType.integer(1, 100000))
						.executes(ctx -> {
							int n = IntegerArgumentType.getInteger(ctx, "count");
							config.maxSectionsPerTickPerPlayer = n;
							config.save();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] maxSectionsPerTickPerPlayer set to " + n),
								true);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] maxSectionsPerTickPerPlayer: " + config.maxSectionsPerTickPerPlayer),
							false);
						return 1;
					}))

				.then(Commands.literal("sectionsPerPacket")
					.then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
						.executes(ctx -> {
							int n = IntegerArgumentType.getInteger(ctx, "count");
							config.sectionsPerPacket = n;
							config.save();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] sectionsPerPacket set to " + n),
								true);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] sectionsPerPacket: " + config.sectionsPerPacket),
							false);
						return 1;
					}))

				.then(Commands.literal("dirtyScanInterval")
					.then(Commands.argument("ticks", IntegerArgumentType.integer(1, 1200))
						.executes(ctx -> {
							int n = IntegerArgumentType.getInteger(ctx, "ticks");
							config.dirtyScanInterval = n;
							config.save();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] dirtyScanInterval set to " + n + " ticks"),
								true);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] dirtyScanInterval: " + config.dirtyScanInterval + " ticks"),
							false);
						return 1;
					}))

				.then(Commands.literal("maxDirtyChunksPerScan")
					.then(Commands.argument("count", IntegerArgumentType.integer(1, 10000))
						.executes(ctx -> {
							int n = IntegerArgumentType.getInteger(ctx, "count");
							config.maxDirtyChunksPerScan = n;
							config.save();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] maxDirtyChunksPerScan set to " + n),
								true);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] maxDirtyChunksPerScan: " + config.maxDirtyChunksPerScan),
							false);
						return 1;
					}))

				.then(Commands.literal("l0Cache")
					.then(Commands.literal("cap")
						.then(Commands.argument("megabytes", IntegerArgumentType.integer(1, 65536))
							.executes(ctx -> {
								int mb = IntegerArgumentType.getInteger(ctx, "megabytes");
								long bytes = (long) mb * 1024L * 1024L;
								config.l0HashCacheCapBytes = bytes;
								config.save();
								if (lodEngine != null) {
									lodEngine.getSectionHashStore().setCacheCapBytes(bytes);
								}
								ctx.getSource().sendSuccess(
									() -> Component.literal("[Voxy] L0 hash cache cap set to " + mb + " MB"
										+ (lodEngine == null ? " (will apply on next server start)" : "")),
									true);
								return 1;
							})))
					.then(Commands.literal("flush")
						.executes(ctx -> {
							if (lodEngine == null) {
								ctx.getSource().sendFailure(Component.literal(
									"[Voxy] Engine not running"));
								return 0;
							}
							int beforeEntries = lodEngine.getSectionHashStore().getCachedHashCount();
							lodEngine.getSectionHashStore().flushCache();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] L0 hash cache flushed (" + beforeEntries + " entries dropped)"),
								true);
							return 1;
						}))
					.executes(ctx -> {
						long capBytes = config.l0HashCacheCapBytes;
						if (lodEngine == null) {
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] L0 cache: cap=" + (capBytes >> 20) + " MB"
									+ " (engine not running)"),
								false);
							return 1;
						}
						var store = lodEngine.getSectionHashStore();
						int entries = store.getCachedHashCount();
						long usedBytes = store.getCachedHashBytes();
						long actualCap = store.getCacheCapBytes();
						double pctFull = actualCap == 0 ? 0.0 : (100.0 * usedBytes / actualCap);
						ctx.getSource().sendSuccess(
							() -> Component.literal(String.format(
								"[Voxy] L0 cache: %d entries, %.1f / %d MB used (%.1f%% full)",
								entries,
								usedBytes / (1024.0 * 1024.0),
								actualCap >> 20,
								pctFull)),
							false);
						return 1;
					}))

				.then(Commands.literal("merkleHeartbeat")
					.then(Commands.literal("now")
						.executes(ctx -> {
							if (syncService == null) {
								ctx.getSource().sendFailure(Component.literal("[Voxy] Engine not running"));
								return 0;
							}
							int n = syncService.forceEmitHeartbeats(ctx.getSource().getServer());
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] Forced heartbeat emit for " + n + " session(s)"),
								true);
							return 1;
						}))
					.then(Commands.argument("ticks", IntegerArgumentType.integer(0, 12000))
						.executes(ctx -> {
							int t = IntegerArgumentType.getInteger(ctx, "ticks");
							config.merkleHeartbeatTicks = t;
							config.save();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] merkleHeartbeatTicks set to " + t
									+ (t == 0 ? " (heartbeat disabled)" : " (~" + String.format("%.1f", t / 20.0) + "s)")),
								true);
							return 1;
						}))
					.executes(ctx -> {
						int t = config.merkleHeartbeatTicks;
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] merkleHeartbeatTicks: " + t
								+ (t == 0 ? " (disabled)" : " (~" + String.format("%.1f", t / 20.0) + "s)")),
							false);
						return 1;
					}))

				.then(Commands.literal("slideTeleportThreshold")
					.then(Commands.argument("sections", IntegerArgumentType.integer(1, 1024))
						.executes(ctx -> {
							int n = IntegerArgumentType.getInteger(ctx, "sections");
							config.merkleSlideTeleportThreshold = n;
							config.save();
							ctx.getSource().sendSuccess(
								() -> Component.literal("[Voxy] slideTeleportThreshold set to " + n + " sections"),
								true);
							return 1;
						}))
					.executes(ctx -> {
						ctx.getSource().sendSuccess(
							() -> Component.literal("[Voxy] slideTeleportThreshold: " + config.merkleSlideTeleportThreshold + " sections"),
							false);
						return 1;
					})));
		});
	}
}
