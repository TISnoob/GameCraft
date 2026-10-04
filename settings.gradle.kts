pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}

dependencyResolutionManagement {
    // Fabric Loom registers a local remapped-mod repository on the client project.
    // Allow project repositories and declare the normal dependency repositories in the root build.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.md-5.net/content/repositories/snapshots/")
        maven("https://repo.opencollab.dev/main/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        maven("https://jitpack.io")
    }
}

rootProject.name = "GameCraft"

include("gamecraft-api")
include("paper-core")
include("proxy-velocity")
include("proxy-bungeecord")
include("geyser-addon")
include("resource-packs")

include("games:game-engine-common")
include("games:gc-chess")
include("games:gc-checkers")
include("games:gc-ludo")
include("games:gc-chinese-checkers")
include("games:gc-monopoly")
include("games:gc-uno")
include("games:gc-solitaire")
include("games:gc-sudoku")

// Client builds are split by Minecraft's rendering/networking ABI. 26.x uses
// unobfuscated Mojang names and a separate Loom plugin, so it cannot reuse the
// legacy 1.21.x binary.
listOf("1.20.1", "1.21.1", "1.21.11", "26.1.2", "26.2", "26.3").forEach { mc ->
    val name = "fabric-$mc"
    include("client-mods:$name")
    project(":client-mods:$name").projectDir = file("client-mods/$name")
}
