# Voxy Server

Server-side LoD synchronization mod for [Voxy](https://modrinth.com/mod/voxy), using Merkle quadtrees. Streams terrain LoDs and far-entity positions to clients over a Fabric network channel.

## Build

Multi-version via [Stonecutter](https://github.com/kikugie/stonecutter). Targets MC 1.21.1, 1.21.11, and 26.1.2. The active version (set in `stonecutter.gradle.kts`, currently 26.1.2) is what `./gradlew build` produces.

```sh
# Build the Voxy dependency first (if libs/voxy.jar is missing).
# Clone Voxy from https://github.com/cortex/voxy, build it, and copy the jar:
#   cd /path/to/voxy && ./gradlew jar
#   cp build/libs/voxy-*.jar /path/to/voxy-server/libs/voxy.jar
# (libs/voxy-<mc>.jar is preferred when present, with libs/voxy.jar as fallback)

# Build the active version
./gradlew build
# Output: build/libs/voxy-server-<mc>-0.1.0.jar

# Build a specific version
./gradlew :1.21.11:build
./gradlew :26.1.2:build

# Build all versions (used in CI)
./gradlew :26.1.2:build :1.21.11:build :1.21.1:build
```

Java requirement is per-version: 21 for 1.21.x, 25 for 26.1.2 (see `versions/<mc>/gradle.properties`). MC 1.20.1 is no longer a build target.

On NixOS / nix environments without a system JDK:
```sh
nix shell nixpkgs#jdk25 -c bash -c 'export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java)))) && ./gradlew build --no-daemon'
```

### Stonecutter caveats

- `stonecutter.gradle.kts` declares string replacements for cross-version names (Identifier vs ResourceLocation, getMinY vs getMinBuildHeight, ChunkPos x/z field vs accessor, etc.). When adding code that uses any of these, check that block.
- Preprocessor constants drive `//? if FOO {` blocks: `HAS_NEW_NETWORKING`, `HAS_IDENTIFIER`, `HAS_LOOKUP_OR_THROW`, `HAS_DEBUG_SCREEN`, `HAS_RENDER_PIPELINES`, `HAS_SUBMIT_NODE_COLLECTOR`, `HAS_PERMISSIONS`, `HAS_FULL_CHUNK_IS_OR_AFTER`, `SETBLOCKSTATE_INT_FLAGS`, `DEOBFUSCATED`. Never inline a version check -- use the constant. `HAS_SUBMIT_NODE_COLLECTOR` gates the 1.21.11 entity renderer path; `HAS_RENDER_PIPELINES` gates the 26.1.2 render pipeline path.
- `build.gradle.kts` switches between `fabric-loom` (deobfuscated, MC >= 26.1) and `fabric-loom-remap` (older, intermediary mappings) automatically. The Voxy jar is auto-detected as intermediary or production-name from its access widener header.

## Project layout

```
src/main/java/me/cortex/voxy/server/
  VoxyServerMod.java              # Entrypoint (ModInitializer), /voxysv commands, debug toggle
  config/VoxyServerConfig.java    # JSON config at config/voxy-server.json
  compat/SableIntegration.java    # Optional reflection-based Sable compat (lifts tracking range)
  engine/
    ServerLodEngine.java          # Extends VoxyInstance, manages WorldEngines per dimension
    ChunkVoxelizer.java           # Chunk load -> voxelization pipeline; IngestResult enum
    ChunkTimestampStore.java      # RocksDB-backed dirty tracking (survives restarts)
    DirtyScanService.java         # Periodic scanner: finds stale chunks, queues re-voxelization
    StoredSectionPresenceIndex.java  # Bloom filter for fast existence checks
  merkle/
    MerkleHashUtil.java           # xxHash64 computation, column/region key packing
    SectionHashStore.java         # Persists L0 hashes in RocksDB; in-memory L0 cache (200 MB default)
    PlayerMerkleTree.java         # Per-player in-memory quadtree; slideBounds; dangling-column tracking
  streaming/
    SyncService.java              # Orchestrates per-player sync, two-stage fetcher+ingest executors,
                                  # AIMD rate controller, Merkle heartbeat loop
    PlayerSyncSession.java        # Per-player state: tree, position, distance-sorted send queue
    SectionSerializer.java        # WorldSection -> LODSectionPayload with ID remapping (biome fix)
    EntitySyncService.java        # Far-entity sync (native transport only)
  network/                        # All custom packet payloads (C2S and S2C)
    VoxyServerNetworking.java     # Payload type registration
    LODSectionPayload, LODBulkPayload, PreSerializedLodPayload, LODClearPayload
    MerkleReadyPayload (C2S), MerkleSettingsPayload, MerkleL2HashesPayload
    MerkleClientL1Payload (C2S), MerkleHashUpdatePayload
    ConfigEditPayload (C2S)       # Live config field edit request
    ConfigEditResultPayload       # S2C: edit success/failure with message
    ConfigSnapshotPayload         # S2C: full config snapshot on join or successful edit
    TelemetrySnapshotPayload      # S2C: 1 Hz throughput counters for client HUD
    ConfigSyncHandler.java        # Server-side handler: validates, applies, persists, broadcasts config edits
  mixin/
    LevelChunkMixin.java          # setBlockState -> timestamp update (filters out worldgen)
    ChunkMapAccessor.java         # Access ChunkMap.entityMap (for native entity transport)
    ChunkMapTrackedEntityMixin.java  # Force-track far entities for native transport mode
    TrackedEntityAccessor.java    # Access ChunkMap$TrackedEntity.seenBy / serverEntity
  util/DebouncedLogger.java       # Kernel-style log debouncing for spammy debug output

src/client/java/me/cortex/voxy/server/client/
  VoxyServerClient.java           # ClientModInitializer, B/H keybinds, VoxyTelemetryOverlay registration
  VoxyServerClientConfig.java     # Client config at config/voxy-server-client.json
  VoxyServerModMenu.java          # ModMenuApi entrypoint -> VoxyServerConfigScreen
  VoxyServerConfigScreen.java     # ModMenu config screen (Screen wrapper)
  VoxyServerConfigScreenContent.java  # Live config editor widget; sends ConfigEditPayload; shows feedback
  ServerConfigState.java          # Client-side cache of last-received ConfigSnapshotPayload
  ServerConfigEditFeedback.java   # Delivers ConfigEditResultPayload results to the config UI
  ClientSyncHandler.java          # Receives sections, exchanges Merkle hashes, applies updates
  ClientMerkleState.java          # Local L0/L1/L2 hash state (preserved across reconnects)
  LODEntityRenderer.java          # Renders far entities (model mode only, vanilla unloaded-chunk entities)
  VoxyDebugRenderer.java          # LOD radius border + section highlight boxes (MC >= 26.1)
  LODSectionHighlightTracker.java # Recently-updated section highlight state (toggled by H keybind)
  VoxyBandwidthTracker.java       # Bandwidth counters (in kbps), used by F3 entry
  VoxyBandwidthDebugEntry.java    # F3 debug screen entry (MC >= 1.21.11)
  VoxyTelemetryHistory.java       # Rolling per-metric time-series from TelemetrySnapshotPayload
  VoxyTelemetryOverlay.java       # HUD sparkline overlay (pre-26.1 only, HudRenderCallback)
  mixin/client/
    DebugScreenEntriesAccessor.java           # F3 entry registration (MC >= 1.21.11)
    GameRendererMixin.java                    # Lifts GPU far-plane to lodRadius*32 (pre-26.1)
    compat/sable/SubLevelRenderSectionManagerMixin.java  # Lifts Sable SubLevel render distance
```

## Architecture

### Merkle quadtree (not octree)

Y dimension is collapsed at L1. Three tree levels:

- **L0** (leaf): individual WorldSection hashes (xxHash64 of serialized data), persisted in RocksDB.
- **L1** (column): hash of all L0 at same (x,z) across all Y. Per-player, in-memory.
- **L2** (region): 32x32 grid of L1 columns (64x64 chunks). Per-player, in-memory.
- **L3** (root): hash of all L2 regions.

Trees are built per-player on join, discarded on disconnect. When a player moves beyond `merkleSlideTeleportThreshold` sections (default 64), the tree is rebuilt in place via `slideBounds()` (incremental strip add/remove) rather than a full teardown. This avoids resending the entire radius on moderate movement.

**Dangling columns**: columns where the per-chunk marker (level=15 in `SectionHashStore`) is set but no L0 voxel data exists -- typically from a prior crash mid-voxelization. `PlayerMerkleTree` tracks these explicitly; `SyncService` force-re-voxelizes them during dispatch. `ChunkVoxelizer.detectAndRecoverDanglingSpawnMarkers()` and `scanAlreadyLoadedSpawnChunks()` run at server start to catch dangling spawn-area chunks.

### Sync protocol (2 round-trips)

1. Server -> client: `MerkleSettingsPayload` (radius, max sections per tick) on join. Also sends `ConfigSnapshotPayload` with the full current server config.
2. Client -> server: `MerkleReadyPayload` -> server builds tree, sends `MerkleL2HashesPayload`.
3. Client compares L2, responds with `MerkleClientL1Payload` for mismatches.
4. Server diffs L1 in `PlayerMerkleTree.findDifferingL0Sections`, returns two sets:
   - `sectionsToSync`: sections the server has data for, streamed via `LODBulkPayload` (wrapped in `PreSerializedLodPayload` on MC >= 1.20.5). Sections are sorted nearest-first to the player before draining.
   - `columnsToGenerate`: columns the server is missing data for. Scheduled via `SyncService.scheduleGeneration`.
5. Server sends `MerkleHashUpdatePayload` alongside each batch so client can track L1 column hashes.
6. Server periodically sends `TelemetrySnapshotPayload` (1 Hz) with per-window throughput counters (chunks completed, sections sent, heartbeats) for the client HUD. Also emits a Merkle heartbeat (`MerkleL2HashesPayload`) every `merkleHeartbeatTicks` (default 100) so clients that have drifted catch up without a full reconnect.

### Dirty tracking

Block changes are tracked via persistent timestamps in RocksDB (not an ephemeral in-memory set). Flow:

1. `LevelChunkMixin` fires on `setBlockState` (only for fully-loaded chunks, filters worldgen).
2. Writes `lastBlockUpdateTick` to `ChunkTimestampStore`.
3. `DirtyScanService` scans every N ticks for chunks where `lastBlockUpdate > lastVoxelization`. Scan interval and max-per-scan are read live from config (no restart needed). Logs a per-window summary rather than per-event noise.
4. Queues re-voxelization with `force=true` (bypasses already-voxelized check).
5. `onSectionDirty` callback is debounced (20 ticks) to avoid redundant hashing during burst updates.

### Voxelization idempotency

Voxelization is non-deterministic (depends on lighting/neighbor state). To prevent spurious hash conflicts:
- `onChunkLoad` skips chunks that already have a per-chunk marker in `SectionHashStore` (level=15 namespace, keyed by individual chunk position -- NOT by WorldSection position, since each section covers 2x2 chunks). `ChunkVoxelizer.ingestChunk` returns an `IngestResult` enum (`ENQUEUED`, `ALREADY_VOXELIZED`, `FAILED`).
- Only `DirtyScanService` (confirmed block changes) and the Merkle generation path force re-voxelization.
- After any marker write, `syncService.notifyChunkMarkerSet()` is called so the tree can update dangling-column state immediately.

### Merkle-driven chunk generation

Generation is demand-driven by the Merkle diff. A two-stage executor pipeline replaces the old single `genExecutor`:

- **`fetcherExecutor`** (16 threads): calls `level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, true)` -- the blocking chunk load. Never wrapped in `server.execute()` (deadlocks watchdog at scale).
- **`ingestExecutor`** (32 threads): receives the loaded chunk and calls `chunkVoxelizer.voxelizeNewChunk`.

An **AIMD rate controller** governs dispatch: `dispatchBudget` accumulates at `targetTps` sections/second (default 15) and is drawn down on each submission. On completion the budget is increased additively; on backpressure it is halved multiplicatively. `maxInFlightChunks` (default 16) caps the concurrent fetch count regardless of budget. This replaces the old fixed `chunkGenConcurrency` config field.

- `PlayerSyncSession.pendingGeneration` (column-keyed, synchronized) prevents re-scheduling across successive Merkle rounds.
- `SyncService.rebuildAllTrees()` is called when `lodStreamRadius` changes (via config edit or `/voxysv config lodRadius`).
- `getNearestEmptyColumns()` on `PlayerMerkleTree` returns columns sorted by distance to the player (min-heap), so generation prioritizes nearby terrain first.

### Far-entity sync (`EntitySyncService`)

Entity sync uses **native transport only** (the old `custom` transport with `LODEntityUpdatePayload`/`LODEntityRemovePayload` has been removed). The `entitySyncTransport` config field is gone.

- Server force-tracks entities via `ChunkMapTrackedEntityMixin`: cancels vanilla's tracker-removal for entities in the per-player force-track set, so vanilla streams full position/animation packets. `EntitySyncService.syncEntitiesFor` proactively calls `addPairing` for stationary observers.
- `LODEntityRenderer` renders entities that vanilla's renderer would skip (entity in unloaded chunk OR failing vanilla `shouldRender` cull). Billboard mode is removed; model rendering only.
- Filter mode (`entitySyncMode`): `non_trivial` (default -- excludes `ItemEntity`, `ExperienceOrb`, `Projectile`, `AreaEffectCloud`), `living`, `players_only`, or `all`. Players are always sorted to the front of the candidate list before the per-player cap.

### Live config editing

Server config fields can be edited at runtime without a restart, via two paths:

- **`/voxysv config <field> <value>`** (server-side command, op level 4).
- **ModMenu config screen** (client-side): sends `ConfigEditPayload` (C2S) -> `ConfigSyncHandler` validates, applies, persists, and broadcasts `ConfigSnapshotPayload` to all clients. `ConfigEditResultPayload` delivers success/failure back to the UI. Requires the client to have the mod installed.

`ConfigSyncHandler` fires a change callback on `lodStreamRadius` edits to trigger `SyncService.rebuildAllTrees()`.

### Sable compatibility (`SableIntegration`)

Optional integration via reflection -- no hard dependency. If Sable (`dev.ryanhcode.sable`) is present and `compatSableAutoTrackingRange = true` (default), `SableConfig.SUB_LEVEL_TRACKING_RANGE` is raised (never lowered) to `lodStreamRadius * 32` at server start and on any radius config change. `SubLevelRenderSectionManagerMixin` also lifts Sable's client-side render graph distance to `lodRadius * 2` chunks.

### Client debug HUD

- **F3 entry** (`VoxyBandwidthDebugEntry`, MC >= 1.21.11): inbound kbps, sections received, LOD entity count, server-side queue size + sync state name, pending gen count. Populated from `TelemetrySnapshotPayload`.
- **Telemetry sparkline overlay** (`VoxyTelemetryOverlay`, pre-26.1 only): per-metric rolling graphs from `VoxyTelemetryHistory`. Toggled via `telemetryOverlayEnabled` in client config; can require F3 to be open (`telemetryOverlayRequiresF3`).
- **LOD border keybind** (default `B`, set in `VoxyServerClient`): toggles the radius outline rendered by `VoxyDebugRenderer` (MC >= 26.1).
- **Section highlight keybind** (default `H`): toggles 2.5-second flash boxes on each newly-received section. `LODSectionHighlightTracker` caps at 256 entries, evicts oldest.
- **ModMenu config screen** (`VoxyServerConfigScreen`): live config editor. Available when ModMenu is installed. Shows current server values from `ServerConfigState` and sends edits via `ConfigEditPayload`.
- **GPU far-plane lift** (`GameRendererMixin`, pre-26.1): raises `getDepthFar()` to `lodRadius * 32` blocks so far entities and Sable SubLevels aren't GPU-clipped. No-ops if the method doesn't exist (26.1+).

### Server runtime commands (`/voxysv`, op level 4 / `Permissions.COMMANDS_ADMIN` on MC >= 1.21.11)

- `/voxysv debug` -- toggles `debugLogging`, persists to config.
- `/voxysv config` -- show all current config values.
- `/voxysv config targetTps <n>` -- AIMD target generation rate (sections/second).
- `/voxysv config lodRadius <n>` -- LOD stream radius; triggers tree rebuild for all players.
- `/voxysv config generateOnChunkLoad <true|false>` -- live toggle.
- `/voxysv config maxSectionsPerTickPerPlayer <n>` -- send throttle.
- `/voxysv config sectionsPerPacket <n>` -- bulk payload size (default 200).
- `/voxysv config dirtyScanInterval <ticks>` -- dirty scan cadence.
- `/voxysv config maxDirtyChunksPerScan <n>` -- dirty scan batch cap.
- `/voxysv config l0Cache cap <bytes>` -- resize the in-memory L0 hash cache live.
- `/voxysv config l0Cache flush` -- evict the L0 hash cache (forces RocksDB reads).
- `/voxysv config merkleHeartbeat now` -- emit a Merkle heartbeat to all players immediately.
- `/voxysv config merkleHeartbeat ticks <n>` -- change heartbeat cadence.
- `/voxysv entity` -- show mode/interval/max.
- `/voxysv entity interval <ticks>` -- 1..200.
- `/voxysv entity max <count>` -- 1..10000.
- `/voxysv entity enabled <true|false>` -- enable/disable entity sync.
- `/voxysv entity mode <non_trivial|living|players_only|all>` -- entity filter mode.

**Note**: the server command namespace is `/voxysv`, NOT `/voxy`. Upstream Voxy registers a client-side `/voxy` command that intercepts chat and cannot be shadowed.

## Key design decisions

- **Per-player trees, not global**: avoids O(world_size) memory. Only hashes within each player's radius are held.
- **`slideBounds` incremental updates**: on moderate movement, strips are added/removed rather than rebuilding the full tree, keeping per-move cost proportional to the perimeter not the area.
- **Dangling-column detection**: markers-present but no L0 data (common after a mid-voxelization crash) are tracked and force-re-voxelized at startup and during dispatch, so the tree converges without manual intervention.
- **Debounced dirty callback**: a WorldSection covers 2x2 chunks. During initial load, each neighboring chunk partially fills it, causing many dirty events. Debouncing waits for stability before hashing.
- **Server tells client its hashes**: client Mapper IDs differ from server's, so client can't compute matching hashes locally. Server sends `MerkleHashUpdatePayload` with each batch. Biome IDs are translated from Mapper-internal IDs to vanilla registry IDs in `SectionSerializer` before going on the wire.
- **Client preserves Merkle state across reconnects**: enables efficient diff on rejoin instead of full re-sync. State is only cleared on dimension change (`LODClearPayload`).
- **AIMD generation rate control**: replaces fixed concurrency. Adapts to server load automatically; `targetTps` and `maxInFlightChunks` are the tuning knobs.
- **L0 hash cache**: 200 MB in-memory `Long2LongOpenHashMap` in front of RocksDB for hot L0 reads. Avoids JNI LRUCache (version safety). Tunable live via `/voxysv config l0Cache cap`.
- **Nearest-first section dispatch**: `pollBatch` sorts the send queue by Manhattan distance to the player before draining, so the player always gets nearby terrain first.
- **Merkle heartbeat**: server re-sends L2 hashes every `merkleHeartbeatTicks` so clients that missed an update reconverge without a full reconnect.
- **Native-only entity transport**: eliminates the separate custom packet path. Entities arrive via standard vanilla tracking packets; the server only forces the tracking range for distant entities.
- **`DebouncedLogger`**: every spammy `VoxyServerMod.debug(...)` call is funneled through it. Repeated format strings collapse to `[repeated N more times]` per second.

## Testing

Set up a Fabric server matching the target MC version and place the built jar plus Voxy and Fabric API in the `mods/` folder. Then:

```sh
# 26.1.2 server (Java 25)
java -Xmx2G --enable-native-access=ALL-UNNAMED -jar fabric-server-launch.jar nogui

# Watch sync flow
tail -f logs/latest.log | grep -E '\[Sync\]|\[Voxelizer\]|\[MerkleGen\]|\[DirtyScan\]|\[EntitySync\]'
```

Enable `"debugLogging": true` in `config/voxy-server.json` (or `/voxysv debug` at runtime) for verbose output including hash conflict coordinates.
