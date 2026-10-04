# Server setup

## Install GameCraft and game modules

1. Build the release with `./gradlew assembleDistribution`, or obtain the desired artifacts.
2. Copy `GameCraft.jar` into the Paper server's `plugins/` directory and start the server once.
3. Copy each selected game JAR into `plugins/GameCraft/modules/`.
4. On every Java player's client, install Fabric Loader and Fabric API for their Minecraft version and place the matching `gamecraft-client-<minecraft-version>-<version>.jar` in the client's `mods/` folder. Builds are provided for 1.20.1, 1.21.1, 1.21.11, 26.1.2, 26.2, and 26.3. GameCraft checks for the mod before allowing a player into a match.
5. Add the selected IDs to `modules.enabled-games` in `plugins/GameCraft/config.yml`:

   ```yaml
   modules:
     enabled-games:
       - chess
       - ludo
       - sudoku
   ```

6. Restart. The core generates each module's defaults at `plugins/GameCraft/games/<id>.yml`.

The client mod is required for gameplay. It provides the game-specific table surfaces, detailed pieces and cards, click targeting, legal-move markers, movement animation, and the UNO hand screen. The server places real table and chair blocks; the mod renders their game-specific tops and legs and renders pieces/cards on top. A custom registered block ID is not sent by Paper: the server blocks remain real placeable blocks while the client mod supplies their custom game appearance.

When a match starts, GameCraft saves and clears the player's inventory. The first eight hotbar slots become game controls: slot 1 forfeits, slot 2 offers a draw where supported or shows status, slot 3 opens the private hand for UNO or help for other games, and slots 4–8 expose current game actions. The saved inventory returns when the match ends.

The module IDs are `chess`, `ludo`, `chinese-checkers`, `checkers`, `monopoly`, `uno`, `solitaire`, and `sudoku`. Modules may also be downloaded from the configured registry. Registry downloads are checksum-verified and take effect after a restart. See [module release format](module-release.md).

GameCraft targets Paper 1.20.x, 1.21.x, and the 26.x lines using public APIs and Java 21 bytecode. The core compiles against Paper API 1.20.1, declares `api-version: '1.20'`, and avoids NMS. Build with JDK 25; run Paper 1.20/1.21 on Java 21 and 26.x on Java 25. Folia-aware work uses Paper's entity, region, global, and async schedulers. Each exact Paper/Folia version still needs a live server check before being declared production-verified.

## Commands

| Command | Purpose | Permission |
| --- | --- | --- |
| `/gamecraft help` | Show commands and game-start syntax | none |
| `/gamecraft status` | Show core/server version, loaded module count, and AI status | none |
| `/gamecraft modules` | List configured and loaded modules | none |
| `/gc play` | Open the chest menu to select a game, mode, player count, difficulty, and side/color | `gamecraft.use` |
| `/gc invite <player>` | Invite an online player to your waiting room | `gamecraft.use` |
| `/gc accept [host]` | Accept a pending room invitation | `gamecraft.use` |
| `/gc start` | Start your room after all selected seats join | `gamecraft.use` |
| `/gc room` | Show your room or pending invitation | `gamecraft.use` |
| `/gc cancel` | Close your room | `gamecraft.use` |
| `/gamecraft forfeit` | End your active match | `gamecraft.use` |
| `/gamecraft sit` / `/gamecraft stand` | Sit in a nearby GameCraft chair or stand | none |
| `/gc furniture chair [game]` | Place a chair above the targeted block | `gamecraft.admin` |
| `/gc furniture table <game>` | Place that game's custom-sized textured 3D table | `gamecraft.admin` |
| `/gamecraft enable <module-id>` / `disable <module-id>` | Enable/download or disable a module | `gamecraft.admin` |
| `/gamecraft reload` | Reload core config and check the registry | `gamecraft.admin` |

Difficulty values are `easy`, `medium`, `hard`, and `expert`. Each module decides how those settings affect its opponent or puzzle. Use the chest menu to pick the mode, player count, difficulty, and a side/color. A multiplayer host then invites friends with `/gc invite <player>` and starts once the selected room seats are full. The direct command form remains available for single player or computer matches, for example:

```text
/gamecraft play chess computer hard
/gamecraft play monopoly computer medium 4
```

The computer seat count includes you: `monopoly computer medium 4` is one human and three bots. The multiplayer flow is room-based so invited players can choose whether to join before the match begins. Ludo offers a four-player 2v2 team option.

`gamecraft.admin` defaults to operators. Furniture uses real table and stair blocks, with an invisible seat anchor for sitting. Right-click a table to open `/gc play`. Chairs can be right-clicked to sit on them; `/gc sit` is also available. `/gc play` uses a nearby table for the selected game within 16 blocks; if none is nearby, it places that game's table five blocks in front of the host. The client mod textures furniture between matches and draws active boards and pieces; only session members can make moves.

