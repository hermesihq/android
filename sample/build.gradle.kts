plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Firebase reads its project settings from google-services.json, which is yours and is not in this repository (see
// README.md). Without it the app still builds, which is what CI does, but cannot get a push token.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "io.github.hermesihq.push.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hermesihq.push.sample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // The library from this repository. An app of your own would use the JitPack coordinates in the main README.
    implementation(project(":hermesi-push"))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
}
