# Voxy Server

Server-side LoD synchronization mod for [Voxy](https://modrinth.com/mod/voxy), using Merkle quadtrees.

## Build

Requires Java 25. Uses Fabric Loom.

```sh
# Build the Voxy dependency first (if libs/voxy.jar is missing).
# Clone Voxy from https://github.com/cortex/voxy, build it, and copy the jar:
#   cd /path/to/voxy && ./gradlew jar
#   cp build/libs/voxy-*.jar /path/to/voxy-server/libs/voxy.jar

# Build this mod
./gradlew build
# Output: build/libs/voxy-server-0.1.0.jar
```

On NixOS / nix environments without a system JDK:
```sh
nix shell nixpkgs#jdk25 -c bash -c 'export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java)))) && ./gradlew build --no-daemon'
```

## Project layout

```
src/main/java/me/cortex/voxy/server/
  VoxyServerMod.java              # Entrypoint (ModInitializer)
  config/VoxyServerConfig.java    # JSON config at config/voxy-server.json
  engine/
    ServerLodEngine.java          # Extends VoxyInstance, manages WorldEngines per dimension
    ChunkVoxelizer.java           # Chunk load -> voxelization pipeline
    ChunkTimestampStore.java      # RocksDB-backed dirty tracking (survives restarts)
    ChunkGenerationService.java   # Generates chunks beyond load radius out to LOD radius
    DirtyScanService.java         # Periodic scanner: finds stale chunks, queues re-voxelization
    StoredSectionPresenceIndex.java  # Bloom filter for fast existence checks
  merkle/
    MerkleHashUtil.java           # xxHash64 computation
    SectionHashStore.java         # Persists L0 hashes in RocksDB
    PlayerMerkleTree.java         # Per-player in-memory quadtree (L1/L2/L3 from L0 hashes)
  streaming/
    SyncService.java              # Orchestrates per-player sync, debounces dirty sections
    PlayerSyncSession.java        # Per-player state: tree, position, send queue
    SectionSerializer.java        # WorldSection -> LODSectionPayload with ID remapping
  network/                        # All custom packet payloads (C2S and S2C)
  mixin/LevelChunkMixin.java      # setBlockState -> timestamp update (filters out worldgen)

src/client/java/me/cortex/voxy/server/client/
  VoxyServerClient.java           # ClientModInitializer
  ClientSyncHandler.java          # Receives sections, exchanges Merkle hashes
  ClientMerkleState.java          # Local L0/L1/L2 hash state (preserved across reconnects)
```

## Architecture

### Merkle quadtree (not octree)

Y dimension is collapsed at L1. Three tree levels:

- **L0** (leaf): individual WorldSection hashes (xxHash64 of serialized data), persisted in RocksDB
- **L1** (column): hash of all L0 at same (x,z) across all Y. Per-player, in-memory.
- **L2** (region): 32x32 grid of L1 columns (64x64 chunks). Per-player, in-memory.
- **L3** (root): hash of all L2 regions.

Trees are built per-player on join, discarded on disconnect.

### Sync protocol (2 round-trips)

1. Client sends `MerkleReadyPayload` -> server builds tree, sends `MerkleL2HashesPayload`
2. Client compares L2, responds with `MerkleClientL1Payload` for mismatches
3. Server diffs L1, streams changed sections via `PreSerializedLodPayload`
4. Server sends `MerkleHashUpdatePayload` alongside each batch so client can track state

### Dirty tracking

Block changes are tracked via persistent timestamps in RocksDB (not an ephemeral in-memory set). Flow:

1. `LevelChunkMixin` fires on `setBlockState` (only for fully-loaded chunks, filters worldgen)
2. Writes `lastBlockUpdateTick` to `ChunkTimestampStore`
3. `DirtyScanService` scans every N ticks for chunks where `lastBlockUpdate > lastVoxelization`
4. Queues re-voxelization with `force=true` (bypasses already-voxelized check)
5. `onSectionDirty` callback is debounced (20 ticks) to avoid redundant hashing during burst updates

### Voxelization idempotency

Voxelization is non-deterministic (depends on lighting/neighbor state). To prevent spurious hash conflicts:
- `onChunkLoad` and `ChunkGenerationService` skip chunks that already have a hash in `SectionHashStore`
- Only `DirtyScanService` (confirmed block changes) forces re-voxelization

## Key design decisions

- **Per-player trees, not global**: avoids O(world_size) memory. Only hashes within each player's radius are held.
- **Debounced dirty callback**: a WorldSection covers 2x2 chunks. During initial load, each neighboring chunk partially fills it, causing many dirty events. Debouncing waits for stability before hashing.
- **Server tells client its hashes**: client Mapper IDs differ from server's, so client can't compute matching hashes locally. Server sends `MerkleHashUpdatePayload` with each batch.
- **Client preserves Merkle state across reconnects**: enables efficient diff on rejoin instead of full re-sync.

## Testing

Set up a Fabric 0.18.6 server for MC 26.1.1 and place the built jar plus Voxy and Fabric API in the `mods/` folder. Then:

```sh
# Start the server (requires Java 25)
java -Xmx2G --enable-native-access=ALL-UNNAMED -jar fabric-server-launch.jar nogui

# Watch sync flow
tail -f logs/latest.log | grep -E '\[Sync\]|\[Voxelizer\]|\[ChunkGen\]|\[DirtyScan\]'
```

Enable `"debugLogging": true` in `config/voxy-server.json` for verbose output including hash conflict coordinates.
