plugins {
    base
}

val packOutput = layout.buildDirectory.dir("packs")

val javaCorePack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the GameCraft Java Edition core resource pack."
    from(layout.projectDirectory.dir("src/main/packs/java"))
    archiveFileName.set("gamecraft-java-core.zip")
    destinationDirectory.set(packOutput)
}

val bedrockCorePack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the GameCraft Bedrock core resource pack for Geyser."
    from(layout.projectDirectory.dir("src/main/packs/bedrock"))
    archiveFileName.set("gamecraft-bedrock-core.mcpack")
    destinationDirectory.set(packOutput)
}

val buildPacks by tasks.registering {
    group = "distribution"
    description = "Packages Java and Bedrock GameCraft core resource packs."
    dependsOn(javaCorePack, bedrockCorePack)
}

tasks.assemble {
    dependsOn(buildPacks)
}
