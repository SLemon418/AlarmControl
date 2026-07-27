import java.util.jar.JarFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

val releaseSigningValues =
    mapOf(
        "storeFile" to providers.environmentVariable("ALARMCONTROL_KEYSTORE_FILE").orNull,
        "storePassword" to providers.environmentVariable("ALARMCONTROL_KEYSTORE_PASSWORD").orNull,
        "keyAlias" to providers.environmentVariable("ALARMCONTROL_KEY_ALIAS").orNull,
        "keyPassword" to providers.environmentVariable("ALARMCONTROL_KEY_PASSWORD").orNull,
    )
val hasAnyReleaseSigningValue = releaseSigningValues.values.any { it != null }
val hasCompleteReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }
check(!hasAnyReleaseSigningValue || hasCompleteReleaseSigning) {
    "Set all four ALARMCONTROL_KEYSTORE_* / ALARMCONTROL_KEY_* environment variables, or none."
}

android {
    namespace = "com.alarmcontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alarmcontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningValues["storeFile"]))
                storePassword = requireNotNull(releaseSigningValues["storePassword"])
                keyAlias = requireNotNull(releaseSigningValues["keyAlias"])
                keyPassword = requireNotNull(releaseSigningValues["keyPassword"])
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Robolectric needs merged Android resources to run Compose UI tests on the local JVM.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // AGP 8.7 lint crashes in Kotlin FIR while analysing the Compose Android-test class.
        // Product sources remain fully linted; test sources are independently compiled and gated
        // by detekt, ktlint, and the managed-device job.
        checkTestSources = false
    }
}

kotlin {
    jvmToolchain(17)
}

// The app module is the only Compose consumer for now; the design system stays here until
// multiple feature modules justify extracting :core:designsystem (CLAUDE.md §4).
dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":ml"))
    implementation(project(":notifications"))
    implementation(project(":automation"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    // Background work: WorkManager + Hilt-injected workers (@HiltWorker).
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // Local JVM Compose UI tests via Robolectric (no emulator). ui-test-manifest stays on
    // debugImplementation above; it provides the ComponentActivity createComposeRule() uses.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)

    baselineProfile(project(":baselineprofile"))
}

// Profile generation is an explicit device/emulator task. Normal assemble/build/check commands
// consume the last generated profile and never attempt to boot a device.
baselineProfile {
    automaticGenerationDuringBuild = false
}

// Robolectric-backed Compose UI tests rely on ui-test-manifest (debugImplementation), which isn't
// merged into the release variant — so run :app unit tests on the debug variant only.
tasks.matching { it.name == "testReleaseUnitTest" }.configureEach { enabled = false }

// Build-time enforcement in addition to the JVM guard tests: every assembled variant's merged
// manifest and runtime dependency graph must remain offline-clean (CLAUDE.md §3).
val offlineGuard by tasks.registering {
    group = "verification"
    description = "Fails if a merged manifest or runtime classpath violates the offline boundary."
    dependsOn("processDebugMainManifest", "processReleaseMainManifest")

    doLast {
        val manifests =
            listOf("debug", "release").map { variant ->
                val taskVariant = variant.replaceFirstChar { it.uppercaseChar() }
                layout.buildDirectory
                    .file(
                        "intermediates/merged_manifest/$variant/" +
                            "process${taskVariant}MainManifest/AndroidManifest.xml",
                    ).get()
                    .asFile
            }
        val missingManifests = manifests.filterNot { it.isFile }
        check(missingManifests.isEmpty()) {
            "Expected merged manifests were not produced: ${missingManifests.joinToString()}"
        }
        val internetPermission =
            Regex(
                """<uses-permission(?:-sdk-\d+)?\b[^>]*android:name\s*=\s*["']android\.permission\.INTERNET["']""",
            )
        val violatingManifests =
            manifests.filter { manifest ->
                internetPermission.containsMatchIn(manifest.readText())
            }
        check(violatingManifests.isEmpty()) {
            "Offline boundary violated: INTERNET permission found in ${violatingManifests.joinToString()}"
        }

        val forbiddenTokens =
            listOf("okhttp", "retrofit", "ktor-client", "grpc", "volley", "apollo", "firebase", "analytics")
        val violations =
            listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").flatMap { configurationName ->
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
            "Offline boundary violated by networking artifacts: ${violations.joinToString()}"
        }
    }
}

