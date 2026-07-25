plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.alarmcontrol.automation"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric needs merged Android resources for the JVM shortcut test.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Exported automation entry points (CLAUDE.md §7). ProfileToggleReceiver lets Samsung Modes &
// Routines (via Good Lock RoutinePlus) enable/disable filtering through a documented intent; it
// speaks only :core contracts, whose :data implementations persist the change and content-free
// audit. Hilt exposes those contracts through an EntryPoint. No networking — same offline rules.
dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
    // ShortcutManagerCompat / ShortcutInfoCompat / IconCompat for dynamic launcher shortcuts.
    implementation(libs.androidx.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
