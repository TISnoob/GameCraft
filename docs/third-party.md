# Third-party libraries and engines

## Chess

- [Chesslib](https://github.com/bhlangonijr/chesslib) provides move generation and chess-state validation for `chess.jar`. It is distributed under Apache License 2.0. Its license and notice files are included in the shaded Chess JAR.
- [Stockfish](https://github.com/official-stockfish/Stockfish) is the default UCI engine target, but no executable is bundled. Stockfish is GPLv3. Server owners who distribute a Stockfish executable must follow the upstream license terms for that binary.

Stockfish 16 is the default target, and the configured executable must identify itself as Stockfish during the UCI handshake. The version and path are server-owner settings. A server owner distributing the executable must follow Stockfish's license terms.

## Other games

The current Ludo, Chinese Checkers, Checkers, Monopoly, UNO, Solitaire, and Sudoku rules and lightweight opponents are implemented in this repository. They do not depend on an external game engine. New contributors should include the license and notices for any added library or engine in the module's packaged JAR and document the upstream source here.

## Design references

- [ChessCraft](https://github.com/jpenilla/chesscraft) was consulted for in-world chess presentation and engine configuration ideas. Its source is Apache-2.0 licensed. No ChessCraft source files or assets are included.
- The 12 white and black 3D chess-piece models in the resource packs and Fabric client are based on [WannaPlayChess? by FieldB0y](https://github.com/Field-Boy/WannaPlayChess). They are distributed under MIT, with attribution and the license text packaged in each Java pack and in the client JAR at `META-INF/NOTICE`. The upstream repository metadata declares MIT, and the project owner separately authorized GameCraft reuse.
- The local `PlayingCards-1.16.4-main` source was consulted as a reference for physical card objects. It is GPL-3.0 licensed.

Card and furniture textures are generated from GameCraft's own pixel-art source. The third-party model notice is under `resource-packs/src/main/licenses/`.
