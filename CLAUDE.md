# Voxy Server

Server-side LoD synchronization mod for [Voxy](https://modrinth.com/mod/voxy), using Merkle quadtrees. Streams terrain LoDs and far-entity positions to clients over a Fabric network channel.

## Build

Multi-version via [Stonecutter](https://github.com/kikugie/stonecutter). Targets MC 1.20.1, 1.21.1, 1.21.11, and 26.1.2. The active version (set in `stonecutter.gradle.kts`, currently 26.1.2) is what `./gradlew build` produces.

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
./gradlew :26.1.2:build :1.21.11:build :1.21.1:build :1.20.1:build
```

Java requirement is per-version: 17 for 1.20.1, 21 for 1.21.x, 25 for 26.1.2 (see `versions/<mc>/gradle.properties`).

On NixOS / nix environments without a system JDK:
```sh
nix shell nixpkgs#jdk25 -c bash -c 'export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java)))) && ./gradlew build --no-daemon'
```

### Stonecutter caveats

- `stonecutter.gradle.kts` declares string replacements for cross-version names (Identifier vs ResourceLocation, getMinY vs getMinBuildHeight, ChunkPos x/z field vs accessor, etc.). When adding code that uses any of these, check that block.
- Preprocessor constants drive `//? if FOO {` blocks: `HAS_NEW_NETWORKING`, `HAS_IDENTIFIER`, `HAS_LOOKUP_OR_THROW`, `HAS_DEBUG_SCREEN`, `HAS_RENDER_PIPELINES`, `HAS_PERMISSIONS`, `HAS_FULL_CHUNK_IS_OR_AFTER`, `SETBLOCKSTATE_INT_FLAGS`, `MC_1_20_1`, `DEOBFUSCATED`. Never inline a version check -- use the constant.
- `build.gradle.kts` switches between `fabric-loom` (deobfuscated, MC >= 26.1) and `fabric-loom-remap` (older, intermediary mappings) automatically. The Voxy jar is auto-detected as intermediary or production-name from its access widener header.

## Project layout

```
src/main/java/me/cortex/voxy/server/
  VoxyServerMod.java              # Entrypoint (ModInitializer), /voxy commands, debug toggle
  config/VoxyServerConfig.java    # JSON config at config/voxy-server.json
  engine/
    ServerLodEngine.java          # Extends VoxyInstance, manages WorldEngines per dimension
    ChunkVoxelizer.java           # Chunk load -> voxelization pipeline
    ChunkTimestampStore.java      # RocksDB-backed dirty tracking (survives restarts)
    DirtyScanService.java         # Periodic scanner: finds stale chunks, queues re-voxelization
    StoredSectionPresenceIndex.java  # Bloom filter for fast existence checks
  merkle/
    MerkleHashUtil.java           # xxHash64 computation, column/region key packing
    SectionHashStore.java         # Persists L0 hashes in RocksDB
    PlayerMerkleTree.java         # Per-player in-memory quadtree (L1/L2/L3 from L0 hashes)
  streaming/
    SyncService.java              # Orchestrates per-player sync, debounces dirty sections,
                                  # schedules Merkle-driven chunk generation
    PlayerSyncSession.java        # Per-player state: tree, position, send queue, pending gen
    SectionSerializer.java        # WorldSection -> LODSectionPayload with ID remapping
    EntitySyncService.java        # Far-entity sync (custom or native transport)
    PlayerEntityTracker.java      # Per-player snapshot for custom-mode entity diffing
  network/                        # All custom packet payloads (C2S and S2C)
    VoxyServerNetworking.java     # Payload type registration
    LODSectionPayload, LODBulkPayload, PreSerializedLodPayload, LODClearPayload
    MerkleReadyPayload (C2S), MerkleSettingsPayload, MerkleL2HashesPayload
    MerkleClientL1Payload (C2S), MerkleHashUpdatePayload
    LODEntityUpdatePayload, LODEntityRemovePayload
    SyncStatusPayload             # Periodic queue/state/gen status to client
  mixin/
    LevelChunkMixin.java          # setBlockState -> timestamp update (filters out worldgen)
    ChunkMapAccessor.java         # Access ChunkMap.entityMap (for native entity transport)
    ChunkMapTrackedEntityMixin.java  # Force-track far entities for native transport mode
    TrackedEntityAccessor.java    # Access ChunkMap$TrackedEntity.seenBy / serverEntity
  util/DebouncedLogger.java       # Kernel-style log debouncing for spammy debug output

src/client/java/me/cortex/voxy/server/client/
  VoxyServerClient.java           # ClientModInitializer, /voxyentity + /voxyhighlight commands, B keybind
  VoxyServerClientConfig.java     # Client config at config/voxy-server-client.json
  ClientSyncHandler.java          # Receives sections, exchanges Merkle hashes, applies updates
  ClientMerkleState.java          # Local L0/L1/L2 hash state (preserved across reconnects)
  LODEntityManager.java           # Tracks far entities received from server
  LODEntityRenderer.java          # Renders far entities (billboard or model mode, MC >= 26.1)
  VoxyDebugRenderer.java          # LOD radius border + section highlight boxes (MC >= 26.1)
  LODSectionHighlightTracker.java # Recently-updated section highlight state
  VoxyBandwidthTracker.java       # Bandwidth counters (in kbps), used by F3 entry
  VoxyBandwidthDebugEntry.java    # F3 debug screen entry (MC >= 1.21.11)
  mixin/client/DebugScreenEntriesAccessor.java  # F3 entry registration (MC >= 1.21.11)
```

## Architecture

### Merkle quadtree (not octree)

Y dimension is collapsed at L1. Three tree levels:

- **L0** (leaf): individual WorldSection hashes (xxHash64 of serialized data), persisted in RocksDB.
- **L1** (column): hash of all L0 at same (x,z) across all Y. Per-player, in-memory.
- **L2** (region): 32x32 grid of L1 columns (64x64 chunks). Per-player, in-memory.
- **L3** (root): hash of all L2 regions.

Trees are built per-player on join, discarded on disconnect.

### Sync protocol (2 round-trips)

1. Server -> client: `MerkleSettingsPayload` (radius, max sections per tick) on join.
2. Client -> server: `MerkleReadyPayload` -> server builds tree, sends `MerkleL2HashesPayload`.
3. Client compares L2, responds with `MerkleClientL1Payload` for mismatches.
4. Server diffs L1 in `PlayerMerkleTree.findDifferingL0Sections`, returns two sets:
   - `sectionsToSync`: sections the server has data for, streamed via `LODBulkPayload` (wrapped in `PreSerializedLodPayload` on MC >= 1.20.5).
   - `columnsToGenerate`: columns the server is missing data for. Scheduled for chunk generation via `SyncService.scheduleGeneration` (replaces the old passive `ChunkGenerationService`).
5. Server sends `MerkleHashUpdatePayload` alongside each batch so client can track L1 column hashes.
6. Server periodically sends `SyncStatusPayload` (queue size, state ordinal, pending gen count) for the client F3 HUD.

### Dirty tracking

Block changes are tracked via persistent timestamps in RocksDB (not an ephemeral in-memory set). Flow:

1. `LevelChunkMixin` fires on `setBlockState` (only for fully-loaded chunks, filters worldgen).
2. Writes `lastBlockUpdateTick` to `ChunkTimestampStore`.
3. `DirtyScanService` scans every N ticks for chunks where `lastBlockUpdate > lastVoxelization`.
4. Queues re-voxelization with `force=true` (bypasses already-voxelized check).
5. `onSectionDirty` callback is debounced (20 ticks) to avoid redundant hashing during burst updates.

### Voxelization idempotency

Voxelization is non-deterministic (depends on lighting/neighbor state). To prevent spurious hash conflicts:
- `onChunkLoad` skips chunks that already have a per-chunk marker in `SectionHashStore` (level=15 namespace, keyed by individual chunk position -- NOT by WorldSection position, since each section covers 2x2 chunks).
- Only `DirtyScanService` (confirmed block changes) and the Merkle generation path force re-voxelization.

### Merkle-driven chunk generation

The old `ChunkGenerationService` (passive radial generation) was removed. Generation is now demand-driven by the Merkle diff:

- After receiving a client L1 batch, `SyncService.scheduleGeneration` walks `columnsToGenerate`, finds missing chunks via `SectionHashStore.getMissingChunksForSection`, and submits each to `genExecutor` (size = `chunkGenConcurrency`).
- The executor calls `level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, true)` then `chunkVoxelizer.voxelizeNewChunk`.
- `PlayerSyncSession.pendingGeneration` (column-keyed) prevents re-scheduling across successive Merkle rounds.
- This means generation only happens for things the client actually doesn't have. No more burning CPU on terrain everyone already has.

### Far-entity sync (`EntitySyncService`)

Two transport modes (set by `entitySyncTransport`):

- **`custom`** (default): server sends lightweight `LODEntityUpdatePayload` / `LODEntityRemovePayload` packets at block precision every `entitySyncIntervalTicks`. Client renders via `LODEntityRenderer` (billboard or model). Diffed against `PlayerEntityTracker` snapshot.
- **`native`**: server adds a "force-track" set per player. `ChunkMapTrackedEntityMixin` cancels vanilla's tracker-removal logic for entities in that set, so vanilla streams full position/animation packets. `EntitySyncService.syncEntitiesNative` proactively calls `addPairing` because vanilla won't trigger `updatePlayer` for stationary observers.

Filter mode (`entitySyncMode`): `living` (default), `players_only`, or `all`. Players are always sorted to the front of the candidate list before the per-player cap.

### Client debug HUD

- **F3 entry** (`VoxyBandwidthDebugEntry`, MC >= 1.21.11): inbound kbps, sections received, LOD entity count, server-side queue size + sync state name, pending gen count.
- **LOD border keybind** (default `B`, set in `VoxyServerClient`): toggles the radius outline rendered by `VoxyDebugRenderer` (MC >= 26.1).
- **`/voxyhighlight`** (client command): toggles 2.5-second flash boxes on each newly-received section. `LODSectionHighlightTracker` caps at 256 entries, evicts oldest.
- **`/voxyentity mode billboard|model`**: switches `LODEntityRenderer` rendering style.
- **`/voxyentity debug`**: toggles client debug logging.

### Server runtime commands (`/voxy`, op level 4 / `Permissions.COMMANDS_ADMIN` on MC >= 1.21.11)

- `/voxy debug` -- toggles `debugLogging`, persists to config.
- `/voxy entity` -- show transport/mode/interval/max.
- `/voxy entity interval <ticks>` -- 1..200.
- `/voxy entity max <count>` -- 1..10000.

## Key design decisions

- **Per-player trees, not global**: avoids O(world_size) memory. Only hashes within each player's radius are held.
- **Debounced dirty callback**: a WorldSection covers 2x2 chunks. During initial load, each neighboring chunk partially fills it, causing many dirty events. Debouncing waits for stability before hashing.
- **Server tells client its hashes**: client Mapper IDs differ from server's, so client can't compute matching hashes locally. Server sends `MerkleHashUpdatePayload` with each batch.
- **Client preserves Merkle state across reconnects**: enables efficient diff on rejoin instead of full re-sync. State is only cleared on dimension change (`LODClearPayload`).
- **Demand-driven generation**: no idle work for already-synced terrain.
- **`DebouncedLogger`**: every spammy `VoxyServerMod.debug(...)` call is funneled through it. Repeated format strings collapse to `[repeated N more times]` per second.

## Testing

Set up a Fabric server matching the target MC version and place the built jar plus Voxy and Fabric API in the `mods/` folder. Then:

```sh
# 26.1.2 server (Java 25)
java -Xmx2G --enable-native-access=ALL-UNNAMED -jar fabric-server-launch.jar nogui

# Watch sync flow
tail -f logs/latest.log | grep -E '\[Sync\]|\[Voxelizer\]|\[MerkleGen\]|\[DirtyScan\]|\[EntitySync\]'
```

Enable `"debugLogging": true` in `config/voxy-server.json` (or `/voxy debug` at runtime) for verbose output including hash conflict coordinates.
