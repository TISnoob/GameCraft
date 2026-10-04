# Build and install artifacts

## Requirements

- JDK 25 to run Gradle and build the distribution.
- Gradle is provided by the checked-in wrapper (`./gradlew`).
- Java sources compile to Java 21 bytecode and use public Paper APIs, without NMS.

## Build the distribution

```sh
./gradlew assembleDistribution
```

This creates separate artifacts in `build/distributions/`:

- `GameCraft.jar` — Paper/Folia core.
- `gamecraft-client-1.21.1-<version>.jar` — required Fabric client mod with bundled board/piece textures, click handling, movement animation, and the UNO hand screen. It targets Minecraft Java 1.21.1.
- `gamecraft-api-<version>.jar` — compile-time API for module developers; do not put it in the server's `plugins/` directory.
- `chess.jar`, `ludo.jar`, `chinese-checkers.jar`, `checkers.jar`, `monopoly.jar`, `uno.jar`, `solitaire.jar`, `sudoku.jar` — separate, shaded game modules. These JARs include their runtime game libraries where needed, but not duplicate GameCraft API classes.
- `gc-velocity.jar`, `gc-bungeecord.jar`, and `gc-geyser-addon.jar` — project scaffolds. They are not yet usable proxy plugins or a Forms bridge.
- `gamecraft-java-legacy.zip` — Java 1.20 through 1.21.3 board textures and 3D models.
- `gamecraft-java-modern.zip` — Java 1.21.4 and later board textures and 3D models.
- `gamecraft-java-universal.zip` — both model formats in one pack for Java 1.20 through 26.3. This pack is also embedded in `GameCraft.jar` and offered to clients automatically when `resource-pack.public-url` is configured.
- `gamecraft-<game>-java-legacy.zip` and `gamecraft-<game>-java-modern.zip` — standalone packs for Chess, Ludo, Chinese Checkers, Checkers, Monopoly, UNO, Solitaire, and Sudoku.
- `gamecraft-bedrock-core.mcpack` — Bedrock base pack. Custom Geyser item mappings are not implemented yet.

The original per-project outputs remain under each project's `build/libs/` directory. Resource-pack outputs are under `resource-packs/build/packs/`.

## Install the core and selected games

1. Copy `GameCraft.jar` to the Paper server's `plugins/` folder and start once.
2. Copy each selected game JAR to `plugins/GameCraft/modules/`.
3. Add each module ID to `modules.enabled-games` in `plugins/GameCraft/config.yml`, for example:

   ```yaml
   modules:
     enabled-games:
       - chess
       - ludo
       - sudoku
   ```

4. Restart. GameCraft copies module defaults to `plugins/GameCraft/games/<id>.yml` and the module is ready to use.
5. Each Java player installs Fabric Loader and Fabric API for Minecraft 1.21.1, then puts `gamecraft-client-1.21.1-<version>.jar` in the local `.minecraft/mods/` folder.

Each game module is optional and independent. Modules installed this way are loaded from the local module directory; the GitHub manifest is for downloading published modules and does not need to contain locally built modules.

## Build individual artifacts

```sh
./gradlew :paper-core:jar
./gradlew :gamecraft-api:jar
./gradlew :games:gc-chess:jar
./gradlew :games:gc-ludo:jar
./gradlew :games:gc-chinese-checkers:jar
./gradlew :games:gc-checkers:jar
./gradlew :games:gc-monopoly:jar
./gradlew :games:gc-uno:jar
./gradlew :games:gc-solitaire:jar
./gradlew :games:gc-sudoku:jar
./gradlew :proxy-velocity:jar
./gradlew :proxy-bungeecord:jar
./gradlew :geyser-addon:jar
./gradlew :client-mods:fabric-1.21.1:remapJar
./gradlew :resource-packs:buildPacks
```

## Compatibility notes

The core declares Paper API version 1.20 and compiles against Paper 1.20.1, targeting Java 21 bytecode. The plugin avoids NMS and uses Paper's public scheduler interfaces for Folia-aware work. Run the server on a supported Java runtime: Java 21 for older Paper lines and Java 25 for 26.x. Builds do not prove runtime compatibility across every Paper patch line, so deploy against the exact Paper/Folia version you operate and report incompatibilities with that version and startup log.

The combined Java packs contain all game models; the standalone packs contain only one game's cards/pieces and matching table/chair art. The pack generator uses Python 3 with Pillow (`python3 -m pip install Pillow` if needed). In-world gameplay requires the Fabric client mod, which bundles the render assets. The optional server resource pack only affects vanilla item appearances. Bedrock custom item mappings remain incomplete.

The Velocity, BungeeCord, and Geyser artifacts are included for project structure and ongoing integration work; only the Paper core and individual game modules are usable as gameplay components today.
