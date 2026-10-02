plugins {
    java
}

description = "BungeeCord companion for GameCraft proxy messaging."

dependencies {
    compileOnly("net.md-5:bungeecord-api:1.21-R0.3-SNAPSHOT")
}

tasks.jar {
    archiveFileName.set("gc-bungeecord.jar")
}
