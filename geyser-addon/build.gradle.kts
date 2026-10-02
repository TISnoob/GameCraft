plugins {
    java
}

description = "Geyser extension for Bedrock Forms and resource-pack bridging."

dependencies {
    compileOnly("org.geysermc.geyser:api:2.11.2-SNAPSHOT")
}

tasks.processResources {
    filesMatching("extension.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("gc-geyser-addon.jar")
}
