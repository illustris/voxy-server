# Voxy Server

Server-side LoD synchronization for [Voxy](https://modrinth.com/mod/voxy). Generates and streams LoD data to clients using Merkle trees for efficient delta sync.

## Features

- **Server-side LoD generation**: voxelizes chunks on the server so all clients get LoD data, even for terrain they haven't visited
- **Merkle tree sync**: uses a quadtree of xxHash64 hashes to efficiently determine which sections differ between server and client, sending only what changed
- **Persistent dirty tracking**: block changes are tracked in RocksDB timestamps, surviving server restarts
- **Passive chunk generation**: generates chunks beyond each player's load radius out to the full LoD radius
- **Multi-player sync**: updates propagate to all players in range when blocks change

## Requirements

- Minecraft 26.1.1
- Fabric Loader 0.18.6+
- Fabric API 0.145.3+26.1.1
- [Voxy](https://modrinth.com/mod/voxy) 0.2.15-beta
- Java 25+

Clients also need [Sodium](https://modrinth.com/mod/sodium) 0.8.9 (required by Voxy).

## Installation

### Server

Drop these into the server's `mods/` folder:

- `voxy-server-0.1.0.jar` (this mod)
- `voxy-0.2.15-beta.jar` (Voxy)
- `fabric-api-0.145.3+26.1.1.jar` (Fabric API)

No Sodium needed on the server.

### Client

Install Voxy and Sodium as usual. This mod's client component is included in the same jar -- just add `voxy-server-0.1.0.jar` to the client's `mods/` folder alongside Voxy and Sodium.

## Configuration

Config file: `config/voxy-server.json` (generated on first run)

| Option | Default | Description |
|--------|---------|-------------|
| `lodStreamRadius` | 256 | LoD sync radius in section coordinates (1 section = 32 blocks) |
| `maxSectionsPerTickPerPlayer` | 100 | Max sections to process per tick per player |
| `sectionsPerPacket` | 50 | Sections per network packet batch |
| `generateOnChunkLoad` | true | Voxelize chunks as they load |
| `workerThreads` | 3 | Voxy worker thread pool size |
| `dirtyScanInterval` | 10 | Ticks between dirty chunk scans |
| `maxDirtyChunksPerScan` | 64 | Max chunks to re-voxelize per scan cycle |
| `debugLogging` | false | Enable verbose debug logging |
| `passiveChunkGeneration` | true | Generate chunks beyond load radius for LoD |
| `chunkGenConcurrency` | 8 | Threads and batch size for passive chunk generation |

## How it works

### Merkle quadtree

The mod maintains a per-player Merkle quadtree over the world's LoD sections:

```
L3 (root)     hash of all L2 regions
L2 (region)   64x64 chunks -- at 2048 render distance: 32x32 nodes
L1 (column)   all vertical sections at one (x,z) position
L0 (leaf)     individual 32x32x32 WorldSection hash
```

The Y dimension is collapsed at L1 (world height is limited), making this a 2D quadtree rather than a 3D octree. This reduces sync to 2 network round-trips.

### Sync flow

1. Client connects and sends a ready signal
2. Server builds a Merkle tree from persisted L0 hashes within the player's radius
3. Server sends L2 region hashes to client
4. Client compares with its local state, responds with L1 column hashes for mismatched regions
5. Server identifies differing L0 sections and streams them in batches
6. Hash updates are sent alongside section data so the client can track its state

On reconnection, the client preserves its hash state, so only actually-changed sections are re-sent.

### Block change propagation

1. Block change triggers a mixin on `LevelChunk.setBlockState`
2. Timestamp is persisted in RocksDB (`lastBlockUpdateTick`)
3. `DirtyScanService` detects chunks where `lastBlockUpdate > lastVoxelization`
4. Chunk is re-voxelized and hash is recomputed
5. After a debounce period (1 second), the updated section is pushed to all players in range

## Building from source

Requires Java 25.

First, obtain the Voxy jar and place it at `libs/voxy.jar`:

```sh
# Clone and build Voxy
git clone https://github.com/cortex/voxy.git /tmp/voxy
cd /tmp/voxy && ./gradlew jar

# Copy into this project's libs/
cp build/libs/voxy-*.jar /path/to/voxy-server/libs/voxy.jar
```

Then build the mod:

```sh
cd /path/to/voxy-server
./gradlew build
# Output: build/libs/voxy-server-0.1.0.jar
```

## License

MIT
