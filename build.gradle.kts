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

// ---------------------------------------------------------------------------
// Standalone helicopter model viewer (dev tool, not shipped in the mod).
//   gradlew runModelViewer
// Reuses the real Minecraft ModelRenderer from the dev classpath + LWJGL2
// natives that RFG already extracts for runClient.
// ---------------------------------------------------------------------------
val gatherViewerNatives by tasks.registering(Copy::class) {
    dependsOn("extractNatives2")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // RFG expands the LWJGL natives jar under build/tmp; runClient also leaves a copy under run/natives.
    from(fileTree(layout.buildDirectory.dir("tmp/.cache/expanded")) { include("**/*.dll") })
    from(fileTree("run/natives") { include("**/*.dll") })
    into(layout.buildDirectory.dir("viewer-natives"))
    eachFile { path = name } // flatten into one dir
    includeEmptyDirs = false
}

tasks.register<JavaExec>("runModelViewer") {
    group = "thx"
    description = "Standalone LWJGL viewer for the helicopter model (orbit/pan/zoom)."
    dependsOn("classes", gatherViewerNatives)
    mainClass.set("com.theoxylo.thx.client.viewer.ModelViewer")
    // RFG supplies the deobf Minecraft + LWJGL on the COMPILE classpath; include it
    // so DisplayMode/GL11/ModelRenderer resolve at runtime too.
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["main"].compileClasspath
    systemProperty("java.library.path", layout.buildDirectory.dir("viewer-natives").get().asFile.absolutePath)
    workingDir = projectDir // so the viewer's relative source-texture path resolves to the repo
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
}
