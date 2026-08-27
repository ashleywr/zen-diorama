# Zen Diorama

Zen Diorama is a Fabric and NeoForge mod for Minecraft 1.21.1 that builds miniature diorama worlds inside decorative frame blocks and heightmap placed in-world maps.

## Release

- Version: `1.0.1`
- Minecraft: `1.21.1`
- Loaders: Fabric and NeoForge `21.1.220`

## Features

- Diorama frame blocks and items
- A dedicated diorama dimension and return flow
- World map blocks for miniature terrain presentation
- Client rendering for miniature blocks, lighting, and sky effects
- Deterministic plot allocation and snapshot sampling logic

## Install

1. Install Minecraft `1.21.1` with either Fabric Loader (and Fabric API) or NeoForge `21.1.220`.
2. Download the matching Fabric or NeoForge release jar for `Zen Diorama` version `1.0.1`.
3. Place the jar in your `mods` folder.
4. Launch the game.

## Build From Source

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

## Notes

- The project targets Java 21.
- See [CHANGELOG.md](CHANGELOG.md) for release notes.
