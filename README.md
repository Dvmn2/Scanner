# ScannerPlugin

Paper plugin that scans the terrain around an entity with raycasts and rebuilds it next to another entity as a small
model made of particles or item displays. Type: Paper plugin. Companion client mod: none.

## Requirements
- Minecraft 1.21.11, Paper, Java 21
- No dependencies, no companion mod

## Build and run
```bash
./gradlew build      # build/libs/ScannerPlugin-<version>.jar
./gradlew runServer  # Paper 1.21.11 test server
```

## Architecture
```text
net.dvmn2.scannerplugin
├── ScannerPlugin           composition root
├── Lang                    MiniMessage localisation (RU/EN)
├── Permissions             permission constants
├── PluginKeys              PDC key factory
├── ScannerSettings         config snapshot
├── ScanListener            orphan cleanup on EntitiesLoadEvent
├── command/ScannerCommand  Brigadier tree of /scanner
└── scan/                   scan engine
```

| Class | Responsibility |
|---|---|
| `ScannerPlugin` | composition root: wiring, enable/disable order, `reloadAll()` |
| `ScannerSettings` | immutable `config.yml` snapshot, validation with warnings |
| `ScanManager` | owns running scans, validates requests against limits, removes displays; main thread only |
| `ScanTask` | one scan: ray stepping, block and Interaction hit tests, model cells |
| `ScanRequest` | immutable scan parameters, location snapshots, delay conversion |
| `ScanResult` | outcome enum of `ScanManager#begin` |
| `RayDirections` | even sphere coverage; `count` sizes a scan without allocating |
| `ScanDisplayFactory` | creates and recognises marked `ItemDisplay` entities |
| `ScanParticles` | dust particle helpers |
| `ScannerCommand` | `/scanner particle\|display\|stop\|cancel\|reload` |

Threading: everything runs on the main server thread. There is no asynchronous code and no disk I/O.

Lifecycle:
- Enable: `saveDefaultConfig` → settings and language → `ScanManager#start` (orphan cleanup) → listener → command → one log line.
- Disable: `ScanManager#shutdown` stops all scans and removes every scan display.
- Reload: `ScannerPlugin#reloadAll` re-reads `config.yml`; running scans keep their parameters.

## Public API
None.

## Protocol
None.

## Data and files
The plugin writes no data files. `plugins/ScannerPlugin/config.yml` is the only file.

Scan displays are non-persistent (`Entity#setPersistent(false)`) and marked with the PDC key `scannerplugin:scan_display`,
so they never reach the disk and vanish when their chunk unloads or the server restarts. A marked display found
at startup or in `EntitiesLoadEvent` is an orphan and is removed.

## Conventions and constraints
- Each ray advances `1.0` block per step. Hits are tested at integer distances, so features thinner than the step can be missed.
- Points in unloaded chunks count as empty: the scan never loads chunks.
- Interaction entities are collected once at launch (radius + 2 blocks around the scanner). Interactions that appear or move later are ignored, consistent with the location snapshots.
- A ray is a hit only for blocks that have an item form (`Material#isItem`), so water, lava and technical blocks are skipped.
- `max-rays-per-scan` is checked before any ray is allocated (`RayDirections#count`). It also caps the displays or particles a scan can create, because every ray yields at most one result.
- The command takes the delay in seconds; `ScanRequest#delayTicks` converts it for the scheduler, minimum one tick.
- A scan that throws is logged once at `SEVERE` and stopped; it does not affect other scans.

### Migration from 1.x
- The plugin is renamed to `ScannerPlugin`; the data folder changes from `plugins/Scanner` to `plugins/ScannerPlugin`.
- The command is `/scanner` (was `/scan`); permissions are `scanner.*` (was `scan.admin`); the last argument is `delay` in seconds (was `delayTicks`).
- Displays are marked with PDC instead of the custom name `scan`, and are no longer saved. Displays named `scan` left by 1.x are not touched; remove them with `/kill @e[type=item_display,name=scan]`.

## License
All rights reserved.
