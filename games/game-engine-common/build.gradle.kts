plugins {
    `java-library`
}

description = "Common framework for GameCraft game modules."

dependencies {
    compileOnly(project(":gamecraft-api"))
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
}

tasks.jar {
    archiveBaseName.set("game-engine-common")
}
