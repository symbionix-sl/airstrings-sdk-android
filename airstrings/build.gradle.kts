import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// Phase 8.6 D-02 / catalog-free for sibling-projectDir consumers.
//
// When this module is consumed via Gradle's projectDir-override pattern
// (consumer's settings.gradle.kts does `rootProject.children + projectDir =
// file(...)` — NOT `includeBuild`), this build script is evaluated against
// the CONSUMER'S plugin classpath and version catalog. Typesafe `libs.X`
// accessors only resolve if the consumer's catalog declares matching
// aliases — rarely the case across separate projects.
//
// Using plain plugin IDs and explicit Maven coordinates keeps this module
// portable to any downstream consumer (BioPulse's Phase 8.6 sibling-projectDir
// Debug wiring is the originating use case). Versions remain pinned to the
// same values previously declared in gradle/libs.versions.toml.
plugins {
    id("com.android.library") version "9.1.1"
    // AGP 9 built-in Kotlin handles Android sources; the kotlin.android plugin
    // is no longer applied here. Matches BioPulse Phase 8.5 D-04/D-05.
}

android {
    namespace = "com.airstrings.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Declared explicitly so JitPack does not auto-inject a Groovy-style
    // `publishing { singleVariant('release') }` block — single-quoted Groovy
    // strings are character literals in Kotlin DSL and fail script compilation.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

// Pins JVM bytecode target to 17 via the modern compiler-options DSL
// (the legacy android { kotlin-options } block was removed in Kotlin 2.3 /
// AGP 9). Phase 8.6 D-09.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

kotlin {
    explicitApi()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")

    compileOnly(platform("androidx.compose:compose-bom:2024.12.01"))
    compileOnly("androidx.compose.runtime:runtime")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.3.0")
}
