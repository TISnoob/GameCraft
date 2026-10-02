plugins {
    base
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

val installableArtifacts = listOf(
    ":paper-core:jar",
    ":proxy-velocity:jar",
    ":proxy-bungeecord:jar",
    ":geyser-addon:jar",
    ":resource-packs:buildPacks",
)

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
    from(project(":resource-packs").layout.buildDirectory.dir("build/packs"))
}

tasks.named("build") {
    dependsOn("assembleDistribution")
}
