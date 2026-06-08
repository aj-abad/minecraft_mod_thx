// ============================================================================
// THX Helicopter Mod - Minecraft Forge 1.7.10 (ported from 1.6.1 ModLoader/MCP)
// ----------------------------------------------------------------------------
// Toolchain: RetroFuturaGradle (maintained 1.7.10 toolchain) + Gradle 8.8.
//   * Gradle itself runs on Java 17 (JAVA_HOME).
//   * The mod + Minecraft are compiled/run with the Java 8 toolchain below
//     (RFG decouples the two).
//
// Common tasks:
//   gradlew build       - compile + reobfuscate the mod jar
//   gradlew runClient   - launch a dev client with the mod loaded
//   gradlew runServer   - launch a dev dedicated server
// ============================================================================

plugins {
    id("java")
    id("com.gtnewhorizons.retrofuturagradle") version "1.4.0"
}

group = "com.theoxylo.thx"
version = "0.26.0"

base {
    archivesName.set("mod_thx")
}

java {
    toolchain {
        // Compile/run the mod with Java 8 regardless of the JDK running Gradle.
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

// RFG: configures the deobfuscated Forge 1.7.10 dev environment.
minecraft {
    mcVersion.set("1.7.10")
    username.set("Developer")
}

// Inject version + mcversion into mcmod.info at build time.
tasks.processResources.configure {
    val projVersion = project.version.toString()
    inputs.property("version", projVersion)
    inputs.property("mcversion", "1.7.10")

    filesMatching("mcmod.info") {
        expand(mapOf("version" to projVersion, "mcversion" to "1.7.10"))
    }
}
