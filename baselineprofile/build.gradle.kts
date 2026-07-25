plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.alarmcontrol.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

baselineProfile {
    useConnectedDevices = true
}

// The test-only Android plugin wires its aggregate `assemble` task to profile collection. Keep
// ordinary root builds device-independent while preserving explicit generate/collect/connected
// commands for real profile generation and benchmarks.
val deviceRunExplicitlyRequested =
    provider {
        gradle.startParameter.taskNames.any { requestedTask ->
            requestedTask.contains("generateBaselineProfile", ignoreCase = true) ||
                requestedTask.contains("collect", ignoreCase = true) ||
                requestedTask.contains("connected", ignoreCase = true)
        }
    }
tasks
    .matching {
        it.name == "connectedNonMinifiedReleaseAndroidTest" ||
            it.name == "collectNonMinifiedReleaseBaselineProfile"
    }.configureEach {
        onlyIf { deviceRunExplicitlyRequested.get() }
    }

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}

val offlineManifestGuard by tasks.registering {
    group = "verification"
    description = "Fails if a Baseline Profile test APK violates the offline boundary."
    dependsOn("processBenchmarkReleaseManifest", "processNonMinifiedReleaseManifest")

    doLast {
        val manifests =
            listOf("benchmarkRelease", "nonMinifiedRelease").map { variant ->
                val taskVariant = variant.replaceFirstChar { it.uppercaseChar() }
                layout.buildDirectory
                    .file(
                        "intermediates/packaged_manifests/$variant/" +
                            "process${taskVariant}Manifest/AndroidManifest.xml",
                    ).get()
                    .asFile
            }
        val missingManifests = manifests.filterNot { it.isFile }
        check(missingManifests.isEmpty()) {
            "Expected Baseline Profile manifests were not produced: ${missingManifests.joinToString()}"
        }
        val internetPermission =
            Regex(
                """<uses-permission(?:-sdk-\d+)?\b[^>]*android:name\s*=\s*["']android\.permission\.INTERNET["']""",
            )
        val violatingManifests = manifests.filter { internetPermission.containsMatchIn(it.readText()) }
        check(violatingManifests.isEmpty()) {
            "Offline boundary violated: INTERNET permission found in ${violatingManifests.joinToString()}"
        }

        val forbiddenTokens =
            listOf("okhttp", "retrofit", "ktor-client", "grpc", "volley", "apollo", "firebase", "analytics")
        val runtimeConfigurations =
            listOf(
                "benchmarkReleaseRuntimeClasspath",
                "nonMinifiedReleaseRuntimeClasspath",
            )
        val violations =
            runtimeConfigurations.flatMap { configurationName ->
                configurations
                    .getByName(configurationName)
                    .incoming
                    .resolutionResult
                    .allComponents
                    .mapNotNull { component ->
                        component.moduleVersion?.let { id -> "$configurationName:${id.group}:${id.name}" }
                    }.filter { coordinate -> forbiddenTokens.any { token -> token in coordinate.lowercase() } }
            }
        check(violations.isEmpty()) {
            "Offline boundary violated by Baseline Profile networking artifacts: ${violations.joinToString()}"
        }
    }
}

tasks.named("check").configure { dependsOn(offlineManifestGuard) }
tasks
    .matching {
        it.name == "assembleBenchmarkRelease" || it.name == "assembleNonMinifiedRelease"
    }.configureEach {
        dependsOn(offlineManifestGuard)
    }
