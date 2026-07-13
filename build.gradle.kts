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
    `maven-publish`
    signing
}

group = "com.visorcraft"
version = "0.52.2"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The native embedded mode (JNI) has no declared build dependency.
    // NativeDB loads libmongreldb_jni at runtime via NativeLoader, which
    // searches MONGRELDB_NATIVE_DIR, java.library.path, or the classpath
    // (for the com.visorcraft:mongreldb-jni fat JAR). Consumers add the
    // JAR to their own project when they want native mode.
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
                "Automatic-Module-Name" to "com.visorcraft.mongreldb",
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

// ── Maven Central publishing ───────────────────────────────────────────────
// The signing plugin only signs when the publishing task runs (CI only), and
// only when an in-memory signing key is present in CI. The emptyJavadocJar
// satisfies Maven Central's javadoc
// requirement for Kotlin libraries (Kotlin consumers use -sources instead).
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

artifacts {
    archives(javadocJar)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(javadocJar)

            pom {
                name.set("MongrelDB Kotlin Client")
                description.set(
                    "Pure-Kotlin HTTP client for MongrelDB - typed CRUD, a fluent query " +
                    "builder that pushes conditions down to native indexes, idempotent " +
                    "batch transactions, full SQL access, and schema introspection, all " +
                    "over java.net.HttpURLConnection. No external runtime dependencies."
                )
                url.set("https://github.com/visorcraft/MongrelDB-Kotlin")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("Visorcraft")
                        email.set("support@visorcraft.com")
                        organization.set("Visorcraft")
                        organizationUrl.set("https://www.visorcraft.com")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/visorcraft/MongrelDB-Kotlin.git")
                    developerConnection.set("scm:git:https://github.com/visorcraft/MongrelDB-Kotlin.git")
                    url.set("https://github.com/visorcraft/MongrelDB-Kotlin")
                }
            }
        }
    }

    // Central Publishing Portal staging API (OSSRH replacement). Credentials
    // injected via ORG_GRADLE_PROJECT_* env vars in CI. The `publish` task
    // uploads to the staging repository; a follow-up API call in the release
    // workflow converts the staging repo to a deployment and publishes it.
    repositories {
        maven {
            name = "central"
            url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
            credentials {
                username = findProperty("mavenCentralUsername") as String?
                password = findProperty("mavenCentralPassword") as String?
            }
        }
    }
}

signing {
    // Only sign when credentials are present (CI); local dev builds skip it.
    val signingKey = findProperty("signingKey") as String?
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, findProperty("signingPassword") as String?)
    }
    isRequired = gradle.startParameter.taskNames.any { it.contains("publish") }
    sign(publishing.publications["maven"])
}

