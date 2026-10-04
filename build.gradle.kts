plugins {
    base
    id("com.gradleup.shadow") version "9.3.1" apply false
}

group = "io.github.tis199.gamecraft"
version = providers.gradleProperty("gamecraftVersion").orElse("0.1.0-SNAPSHOT").get()

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
            withSourcesJar()
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(21)
        }
        tasks.withType<Jar>().configureEach {
            manifest.attributes["Implementation-Title"] = project.name
            manifest.attributes["Implementation-Version"] = project.version
        }
    }
}

allprojects {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.md-5.net/content/repositories/snapshots/")
        maven("https://repo.opencollab.dev/main/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        maven("https://jitpack.io")
        maven("https://maven.fabricmc.net/")
    }
}

val installableArtifacts = listOf(
    ":paper-core:jar",
    ":proxy-velocity:jar",
    ":proxy-bungeecord:jar",
    ":geyser-addon:jar",
    ":resource-packs:buildPacks",
    ":client-mods:fabric-1.21.1:remapJar",
    ":games:gc-chess:jar",
    ":games:gc-ludo:jar",
    ":games:gc-chinese-checkers:jar",
    ":games:gc-checkers:jar",
    ":games:gc-monopoly:jar",
    ":games:gc-uno:jar",
    ":games:gc-solitaire:jar",
    ":games:gc-sudoku:jar",
    ":client-mods:fabric-1.20.1:remapJar",
    ":client-mods:fabric-1.21.1:remapJar",
    ":client-mods:fabric-1.21.11:remapJar",
    ":client-mods:fabric-26.1.2:jar",
    ":client-mods:fabric-26.2:jar",
    ":client-mods:fabric-26.3:jar",
)

val clientModTargets = listOf("1.20.1", "1.21.1", "1.21.11", "26.1.2", "26.2", "26.3")
val clientModTasks = clientModTargets.map { mc ->
    if (mc.startsWith("26.")) ":client-mods:fabric-$mc:jar" else ":client-mods:fabric-$mc:remapJar"
}

tasks.register<Sync>("assembleClientMods") {
    group = "build"
    description = "Builds all version-specific GameCraft Fabric client mods into build/client-mods."
    dependsOn(clientModTasks)
    into(layout.buildDirectory.dir("client-mods"))
}

tasks.register<Sync>("assembleDistribution") {
    group = "distribution"
    description = "Builds the GameCraft core, proxy companions, Geyser extension, developer API, and resource packs."
    dependsOn(installableArtifacts)
    dependsOn(":gamecraft-api:jar")
    into(layout.buildDirectory.dir("distributions"))

    from(project(":paper-core").layout.buildDirectory.file("libs/GameCraft.jar"))
    from(project(":gamecraft-api").layout.buildDirectory.file("libs/gamecraft-api-${project.version}.jar"))
    from(project(":proxy-velocity").layout.buildDirectory.file("libs/gc-velocity.jar"))
    from(project(":proxy-bungeecord").layout.buildDirectory.file("libs/gc-bungeecord.jar"))
    from(project(":geyser-addon").layout.buildDirectory.file("libs/gc-geyser-addon.jar"))
    from(project(":games:gc-chess").layout.buildDirectory.file("libs/chess.jar"))
    from(project(":games:gc-ludo").layout.buildDirectory.file("libs/ludo.jar"))
    from(project(":games:gc-chinese-checkers").layout.buildDirectory.file("libs/chinese-checkers.jar"))
    from(project(":games:gc-checkers").layout.buildDirectory.file("libs/checkers.jar"))
    from(project(":games:gc-monopoly").layout.buildDirectory.file("libs/monopoly.jar"))
    from(project(":games:gc-uno").layout.buildDirectory.file("libs/uno.jar"))
    from(project(":games:gc-solitaire").layout.buildDirectory.file("libs/solitaire.jar"))
    from(project(":games:gc-sudoku").layout.buildDirectory.file("libs/sudoku.jar"))
    from(project(":resource-packs").layout.buildDirectory.dir("packs")) {
        include("gamecraft-java-legacy.zip", "gamecraft-java-modern.zip", "gamecraft-bedrock-core.mcpack",
            "gamecraft-java-universal.zip", "gamecraft-*-java-legacy.zip", "gamecraft-*-java-modern.zip")
    }
}

tasks.named("build") {
    dependsOn("assembleDistribution")
}

// Resolve the version-specific archive tasks only after Loom has created them. Using the
// task outputs instead of the whole libs directory prevents sources JARs and avoids Gradle
// treating unrelated directory-producing tasks as implicit inputs to the distribution copy.
gradle.projectsEvaluated {
    val clientArtifactTasks = clientModTargets.map { mc ->
        val clientProject = project(":client-mods:fabric-$mc")
        clientProject.tasks.getByName(if (mc.startsWith("26.")) "jar" else "remapJar")
    }

    tasks.named<Sync>("assembleClientMods") {
        dependsOn(clientArtifactTasks)
        clientArtifactTasks.forEach { from(it.outputs.files) }
    }
    tasks.named<Sync>("assembleDistribution") {
        dependsOn(clientArtifactTasks)
        clientArtifactTasks.forEach { from(it.outputs.files) }
    }
}
