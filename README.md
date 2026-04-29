# Voxy Server

Server-side LoD synchronization for [Voxy](https://modrinth.com/mod/voxy). Generates and streams LoD data to clients using Merkle trees for efficient delta sync, plus optional far-entity tracking for entities outside the vanilla view distance.

## Features

- **Server-side LoD generation**: voxelizes chunks on the server so all clients get LoD data, even for terrain they haven't visited.
- **Merkle tree sync**: a quadtree of xxHash64 hashes determines which sections differ between server and client; only mismatches are sent. Client state is preserved across reconnects for fast rejoin.
- **Persistent dirty tracking**: block changes are tracked in RocksDB timestamps, surviving server restarts.
- **Demand-driven chunk generation**: chunks beyond load radius are generated only when a client actually needs them (driven by the Merkle diff).
- **Multi-player sync**: updates propagate to all players in range when blocks change.
- **Far-entity tracking** (optional): syncs mob/player positions outside the vanilla entity tracking range, with two transport modes:
  - **`custom`** -- lightweight position packets, block precision (low bandwidth).
  - **`native`** -- extends Minecraft's vanilla entity tracker so far entities get full models and animations (higher bandwidth).
- **Client debug HUD**: F3 entry shows incoming bandwidth, sections synced, LOD entity count, and server-side queue/state. Optional radius border and "section just updated" highlight overlays.

## Requirements

Multi-version build via [Stonecutter](https://github.com/kikugie/stonecutter). Pick the jar that matches your server's MC version.

| MC version | Java | Fabric Loader | Fabric API        | Voxy                                     |
|------------|------|---------------|-------------------|------------------------------------------|
| 26.1.2     | 25   | 0.18.6        | 0.147.0+26.1.2    | 0.2.15-beta                              |
| 1.21.11    | 21   | 0.18.4        | 0.141.3+1.21.11   | 0.2.15-beta                              |
| 1.21.1     | 21   | 0.16.10       | 0.116.6+1.21.1    | 0.2.14-alpha m3t4f1v3 backport           |
| 1.20.1     | 17   | 0.16.10       | 0.92.6+1.20.1     | 0.2.14-alpha m3t4f1v3 backport           |

Clients also need [Sodium](https://modrinth.com/mod/sodium) (required by Voxy).

## Installation

### Server

Drop these into the server's `mods/` folder:

- `voxy-server-<mc>-0.1.0.jar` (this mod)
- The matching Voxy jar
- The matching Fabric API jar

No Sodium needed on the server.

### Client

Install Voxy and Sodium as usual. This mod's client component is included in the same jar -- just add `voxy-server-<mc>-0.1.0.jar` to the client's `mods/` folder alongside Voxy and Sodium.

## Configuration

### Server: `config/voxy-server.json` (generated on first run)

| Option                       | Default     | Description                                                   |
|------------------------------|-------------|---------------------------------------------------------------|
| `lodStreamRadius`            | 256         | LoD sync radius in section coordinates (1 section = 32 blocks) |
| `maxSectionsPerTickPerPlayer`| 100         | Max sections to process per tick per player                   |
| `sectionsPerPacket`          | 50          | Sections per network packet batch                             |
| `generateOnChunkLoad`        | true        | Voxelize chunks as they load                                  |
| `tickInterval`               | 5           | Reserved tick cadence                                         |
| `workerThreads`              | 3           | Voxy worker thread pool size                                  |
| `dirtyScanInterval`          | 10          | Ticks between dirty chunk scans                               |
| `maxDirtyChunksPerScan`      | 64          | Max chunks to re-voxelize per scan cycle                      |
| `chunkGenConcurrency`        | 8           | Worker threads for Merkle-driven chunk generation             |
| `debugLogging`               | false       | Enable verbose debug logging (toggle at runtime: `/voxy debug`) |
| `enableEntitySync`           | true        | Enable the far-entity sync service                            |
| `entitySyncIntervalTicks`    | 10          | Ticks between entity sync passes                              |
| `maxLODEntitiesPerPlayer`    | 200         | Cap on tracked far entities per player                        |
| `entitySyncMode`             | `living`    | `living`, `players_only`, or `all`                            |
| `entitySyncTransport`        | `custom`    | `custom` (low bandwidth) or `native` (full vanilla tracking)  |

### Client: `config/voxy-server-client.json`

| Option              | Default      | Description                                  |
|---------------------|--------------|----------------------------------------------|
| `entityRenderMode`  | `billboard`  | `billboard` or `model` for far entities      |
| `debugLogging`      | false        | Verbose client logging                       |

### Runtime commands

Server (op level 4):
- `/voxy debug` -- toggle debug logging.
- `/voxy entity` -- show entity sync status.
- `/voxy entity interval <ticks>` -- adjust sync cadence (1..200).
- `/voxy entity max <count>` -- adjust per-player cap (1..10000).

Client:
- `/voxyentity mode billboard|model` -- switch far-entity rendering.
- `/voxyentity debug` -- toggle client debug logging.
- `/voxyhighlight` -- toggle "section just updated" flash overlay.
- `/voxyhighlight clear` -- clear active highlights.
- Default `B` keybind -- toggle LOD radius border (MC >= 26.1).

## How it works

### Merkle quadtree

The mod maintains a per-player Merkle quadtree over the world's LoD sections:

```
L3 (root)     hash of all L2 regions
L2 (region)   64x64 chunks -- at radius 256 sections, ~32x32 nodes
L1 (column)   all vertical sections at one (x,z) position
L0 (leaf)     individual 32x32x32 WorldSection hash (xxHash64)
```

The Y dimension is collapsed at L1 (world height is bounded), making this a 2D quadtree rather than a 3D octree. This keeps sync to 2 network round-trips.

### Sync flow

1. Client connects; server sends settings (`MerkleSettingsPayload`).
2. Client sends a ready signal (`MerkleReadyPayload`).
3. Server builds a Merkle tree from persisted L0 hashes within the player's radius and sends L2 region hashes (`MerkleL2HashesPayload`).
4. Client compares with its local state, responds with L1 column hashes for mismatched regions (`MerkleClientL1Payload`).
5. Server diffs L1 and produces two sets:
   - **Sections to sync** -- streamed in batches via `PreSerializedLodPayload`.
   - **Columns to generate** -- chunks fetched on demand and voxelized.
6. Hash updates (`MerkleHashUpdatePayload`) are sent alongside section data so the client can track its state.
7. Server periodically sends `SyncStatusPayload` with queue/state info for the client F3 HUD.

On reconnection, the client preserves its hash state, so only actually-changed sections are re-sent. State is cleared only on dimension change.

### Block change propagation

1. Block change triggers a mixin on `LevelChunk.setBlockState` (worldgen-time changes are filtered out).
2. Timestamp is persisted in RocksDB (`lastBlockUpdateTick`).
3. `DirtyScanService` detects chunks where `lastBlockUpdate > lastVoxelization` and re-voxelizes.
4. After a debounce period (1 second), the updated section is hashed and pushed to all players in range whose tree contains it.

### Far-entity sync

Server scans entities every `entitySyncIntervalTicks` within the LoD radius but outside the vanilla view distance. Candidates are filtered by mode, sorted with players first, and capped at `maxLODEntitiesPerPlayer`.

- In **custom** mode, the server sends `LODEntityUpdatePayload` / `LODEntityRemovePayload` deltas; the client renders them via a billboard or model renderer.
- In **native** mode, the server adds these entities to a force-track set; a mixin on `ChunkMap$TrackedEntity` keeps vanilla from removing them, so the engine streams native position/animation packets.

## Building from source

Requires the JDK matching the active version's `mod.java_version` (currently 25 for 26.1.2). Stonecutter is used to support all four MC versions from one source tree.

First, obtain the Voxy jar and place it at `libs/voxy.jar` (or `libs/voxy-<mc>.jar` to use a version-specific jar):

```sh
# Clone and build Voxy
git clone https://github.com/cortex/voxy.git /tmp/voxy
cd /tmp/voxy && ./gradlew jar

# Copy into this project's libs/
cp build/libs/voxy-*.jar /path/to/voxy-server/libs/voxy.jar
```

Then build:

```sh
cd /path/to/voxy-server

# Build the active version (26.1.2 by default)
./gradlew build

# Or a specific version
./gradlew :1.21.11:build
./gradlew :1.21.1:build
./gradlew :1.20.1:build

# All versions
./gradlew :26.1.2:build :1.21.11:build :1.21.1:build :1.20.1:build
```

Output: `build/libs/voxy-server-<mc>-0.1.0.jar`.

## License

MIT
