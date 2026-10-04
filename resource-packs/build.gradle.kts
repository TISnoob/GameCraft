plugins {
    base
}

val packOutput = layout.buildDirectory.dir("packs")
val generateJavaPacks by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Regenerates the Java game furniture and card textures from the checked-in pixel-art source."
    workingDir = projectDir
    executable = providers.gradleProperty("pythonExecutable").orElse("python3").get()
    args("scripts/generate_java_packs.py")
}

val javaLegacyPack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages 3D game models for Java 1.20 through 1.21.3 clients."
    from(layout.projectDirectory.dir("src/main/packs/java-legacy"))
    archiveFileName.set("gamecraft-java-legacy.zip")
    destinationDirectory.set(packOutput)
    dependsOn(generateJavaPacks)
}

val javaModernPack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages 3D game models for Java 1.21.4 and later clients."
    from(layout.projectDirectory.dir("src/main/packs/java-modern"))
    archiveFileName.set("gamecraft-java-modern.zip")
    destinationDirectory.set(packOutput)
    dependsOn(generateJavaPacks)
}

val javaUniversalPack by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages both legacy and modern item definitions for automatic Java client delivery."
    from(layout.projectDirectory.dir("src/main/packs/java-universal"))
    archiveFileName.set("gamecraft-java-universal.zip")
    destinationDirectory.set(packOutput)
    dependsOn(generateJavaPacks)
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
    description = "Packages legacy and modern Java 3D assets plus the Bedrock base pack."
    dependsOn(javaLegacyPack, javaModernPack, javaUniversalPack, bedrockCorePack)
}

val gamePackNames = listOf("chess", "ludo", "chinese-checkers", "checkers", "monopoly", "uno", "solitaire", "sudoku")
for (variant in listOf("legacy", "modern")) {
    for (game in gamePackNames) {
        val gamePack = tasks.register<Zip>("${game.replace('-', '_')}Java${variant.replaceFirstChar { it.uppercase() }}Pack") {
            group = "distribution"
            description = "Packages the standalone ${game} ${variant} resource pack."
            from(layout.projectDirectory.dir("src/main/packs/game-specific/$variant/$game"))
            archiveFileName.set("gamecraft-$game-java-$variant.zip")
            destinationDirectory.set(packOutput)
            dependsOn(generateJavaPacks)
        }
        buildPacks.configure { dependsOn(gamePack) }
    }
}

tasks.assemble {
    dependsOn(buildPacks)
}

buildPacks.configure {
    doLast {
        project.delete(packOutput.get().file("gamecraft-java-core.zip"))
    }
}