tasks.named("check").configure { dependsOn(offlineGuard) }
tasks
    .matching {
        it.name == "assembleDebug" ||
            it.name == "assembleRelease" ||
            it.name == "bundleDebug" ||
            it.name == "bundleRelease"
    }.configureEach {
        dependsOn(offlineGuard)
    }

val maxReleaseBundleBytes = 60L * 1_024 * 1_024

tasks
    .matching { it.name == "bundleRelease" }
    .configureEach {
        doLast {
            val bundles =
                layout.buildDirectory
                    .dir("outputs/bundle/release")
                    .get()
                    .asFile
                    .listFiles { file -> file.extension == "aab" }
                    .orEmpty()
            check(bundles.size == 1) {
                "Expected one release AAB, found ${bundles.size}"
            }
            val bundle = bundles.single()
            check(bundle.length() <= maxReleaseBundleBytes) {
                "Release AAB is ${bundle.length()} bytes; limit is $maxReleaseBundleBytes bytes"
            }
        }
    }

// Normal local/CI compilation may intentionally produce an unsigned release artifact. Distribution
// uses the explicit releaseCandidate gate below so an unsigned AAB can never be mistaken for a
// publishable bundle.
val verifyReleaseSigningConfiguration by tasks.registering {
    group = "verification"
    description = "Fails unless every release-signing environment variable is configured."

    doLast {
        check(hasCompleteReleaseSigning) {
            "Release signing is not configured. Set ALARMCONTROL_KEYSTORE_FILE, " +
                "ALARMCONTROL_KEYSTORE_PASSWORD, ALARMCONTROL_KEY_ALIAS, and ALARMCONTROL_KEY_PASSWORD."
        }
    }
}

tasks
    .matching { it.name == "bundleRelease" }
    .configureEach {
        mustRunAfter(verifyReleaseSigningConfiguration)
    }

val verifyReleaseBundleSigning by tasks.registering {
    group = "verification"
    description = "Builds the release AAB and cryptographically verifies its JAR signature."
    dependsOn(verifyReleaseSigningConfiguration, "bundleRelease")

    doLast {
        val bundles =
            layout.buildDirectory
                .dir("outputs/bundle/release")
                .get()
                .asFile
                .listFiles { file -> file.extension == "aab" }
                .orEmpty()
        check(bundles.size == 1) {
            "Expected one release AAB, found ${bundles.size}"
        }
        val bundle = bundles.single()
        val entryNames = mutableSetOf<String>()
        var signedPayloadEntries = 0
        val unsignedPayloadEntries = mutableListOf<String>()
        val readBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
        JarFile(bundle, true).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryNames += entry.name.uppercase()
                if (!entry.isDirectory && !entry.name.startsWith("META-INF/")) {
                    jar.getInputStream(entry).use { input ->
                        while (input.read(readBuffer) >= 0) {
                            // Reading every byte makes JarFile validate the entry digest/signature.
                        }
                    }
                    if (!entry.codeSigners.isNullOrEmpty()) {
                        signedPayloadEntries += 1
                    } else {
                        unsignedPayloadEntries += entry.name
                    }
                }
            }
        }
        check(signedPayloadEntries > 0) {
            "Release AAB contains no cryptographically verified signed payload entries"
        }
        check(unsignedPayloadEntries.isEmpty()) {
            "Release AAB contains unsigned payload entries: " +
                unsignedPayloadEntries.take(5).joinToString()
        }
        check("META-INF/MANIFEST.MF" in entryNames) {
            "Release AAB has no JAR signature manifest"
        }
        check(entryNames.any { it.matches(Regex("""META-INF/[^/]+\.SF""")) }) {
            "Release AAB has no signature descriptor"
        }
        check(entryNames.any { it.matches(Regex("""META-INF/[^/]+\.(RSA|DSA|EC)""")) }) {
            "Release AAB has no signing certificate block"
        }
        logger.lifecycle("Verified signed release bundle: ${bundle.absolutePath}")
    }
}

tasks.register("releaseCandidate") {
    group = "build"
    description = "Runs all device-independent gates and produces a verified signed release AAB."
    dependsOn(
        ":core:check",
        ":data:check",
        ":ml:check",
        ":notifications:check",
        ":automation:check",
        ":app:check",
        ":baselineprofile:check",
        ":app:assembleRelease",
        ":app:assembleDebugAndroidTest",
        ":data:assembleDebugAndroidTest",
        ":ml:assembleDebugAndroidTest",
        ":baselineprofile:assembleNonMinifiedRelease",
        ":baselineprofile:assembleBenchmarkRelease",
        verifyReleaseBundleSigning,
    )
}
