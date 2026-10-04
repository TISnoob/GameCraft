# GameCraft

GameCraft is a lobby-games platform for Paper and Folia servers. The core provides shared game sessions, animated in-world 3D boards and pieces, game-specific tables and chairs, storage, optional integrations, and a versioned API. `/gc play` opens a chest menu for game and room setup; active gameplay stays in the world, where players click pieces and controls.

The repository currently contains separate modules for Chess, Ludo, Chinese Checkers, Checkers, Monopoly, UNO, Solitaire, and Sudoku. The core JAR does not embed those games. The distribution build produces one JAR per game alongside the core and companion artifacts.

In-world gameplay uses the required Fabric client mod for Minecraft Java 1.21.1. It bundles the board textures and detailed pieces, draws the game on physical tables, handles clicks, animates moves, and opens the UNO hand screen. Optional Java resource packs provide vanilla item appearances for other client versions; they do not replace the gameplay mod.

## Build

Build the distribution with JDK 25:

```sh
./gradlew assembleDistribution
```

Artifacts are copied to `build/distributions/`. For installing a game, see [the build and install guide](docs/build.md); for adding one, see [the game-module developer guide](docs/game-development/README.md).

## Documentation

- [Documentation index](docs/README.md)
- [Server setup, modules, and commands](docs/server-setup.md)
- [Build and artifact guide](docs/build.md)
- [Game module development](docs/game-development/README.md)
- [Module release manifest](docs/module-release.md)

## Project areas

- `paper-core/` — Paper/Folia plugin source
- `gamecraft-api/` — stable Java API shared with game modules
- `games/` — separately built game modules and shared game code
- `proxy-velocity/`, `proxy-bungeecord/`, `geyser-addon/` — companion project scaffolds
- `resource-packs/` — starter Java and Bedrock pack projects
- `client-mods/fabric-1.21.1/` — client renderer, board interaction, and bundled 3D assets
- `modules/manifest.json` — release registry for downloadable game modules

Chess uses Chesslib for legal moves and supports Stockfish 16 through UCI. Stockfish is not bundled; the server owner installs an executable or configures a verified HTTPS download. The other modules currently provide their own rules and lightweight computer-player strategies. See the developer guide for each game's current scope and known ruleset limits.
