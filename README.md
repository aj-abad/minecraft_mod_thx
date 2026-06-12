# THX Helicopter Mod — Minecraft Forge 1.7.10

A craftable, flyable helicopter for Minecraft **1.7.10** (Forge). Ported from the
original 1.6.1 ModLoader/MCP mod to a modern Forge `@Mod`.

## Features

- Craftable **Helicopter** item (Transport creative tab)
- Rideable vehicle entity with a server-authoritative flight model and
  client-side prediction (smooth, low-latency)
- Falls under gravity when vacant; keeps its momentum if you bail out in motion

## Build

The build uses [RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle)
(the maintained 1.7.10 toolchain).

- **Java 8** is used as the compile/run toolchain (RFG can auto-provision it).
- Gradle runs via the wrapper (Gradle 8.8); use a JDK in the 8–21 range to launch it.

```sh
# set JAVA_HOME to a JDK that can run Gradle (8–21), then:
./gradlew build          # -> build/libs/mod_thx-<version>.jar  (the loadable mod)
./gradlew runClient      # dev client
./gradlew runServer      # dev dedicated server
```

The reobfuscated `mod_thx-<version>.jar` is the one to drop into
`.minecraft/mods/` of a **1.7.10 Forge** install. (The `-dev.jar` is deobfuscated
and only works inside the Gradle dev environment.)

## Crafting

```
I R I      I = iron block      R = redstone dust
W L G      W = any wood plank  L = leather
W W .      G = glass block
```

## Controls (while piloting)

| Input | Action |
|---|---|
| Mouse / look | steer (yaw) |
| `W` / `S` | pitch forward / back (fly forward / back) |
| `A` / `D` | roll left / right (strafe) |
| `Space` / `X` | throttle up (ascend) / down (descend) |
| Right-click | mount / dismount |
| `Shift` | dismount |
| Punch (left-click) | remove a parked helicopter |

## Layout

- `src/main/java/com/theoxylo/thx/` — the mod (entity, item, render, network, proxies)
- `src/main/resources/assets/thx/` — textures, lang, mcmod.info
- `blockbench/` — Blockbench design sources for the model (not used by the build)

## Credits

Original THX Helicopter Mod by **Theoxylo**. 1.7.10 Forge port maintained in this repo.
