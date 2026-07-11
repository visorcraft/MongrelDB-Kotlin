// MongrelDB Kotlin client build script.
//
// Pure Kotlin/JVM HTTP client for a running mongreldb-server daemon. The
// project deliberately has no external runtime dependencies: only the Kotlin
// standard library (shaded into the JVM target) and the JDK's
// java.net.HttpURLConnection for the transport.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.21"
    `java-library`
}

group = "dev.visorcraft"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Produce JVM 11 bytecode so the artifact runs on Java 11 or newer, without
// requiring a separate JDK 11 toolchain install at build time.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Implementation-Title" to "MongrelDB Kotlin Client",
                "Implementation-Version" to project.version,
                "Implementation-Vendor" to "Visorcraft",
                "Automatic-Module-Name" to "dev.visorcraft.mongreldb",
            ),
        )
    }
}

// The runnable examples live in examples/ under package com.example and import
// the client from the main source set. Exposing them as a dedicated `examples`
// source set lets the Kotlin Gradle plugin register a compileExamplesKotlin
// task so CI can at least type-check them (running them needs a daemon + runtime
// classpath, which CI does not provide). The set has no tests of its own and
// contributes no code to the published artifact.
sourceSets {
    create("examples") {
        java.srcDir("examples")
        // Compile and run against the client (main) classes.
        compileClasspath += sourceSets.getByName("main").output
        runtimeClasspath += sourceSets.getByName("main").output
    }
}

// Wire the examples compilation into the build lifecycle so a broken example
// fails `check`. Keep it JVM 11 to match the main artifact.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileExamplesKotlin") {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
}

tasks.named("check") {
    dependsOn("compileExamplesKotlin")
}



