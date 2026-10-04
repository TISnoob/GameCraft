# Game module development

This guide covers the module format used by the current GameCraft core and the modules in `games/`. Game code ships as a separate JAR and can be enabled or omitted by each server owner. There is no need to embed a game in `GameCraft.jar`.

## Existing games

| Module ID | Seats | Computer opponent | Current implementation |
| --- | ---: | --- | --- |
| `chess` | 2 | Yes | Chesslib generates and validates every legal move. Click a piece, then a glowing legal destination on the 3D board. A configured Stockfish executable is mandatory; engine failure ends the match without applying a substitute move. |
| `ludo` | 2–4 | Yes | Four tokens per seat, dice, captures, safe squares, home lanes, exact finish rolls. |
| `chinese-checkers` | 2–6 | Yes | Star board, single steps and chained hops. The bot uses a distance heuristic. |
| `checkers` | 2 | Yes | American checkers, compulsory captures, multi-jumps, kings and promotion. |
| `monopoly` | 2–6 | Yes | Lobby rules include movement, property purchases, rent, taxes, event cards and bankruptcy. See the known limits below. |
| `uno` | 2–10 | Yes | Number/action cards, wild colors, skip/reverse/draw effects, turn drawing and UNO calls. |
| `solitaire` | 1 | No | Klondike draw-one or draw-three with tableau and foundation moves. |
| `sudoku` | 1–9 | No | Generated valid 9×9 puzzle, difficulty-based hole count and optional shared-board multiplayer race. |

Each built-in module uses the shared `openMenu(...)` helper to publish the current board state and legal actions to the Fabric client renderer. Despite the historical helper name, it does not open a chest menu. The separate `/gc play` setup flow uses a chest menu to browse games, choose mode/difficulty/seats and create an inviteable room. The rules and board state live in the module; the core routes each board click as a normalized `GameAction`. Bots for games other than Chess are lightweight strategies written in the module/shared game code, not external game engines. They are usable opponents, not tournament-strength engines.

Monopoly is intentionally a smaller lobby ruleset: it does not yet implement houses/hotels, auctions, trades, mortgages, full jail turns, or all original Chance/Community Chest cards. It uses a turn limit and resolves winner by remaining players/net worth. UNO currently omits draw stacking and Wild Draw Four challenges. The Fabric client mod draws game-specific textured tabletops, 3D pieces and cards, legal targets, and short movement animations directly in the world. It uses neither Paper display entities nor gameplay chest menus. Active matches temporarily save and clear player inventories, block building, item use, damage, and ordinary entity interactions, then restore the saved inventory when the match ends.

## Repository layout

```text
gamecraft-api/                 stable API consumed by module code
games/game-engine-common/      shared module helpers and UCI adapter
games/gc-chess/                chess.jar
games/gc-ludo/                 ludo.jar
games/gc-chinese-checkers/     chinese-checkers.jar
games/gc-checkers/             checkers.jar
games/gc-monopoly/             monopoly.jar
games/gc-uno/                  uno.jar
games/gc-solitaire/            solitaire.jar
games/gc-sudoku/               sudoku.jar
```

Every module project contains a Gradle file, Java source, and this service-provider file:

```text
src/main/resources/META-INF/services/io.github.tis199.gamecraft.api.GameModule
```

That file contains the implementing class's fully qualified name. A module JAR must have a unique ID matching `[a-z][a-z0-9-]{1,31}` and that ID must match its service provider's `GameModuleDescriptor`.

## Build and package a module

Build one module:

```sh
./gradlew :games:gc-chess:jar
```

Its JAR is written to `games/gc-chess/build/libs/chess.jar`. Build all installable artifacts and copy them together:

```sh
./gradlew assembleDistribution
```

The distribution folder is `build/distributions/`. The API JAR is for compiling modules and is not installed on a server separately. Modules use `compileOnly(project(":gamecraft-api"))`; do not package duplicate `io.github.tis199.gamecraft.api` classes. Shade third-party runtime dependencies into that individual game's JAR. The Chess module shades Chesslib and shared module code; the other modules bundle the shared helper code they use.

To add a new module:

1. Create `games/gc-<id>/` with a Gradle Java project.
2. Add `include("games:gc-<id>")` in `settings.gradle.kts`.
3. Add `:games:gc-<id>:jar` to the root `installableArtifacts` list and add its destination JAR to `assembleDistribution` in `build.gradle.kts`.
4. Provide a `GameModule` implementation and ServiceLoader declaration.
5. If it has owner-editable defaults, package `src/main/resources/gamecraft-defaults.yml`.

## SPI lifecycle

Implement `io.github.tis199.gamecraft.api.GameModule`. The module manager discovers it with Java `ServiceLoader`, checks its ID and API version, creates its module data directory and YAML file, then calls:

