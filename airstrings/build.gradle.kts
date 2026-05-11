import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.android.library)
    // AGP 9 built-in Kotlin handles Android sources; the kotlin.android plugin
    // alias is no longer consumed here. Matches BioPulse Phase 8.5 D-04/D-05.
    // The catalog still declares the alias (upstream D-10) for other consumers.
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
    implementation(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.common)

    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.runtime)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.kotlin.test.junit5)
}
