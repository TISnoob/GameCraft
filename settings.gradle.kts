pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.md-5.net/content/repositories/snapshots/")
        maven("https://repo.opencollab.dev/main/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
}

rootProject.name = "GameCraft"

include("gamecraft-api")
include("paper-core")
include("proxy-velocity")
include("proxy-bungeecord")
include("geyser-addon")
include("resource-packs")
