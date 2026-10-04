# GameCraft documentation

GameCraft is a Paper/Folia lobby-games platform. The core is installed separately from optional game modules.

- [Server setup, commands, game installation, and configuration](server-setup.md)
- [Build and artifact guide](build.md)
- [Game-module developer guide](game-development/README.md)
- [Module release registry and checksums](module-release.md)
- [Third-party libraries and engine licenses](third-party.md)
- [Third-party asset and license notice](../resource-packs/src/main/licenses/WannaPlayChess-MIT.txt)

## Current scope

The repository includes separate source/build modules for Chess, Ludo, Chinese Checkers, Checkers, Monopoly, UNO, Solitaire, and Sudoku. `assembleDistribution` emits a separate JAR for each. The core does not embed these games; install each selected module JAR under `plugins/GameCraft/modules/` and enable its ID in `config.yml`.

The Paper core loads modules, routes game actions, manages storage and optional PlaceholderAPI integration, and places real table and chair blocks. In-world 3D gameplay is rendered by a required version-specific Fabric client mod. Builds target Minecraft Java 1.20.1, 1.21.1, 1.21.11, 26.1.2, 26.2, and 26.3; each bundles custom models and textures, handles board clicks, animates pieces, and provides the UNO hand screen. Setup uses an inventory menu; gameplay stays on the physical table. Player inventories are saved, cleared and restored around each match. Chess uses Chesslib for legal moves and requires a configured Stockfish executable. The other computer opponents use lightweight module strategies.

The optional Java resource packs target vanilla item models across legacy and modern versions. Install the Fabric build matching the player's exact client version; server and client version support are separate. Bedrock custom-item mapping, Geyser Forms, and proxy routing remain scaffolds. See the build and server guides for installation and platform limits.
