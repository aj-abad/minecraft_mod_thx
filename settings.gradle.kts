rootProject.name = "mod_thx"

pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle + GTNH artifacts
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
                includeGroup("com.gtnewhorizons")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    // Lets Gradle auto-provision the Java 8 toolchain if it isn't installed
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}
