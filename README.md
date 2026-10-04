# GameCraft

GameCraft is a lobby-games platform for Paper and Folia servers. The core provides shared game sessions, animated in-world 3D boards and pieces, game-specific tables and chairs, storage, optional integrations, and a versioned API. `/gc play` opens a chest menu for game and room setup; active gameplay stays in the world, where players click pieces and controls.

The repository currently contains separate modules for Chess, Ludo, Chinese Checkers, Checkers, Monopoly, UNO, Solitaire, and Sudoku. The core JAR does not embed those games. The distribution build produces one JAR per game alongside the core and companion artifacts.

In-world gameplay uses a required Fabric client mod. Separate builds target Minecraft Java 1.20.1, 1.21.1, 1.21.11, 26.1.2, 26.2, and 26.3. Each mod bundles game furniture and piece models, board textures, click handling, move animation, and the UNO hand screen. Install the build matching the player's Minecraft version; a server resource pack does not replace the mod.

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
- `client-mods/fabric-*/` — version-specific Fabric gameplay mods and bundled 3D assets
- `modules/manifest.json` — release registry for downloadable game modules

Chess uses Chesslib for legal moves and supports Stockfish 16 through UCI. Stockfish is not bundled; the server owner installs an executable or configures a verified HTTPS download. The other modules currently provide their own rules and lightweight computer-player strategies. See the developer guide for each game's current scope and known ruleset limits.
