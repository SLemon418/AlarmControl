plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.alarmcontrol.ml"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The compact classifier is bundled in assets. The optional LLM is user-imported locally;
    // neither path can download a model (CLAUDE.md §3/§5).
    // Don't compress .tflite assets or memory-mapped model loading breaks.
    androidResources {
        noCompress += "tflite"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        managedDevices {
            allDevices {
                maybeCreate<com.android.build.api.dsl.ManagedVirtualDevice>("pixel2Api34").apply {
                    device = "Pixel 2"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)

    // Compact official LiteRT Interpreter runtime. Its classifier model ships in assets.
    implementation(libs.litert)

    // On-device generative LLM inference (Milestone 4). Runs a LOCAL model; no network (§3) — the
    // offline guard in :app enforces no INTERNET permission or networking client comes with it.
    implementation(libs.mediapipe.tasks.genai)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // Real org.json for parsing tests (production uses the Android platform's org.json).
    testImplementation(libs.json)

    // Instrumented tests: load and run the real bundled .tflite under the Android LiteRT runtime.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
