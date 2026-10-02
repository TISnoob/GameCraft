plugins {
    `java-library`
}

description = "Platform-neutral API for GameCraft game modules and integrations."

tasks.jar {
    archiveBaseName.set("gamecraft-api")
}
