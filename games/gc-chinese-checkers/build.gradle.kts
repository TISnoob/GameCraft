plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":gamecraft-api"))
    implementation(project(":games:game-engine-common"))
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
}

tasks.jar {
    archiveFileName.set("chinese-checkers.jar")
    dependsOn(":games:game-engine-common:jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
