plugins {
    java
}

description = "Velocity companion for GameCraft proxy messaging."

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:4.2.1-SNAPSHOT")
}

tasks.jar {
    archiveFileName.set("gc-velocity.jar")
}
