# ScannerPlugin

Paper plugin that scans the terrain around an entity with rays and builds a small model of it next to another entity, using particles or item displays.

## Features
- Two render modes: coloured particles (blue is far, green is near) or item displays that copy the scanned blocks.
- The scan runs as an animation, one step at a time, and several scans can run at once.
- The model is shrunk by a configurable factor and can be split into finer cells for more detail.
- The model is turned to match the way the display entity faces.
- Entities of type `interaction` are detected: shown as red particles, or as the nearest item display tagged `scannable` in display mode.
- Safety limits protect the server from oversized scans.
- Messages in Russian and English.

## Requirements
- Paper 1.21.11, Java 21
- No other plugins needed

## Installation
1. Put `ScannerPlugin-<version>.jar` into the `plugins` folder.
2. Start the server; `plugins/ScannerPlugin/config.yml` is created.

### Upgrading from 1.x
The data folder, command, permissions and the last command argument changed:
- Settings now live in `plugins/ScannerPlugin/`; copy your `settings.language` value over.
- Use `/scanner` instead of `/scan`.
- Grant `scanner.admin` instead of `scan.admin`.
- The last argument is now `delay` in seconds instead of `delayTicks`.
- Displays are no longer saved: they disappear when the chunk unloads or the server restarts. Displays named `scan` made by 1.x stay; remove them with `/kill @e[type=item_display,name=scan]`.

## Commands
| Command | Permission | Description |
|---|---|---|
| `/scanner particle <scanner> <display> [radius] [scannerScale] [displayScale] [delay]` | `scanner.scan` | Scan and draw the model with particles |
| `/scanner display <scanner> <display> [radius] [scannerScale] [displayScale] [delay]` | `scanner.scan` | Scan and build the model from item displays |
| `/scanner stop` | `scanner.stop` | Stop running scans; already built parts stay |
| `/scanner cancel` | `scanner.stop` | Stop running scans and remove all built item displays |
| `/scanner reload` | `scanner.reload` | Reload `config.yml` |

Arguments:

| Argument | Meaning |
|---|---|
| `scanner` | Entity selector: the scan starts from this entity and uses its facing |
| `display` | Entity selector: the model is built next to this entity and follows its facing |
| `radius` | Scan radius in blocks |
| `scannerScale` | How many times the model is smaller than the scanned area |
| `displayScale` | Each model block is split into `displayScale³` cells for finer detail |
| `delay` | Pause between steps in seconds, fractions allowed (`0.05` is one tick) |

Omitted arguments use the defaults from `config.yml`. Positions and facing are captured when the scan starts.

## Permissions
| Permission | Default | Description |
|---|---|---|
| `scanner.scan` | op | Use `/scanner particle` and `/scanner display` |
| `scanner.stop` | op | Use `/scanner stop` and `/scanner cancel` |
| `scanner.reload` | op | Use `/scanner reload` |
| `scanner.admin` | op | All of the above |

## Configuration
File: `plugins/ScannerPlugin/config.yml`

| Key | Type | Default | Range | Description |
|---|---|---|---|---|
| `settings.language` | string | `auto` | `ru`, `en`, `auto` | Message language; `auto` follows the player's client, the console uses English |
| `settings.default-radius-blocks` | int | `10` | `1..max-radius-blocks` | Default `radius` |
| `settings.default-scanner-scale` | int | `1` | `1..100` | Default `scannerScale` |
| `settings.default-display-scale` | int | `1` | `1..max-display-scale` | Default `displayScale` |
| `settings.default-delay-seconds` | number | `0.05` | `0.05..3600` | Default `delay` |
| `settings.max-radius-blocks` | int | `100` | `1..1000` | Largest allowed `radius` |
| `settings.max-display-scale` | int | `4` | `1..16` | Largest allowed `displayScale` |
| `settings.max-rays-per-scan` | int | `50000` | `1..1000000` | Largest number of rays in one scan; also caps the displays or particles it creates |
| `settings.max-active-scans` | int | `8` | `1..64` | Scans that may run at the same time |

Invalid values are reported in the console and replaced with the defaults. Example for a stricter server:

```yaml
settings:
  language: en
  max-radius-blocks: 50
  max-rays-per-scan: 20000
  max-active-scans: 2
```

## Data
The plugin creates only `config.yml`. Built models are not saved: they vanish on restart or when their chunk unloads. Back up `config.yml` to keep your settings; delete it to reset them.

## Companion mod
None.

## Troubleshooting
| Problem | Solution |
|---|---|
| "The scan is too large" | Lower `radius` or `displayScale`, raise `scannerScale`, or raise `settings.max-rays-per-scan` |
| The model has holes | Parts of the area are in unloaded chunks, which are treated as empty; scan nearer the scanner or load the chunks |
| Water and lava do not appear | Only blocks that exist as items are shown |
| The model disappeared after a restart | By design, models are not saved |
| "No entity matches the given selector" | Check the selector and that the entity is loaded; when several entities match, the first one is used |

## License
All rights reserved.
