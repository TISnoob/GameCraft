plugins {
    java
}

description = "Paper/Folia server-side GameCraft core."

dependencies {
    implementation(project(":gamecraft-api"))
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.4")
}

tasks.jar {
    archiveFileName.set("GameCraft.jar")
    dependsOn(":gamecraft-api:jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { dependency ->
        if (dependency.isDirectory) dependency else zipTree(dependency)
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
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
