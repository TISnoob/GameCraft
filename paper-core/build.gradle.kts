plugins {
    java
    id("com.gradleup.shadow")
}

description = "Paper/Folia server-side GameCraft core."

dependencies {
    implementation(project(":gamecraft-api"))
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("org.bstats:bstats-bukkit:3.2.1")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.4")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveFileName.set("GameCraft.jar")
    configurations = project.configurations.runtimeClasspath.map { setOf(it) }
    dependencies {
        exclude { dependency -> dependency.moduleGroup != "org.bstats" }
    }
    // The server plugin loads the platform-neutral API from its own classloader;
    // retain those project classes while filtering all non-bStats external libraries.
    from(project(":gamecraft-api").layout.buildDirectory.dir("classes/java/main"))
    dependsOn(":gamecraft-api:classes")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // bStats documents relocation for shaded integrations so another plugin's bStats
    // classes cannot collide with this plugin's copy.
    relocate("org.bstats", "io.github.tis199.gamecraft.internal.bstats")
}

// Keep the existing :paper-core:jar entry point useful while the shaded artifact is
// produced by Shadow's dedicated task.
tasks.named<Jar>("jar") {
    dependsOn("shadowJar")
}

tasks.processResources {
    dependsOn(":resource-packs:javaUniversalPack")
    from(rootProject.file("resource-packs/build/packs/gamecraft-java-universal.zip")) {
        into("packs")
    }
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
