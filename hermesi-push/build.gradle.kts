plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

group = "io.github.hermesihq"
version = "0.1.0"

android {
    namespace = "io.github.hermesihq.push"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // `android.util.Log` and friends return defaults under the unit-test JVM instead of throwing.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // The app brings its own Firebase Messaging; this module only compiles against it, so it can
    // never pull in a version that disagrees with the one the app already uses.
    compileOnly(libs.firebase.messaging)

    testImplementation(libs.junit)
    // The tests build a Firebase RemoteMessage, which the library itself only compiles against.
    testImplementation(libs.firebase.messaging)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    // The Android platform ships `org.json`; the unit-test JVM does not.
    testImplementation(libs.org.json)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.hermesihq"
                artifactId = "hermesi-push"
                version = project.version.toString()
                pom {
                    name.set("Hermesi Push for Android")
                    description.set("Register an Android device for push notifications sent by Hermesi.")
                    url.set("https://github.com/hermesihq/android")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    scm {
                        url.set("https://github.com/hermesihq/android")
                        connection.set("scm:git:https://github.com/hermesihq/android.git")
                    }
                }
            }
        }
    }
}