## In-world boards and client mod

Game scenes use textured 3D models, boards, pieces, clickable board cells, and animated piece movement. The Fabric mod is required for active game views. Right-click a board square, card, or space to choose the move or option you want. Chest menus are only used to browse games and configure a room; they do not replace the in-world board during play.

Place a matching table with `/gc furniture table uno`, `/gc furniture table chess`, and so on. Every game has a different footprint, top texture, and chair art. Players can sit around the table and start with `/gc play`; if they are near more than one table for that game, the nearest matching one is used.

The client mod bundles the game models and textures, so gameplay does not depend on the optional server resource pack. To serve that pack for vanilla item appearances, set a public URL in `plugins/GameCraft/config.yml`; for example:

```yaml
resource-pack:
  enabled: true
  required: false
  public-url: "http://games.example.net:8765/gamecraft.zip"
  host:
    enabled: true
    bind-address: 0.0.0.0
    port: 8765
```

Forward the configured port so clients can reach the game server. Use HTTPS through a reverse proxy where possible. The `required` setting controls only the optional server resource pack; it does not replace the required client mod.

The standalone ZIPs remain available for servers with external pack hosting:

- `gamecraft-java-legacy.zip` for Java 1.20 through 1.21.3 clients.
- `gamecraft-java-modern.zip` for Java 1.21.4 and later clients, including 26.x.
- `gamecraft-<game>-java-legacy.zip` and `gamecraft-<game>-java-modern.zip` for individual game packs.

`gamecraft-java-universal.zip` contains both legacy overrides and modern item definitions for versions from 1.20 through 26.3. It only provides optional vanilla item appearances; active gameplay still requires the matching Fabric client mod. Bedrock custom item mappings are still separate work; do not force the Java pack for a Geyser audience until its Bedrock pack is configured and verified.

## Chess engine setup

Chess is configured in `plugins/GameCraft/games/chess.yml`. By default it looks for a Stockfish 16 UCI executable at `plugins/GameCraft/games/chess/engines/stockfish-16`. An owner can put a compatible executable at that path or set `engine.path` to another local path. A direct HTTPS binary download can be set with `engine.download-url`; set the exact binary's `engine.download-sha256` too. The module rejects downloads without a matching SHA-256. It does not download anything unless a URL is configured.

Tune UCI `Threads`, `Hash`, timeout, and move time by difficulty in the same file. For example:

```yaml
engine:
  version: 16
  path: engines/stockfish-16
  download-url: ""
  download-sha256: ""
  timeout-ms: 15000
  max-threads: 2
  max-memory-mb: 128
  options:
    threads: 2
    hash-mb: 128
  difficulty:
    easy: { move-time-ms: 500 }
    medium: { move-time-ms: 1500 }
    hard: { move-time-ms: 3000 }
    expert: { move-time-ms: 5000 }
```

The effective `Threads` and `Hash` options cannot exceed the configured caps. Restart after editing `chess.yml` for changes to apply.

Install Stockfish (16 is the default target) for the server OS and CPU architecture. Chess is not loaded when the executable is missing and there is no random or substitute-move fallback. Every Stockfish result is checked against the legal moves generated by Chesslib; if Stockfish cannot start or returns an invalid move, that match ends safely without applying it. The server owner is responsible for complying with Stockfish's license when redistributing the engine.

## Core configuration

The generated `plugins/GameCraft/config.yml` contains module registry, storage, AI, resource pack, and proxy settings. SQLite is the default for one server. MariaDB can be configured for shared network storage. The generic AI text service is disabled by default; enabling it requires a supported provider key. It is not used by game bots in the current release.

PlaceholderAPI is optional. If installed, the core provides `%gamecraft_modules_loaded%`, `%gamecraft_player_sitting%`, `%gamecraft_ai_enabled%`, and `%gamecraft_active_games%`.

## Current platform limits

- Java gameplay requires the Fabric client mod. Build targets are Minecraft 1.20.1, 1.21.1, 1.21.11, 26.1.2, 26.2, and 26.3. Client support is tied to the listed targets; a Paper server line accepting a patch version does not by itself guarantee the client mod works on that patch. The Geyser extension is a scaffold; Bedrock custom-item mapping, Forms, and PE-specific menus are not ready.
- Velocity and BungeeCord artifacts are scaffolds and do not provide usable proxy routing yet.
- Optional Java packs include textured 3D game models. The Bedrock pack is still a starter output.
- The exact Paper/Folia version range has not been live-server verified as part of this build.
