# Scanner

A Paper plugin that scans the terrain around an entity using raycasts and renders the result as a scaled-down 3D model next to another entity.

## Requirements

- Paper 1.21.11 or a compatible fork
- Java 21

## How it works

The `/scan` command casts a set of rays outward from a chosen "scanner" entity in all directions, up to a configurable radius. Each ray reports the first solid block or `Interaction` entity it hits. The result is rendered as a compressed model next to a "display" entity, either as colored particles or as a grid of `ItemDisplay` entities matching the scanned blocks.

Multiple scans can run concurrently; each invocation starts an independent task.

## Command

```
/scan <particle|display> <scanner> <display> [maxRadius] [scannerScale] [displayScale] [delayTicks]
/scan stop
/scan cancel
```

| Argument | Description | Default |
|---|---|---|
| `particle` / `display` | Rendering mode: colored dust particles or `ItemDisplay` entities | required |
| `scanner` | Entity selector for the scan origin | required |
| `display` | Entity selector for where the model is rendered | required |
| `maxRadius` | Scan radius in blocks | 10 |
| `scannerScale` | Factor by which the model is shrunk relative to the real world | 1 |
| `displayScale` | Number of sub-cells per block edge in the rendered model (`displayScale³` display entities per block) | 1 |
| `delayTicks` | Delay in ticks between animation steps | 1 |

`stop` cancels all running scans without removing already spawned entities. `cancel` cancels all running scans and removes every `ItemDisplay` created by the plugin.

## Permissions

| Permission | Description | Default |
|---|---|---|
| `scan.admin` | Access to the `/scan` command | op |

## Configuration

`config.yml`:

```yaml
settings:
  # Plugin message language: "ru", "en", or "auto".
  # "auto" resolves per command sender: players use their client locale,
  # console and other non-player senders default to English.
  language: "auto"
```

## Building

```
./gradlew build
```

The compiled jar is placed in `build/libs/`.

## License

No license specified.