1. `onLoad(GameModuleContext)` — receive configuration, data folder and services.
2. `onEnable()` — begin module services after load.
3. `onSessionStart(GameSession)` — create in-memory match state and show the first prompt.
4. `onPlayerAction(GameAction)` — handle an action routed from a board click or player control.
5. `onSessionEnd(GameSession)` — remove session state and release per-match resources.
6. `onDisable()` — close module-wide resources when the server stops.

The descriptor also declares `minPlayers()`, `maxPlayers()`, `supportedDifficulties()`, and `supportsComputer()`. The core checks these before it creates a session. A `GameSession` exposes human player UUIDs separately from `totalPlayers()`; seats after the human list are computer-controlled.

Typical module declaration:

```java
public final class ExampleModule extends AbstractGameModule {
    private final Map<UUID, Match> matches = new ConcurrentHashMap<>();

    @Override
    public GameModuleDescriptor descriptor() {
        return new GameModuleDescriptor("example", "Example", "0.1.0", 1,
                "A sample board game.");
    }

    @Override public int minPlayers() { return 2; }
    @Override public int maxPlayers() { return 4; }
    @Override public boolean supportsComputer() { return true; }
    @Override public List<String> supportedDifficulties() {
        return List.of("easy", "medium", "hard", "expert");
    }

    @Override
    public void onSessionStart(GameSession session) {
        matches.put(session.sessionId(), new Match(session.totalPlayers()));
        prompt(session);
    }

    @Override
    public void onPlayerAction(GameAction action) {
        GameSession session = services.sessions().getPlayerSession(action.playerId()).orElse(null);
        if (session == null || !session.moduleId().equals("example")) return;
        // Read a known action ID, validate it against the current turn and rules,
        // update the match state, then prompt or finish the game.
    }

    @Override
    public void onSessionEnd(GameSession session) {
        matches.remove(session.sessionId());
    }
}
```

The built-in modules extend `AbstractGameModule`, which supplies `services`, `openMenu`, `closeScene`, `tell`, `runBot`, `isHumanSeat`, and `humanAt`. A module may implement `GameModule` directly if it wants to manage these helpers itself.

## Menus and actions

Prepare a `List<MenuOption>` and call `openMenu(session, playerId, title, options, actionType)`. The helper sends the current board state and available actions to nearby GameCraft client mods. The client renders the board and maps clicks on board cells, cards, or pieces to an action ID; the core routes that ID as a `GameAction` in `data["choice"]`. Keep action IDs small and explicit (`roll`, `square:e2`, `draw`, `place:4:6`). Treat every action as untrusted input and validate turn, ownership, position, and legal moves again in the game model. Do not call `services.menus().open(...)` for gameplay.

The Paper core only schedules its own world/block operations through `GameScheduler`; board visuals and short moves are rendered locally by the Fabric client mod. Scene origins are the board-height center of a nearby GameCraft table. Java model assets are generated by `resource-packs/scripts/generate_java_packs.py`; after adding an asset, update `ModelIdCatalog` in `client-mods/fabric-1.21.1/`, run that script, and rebuild the packs and mod. Bedrock custom item mappings and Forms are not implemented yet. `GameModule.menu()` and `render()` remain renderer-neutral API hooks; the current board renderer is the Fabric Java client mod.

## Tables, chairs, and resource packs

The Paper core places real table slabs and chair stairs and protects their blocks during matches. The Fabric client mod renders a game-specific top, table legs, chairs, pieces, and cards over those physical blocks, including between matches. It does not use Paper `Display` or `Interaction` entities for gameplay. The administrator command `/gc furniture table <game-id>` chooses the matching footprint. Keep each game's board within that footprint and anchor it at `GameSession.origin()`. `/gc play` uses the nearest table for that module or places that game's real table five blocks in front of the host.

The generated combined Java packs are `gamecraft-java-legacy.zip`, `gamecraft-java-modern.zip`, and `gamecraft-java-universal.zip`. Each game also has standalone packs named `gamecraft-<game-id>-java-legacy.zip` and `gamecraft-<game-id>-java-modern.zip`. Extend `resource-packs/scripts/generate_java_packs.py` with unique model IDs and art, update `ModelIdCatalog`, regenerate assets with `./gradlew :resource-packs:generateJavaPacks`, then rebuild using `./gradlew assembleDistribution`. Do not reuse another game pack's model IDs. The Fabric client mod is currently required for in-world gameplay and bundles the models; the Java pack is optional for vanilla item appearances. Active match boards and pieces are visible to nearby modded Java clients; action handlers still validate that the clicker is in the session.

## Difficulty, players, and computer seats

