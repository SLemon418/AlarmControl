plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.alarmcontrol.notifications"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

// Holds the PURE matching engine (CLAUDE.md §4/§6): input = notification snapshot + rules,
// output = a decision. No NotificationListenerService here (that thin shell lives in :app),
// and no Hilt plugin needed — matcher classes use constructor injection only.
dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
