plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.airstrings.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "11"
    }

    @Suppress("UnstableApiUsage")
    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
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