The core passes the requested difficulty string into `GameSession.difficulty()` and the total seat count into `GameSession.totalPlayers()`. A multiplayer module may support any sensible seat range. `GameSession.properties()` carries launch options such as Ludo team mode. `human-seat-order` can map a computer-mode player's selected color/side to a nonzero seat; remaining seats are computer-controlled.

Commands use the form:

```text
/gc play
/gc invite <player>
/gc accept [host]
/gc start
/gc room
/gc cancel
```

The computer seat count includes the requesting player. For example, a four-seat expert match means one human and three computer opponents. Room seat count includes the host; the host invites players and starts after all selected seats are occupied. Current difficulty names are `easy`, `medium`, `hard`, and `expert`, with actual effects defined by each game.

## Configuration and persistence

The loader creates:

- `plugins/GameCraft/games/<id>.yml` — server-owner settings copied from `gamecraft-defaults.yml` on first load.
- `plugins/GameCraft/games/<id>/` — module-private data folder.

Read configuration through `ModuleConfiguration` (`getString`, `getBoolean`, `getInt`, and `getStringList`). Existing YAML files are not merged with changed defaults. When adding settings, use safe fallback values in code and document the new keys. Persist records with `services.storage()` using a module-owned namespace, or store private files below the module data folder. Storage returns asynchronous `CompletionStage`s; do not block a server thread on database work.

Module YAML is read when the module loads. Restart the server after changing game settings; `/gamecraft reload` reloads the core YAML and registry check but does not reload game classes or module settings.

## Scheduler and Folia rules

Use only API scheduler methods to choose the owning thread:

- `runAsync` for CPU work, HTTP, and file/database coordination.
- `runGlobal` for global server state that does not belong to one region.
- `runAt(GameLocation, ...)` for world/block operations at that location.
- `runAtLater(GameLocation, delayTicks, ...)` for delayed work on the region owning that location.
- `runForEntity(UUID, ...)` for player/entity reads and mutations; handle the retired callback.

Never hold or access a mutable Bukkit `Player`, `World`, `Entity`, or block across an async task. Keep game rules and state as ordinary Java data. When a bot or engine finishes off-thread, schedule the result back to the appropriate entity/global scheduler and validate that the session still exists and the move is still legal before applying it.

Furniture service operations route their world changes through region scheduling. The installed core declares Folia support, but check any new Paper API call against the lowest Paper line you support; avoid NMS and APIs added after the intended compatibility floor.

## Engines and optional AI

Chess uses [Chesslib](https://github.com/bhlangonijr/chesslib) for legal move generation and game results. It targets Stockfish 16 by default through the Universal Chess Interface (UCI). No Stockfish binary is included. Configure the chess module's `engine.path` to an installed compatible UCI executable, or set `engine.download-url` to a direct HTTPS binary URL and provide the exact file's `engine.download-sha256`. Downloads are size-limited and rejected unless the checksum matches. Engine `Threads`, `Hash`, timeout, and per-difficulty move time can be tuned in `plugins/GameCraft/games/chess.yml`; `max-threads` and `max-memory-mb` cap the configured UCI options. Do not point the downloader at an archive; provide a ready-to-run executable for the target server OS and CPU architecture.

Stockfish is GPLv3. If a server owner distributes a Stockfish binary, they must meet the license terms for that binary. The module does not bundle the engine. Stockfish is mandatory: Chess will not load when the configured executable is missing and no verified download URL is present. The configured binary must identify itself as Stockfish during the UCI handshake. Every proposed move is checked against Chesslib's legal moves. If Stockfish fails or returns an invalid move, the match ends without applying a substitute move. Stockfish 16 is the default target; an owner can use another Stockfish version or set a direct HTTPS binary URL with the exact SHA-256.

Other games currently use simple in-module computer strategies rather than separate third-party engines. For a new engine, implement a module-owned adapter to `LocalEngine`, run it asynchronously, and verify all proposed moves against the module's own rules before applying them.

The shared `AiService` is optional text generation for server-configured providers. It is not a rules engine and is disabled by default. If used, never put provider secrets in a module JAR, prompt, or log; treat output as untrusted and validate every proposed game action locally.

## Compatibility, checks, and release

Compile against the API JAR, keep third-party dependencies inside the module JAR, and declare the lowest `apiVersion` required by the code. Test module logic independently from Bukkit where possible, then try a clean server with the exact Paper/Folia version and Java runtime you claim to support. Include module loading, config creation, restart behavior, disabled/failed engine behavior, game actions, disconnect/forfeit cleanup, and multiplayer seat limits in your release checks.

The root release registry can distribute modules from HTTPS GitHub release assets. See [the manifest and release instructions](../module-release.md). Local modules can instead be copied into `plugins/GameCraft/modules/<id>.jar` and enabled in `modules.enabled-games`; module changes activate after restart.
