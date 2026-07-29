import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

val appVersionFile = file("version.json")
val appVersion =
    (JsonSlurper().parse(appVersionFile) as? Map<*, *>).also { value ->
        check(value?.keys == setOf("versionCode", "versionName")) {
            "$appVersionFile must contain only versionCode and versionName"
        }
    } ?: error("$appVersionFile must contain a JSON object")
val appVersionCode =
    appVersion["versionCode"].let { value ->
        check(value is Int && value > 0) {
            "$appVersionFile versionCode must be a positive integer"
        }
        value
    }
val appVersionName =
    appVersion["versionName"].let { value ->
        check(value is String && Regex("""[0-9]+\.[0-9]+\.[0-9]+""").matches(value)) {
            "$appVersionFile versionName must be MAJOR.MINOR.PATCH"
        }
        value
    }

data class ReleaseBundleSizeReport(
    val nonSemanticPhysicalBytes: Long,
    val semanticClassifierRawBytes: Long,
    val semanticPayloadRawBytes: Long,
    val semanticPayloadCompressedBytes: Long,
    val totalPhysicalBytes: Long,
    val hasSemanticClassifier: Boolean,
)

val semanticBundleEntries =
    setOf(
        "base/assets/semantic_notification_classifier.tflite",
        "base/assets/semantic_vocab.txt",
        "base/assets/semantic_labels.txt",
        "base/assets/semantic_model_manifest.json",
    )
val semanticClassifierBundleEntry = "base/assets/semantic_notification_classifier.tflite"
val semanticApkEntries = semanticBundleEntries.mapTo(mutableSetOf()) { it.removePrefix("base/") }
val semanticClassifierApkEntry = semanticClassifierBundleEntry.removePrefix("base/")
val semanticAssetEntryPattern = Regex("""(?:^|.*/)assets/(?:.*/)?semantic_[^/]+$""")
val semanticClassifierTargetBytes = 30L * 1_024 * 1_024
val maxSemanticClassifierBytes = 45L * 1_024 * 1_024
// This is an internal full-AAB budget after subtracting the semantic entries. It is deliberately
// not described as Play's device-specific compressed download size.
val maxNonSemanticAabPhysicalBytes = 60L * 1_024 * 1_024
val maxPhysicalBundleBytes = 105L * 1_024 * 1_024
// Direct GitHub distribution uses one universal APK so users cannot accidentally install the
// wrong ABI. Its four native ABI payloads need a larger non-semantic budget than the Play AAB.
val maxNonSemanticApkPhysicalBytes = 140L * 1_024 * 1_024
val maxPhysicalApkBytes = 185L * 1_024 * 1_024
val releaseSigningCertificatePinFile =
    rootProject.layout.projectDirectory
        .file("config/release-signing-certificate.sha256")
        .asFile
val releaseSigningCertificatePattern = Regex("[0-9a-fA-F]{64}")
val apksignerCertificateDigestPattern =
    Regex("""(?m)^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$""")

fun requireReleaseSigningCertificatePin(): String {
    check(releaseSigningCertificatePinFile.isFile) {
        "Missing public release-signing certificate pin: $releaseSigningCertificatePinFile"
    }
    val pin = releaseSigningCertificatePinFile.readText().trim()
    check(releaseSigningCertificatePattern.matches(pin)) {
        "Release-signing certificate pin is not configured. Replace the pending value in " +
            "$releaseSigningCertificatePinFile with the independently verified SHA-256 digest " +
            "of the long-lived Android update certificate."
    }
    return pin.lowercase()
}

@Suppress("UNCHECKED_CAST")
val semanticAssetPayloadVerifier =
    rootProject.extra["semanticAssetPayloadVerifier"] as
        (Map<String, ByteArray>, Boolean) -> Boolean

@Suppress("UNCHECKED_CAST")
val semanticAssetSha256 =
    rootProject.extra["semanticAssetSha256"] as (ByteArray) -> String

@Suppress("UNCHECKED_CAST")
val semanticAssetMaximumBytes =
    rootProject.extra["semanticAssetMaximumBytes"] as Map<String, Long>

fun requireSemanticClassifierSize(sizeBytes: Long) {
    check(sizeBytes <= maxSemanticClassifierBytes) {
        "Release semantic classifier is $sizeBytes raw bytes; " +
            "hard limit is $maxSemanticClassifierBytes bytes"
    }
}

fun requireReleaseBundleSizeLimits(
    nonSemanticAabPhysicalBytes: Long,
    totalPhysicalBytes: Long,
) = requireReleaseArchiveSizeLimits(
    artifactLabel = "Release AAB",
    nonSemanticPhysicalBytes = nonSemanticAabPhysicalBytes,
    totalPhysicalBytes = totalPhysicalBytes,
    maxNonSemanticPhysicalBytes = maxNonSemanticAabPhysicalBytes,
    maxTotalPhysicalBytes = maxPhysicalBundleBytes,
)

fun requireReleaseApkSizeLimits(
    nonSemanticPhysicalBytes: Long,
    totalPhysicalBytes: Long,
) = requireReleaseArchiveSizeLimits(
    artifactLabel = "Release APK",
    nonSemanticPhysicalBytes = nonSemanticPhysicalBytes,
    totalPhysicalBytes = totalPhysicalBytes,
    maxNonSemanticPhysicalBytes = maxNonSemanticApkPhysicalBytes,
    maxTotalPhysicalBytes = maxPhysicalApkBytes,
)

fun requireReleaseArchiveSizeLimits(
    artifactLabel: String,
    nonSemanticPhysicalBytes: Long,
    totalPhysicalBytes: Long,
    maxNonSemanticPhysicalBytes: Long,
    maxTotalPhysicalBytes: Long,
) {
    check(nonSemanticPhysicalBytes <= maxNonSemanticPhysicalBytes) {
        "$artifactLabel non-semantic physical payload is $nonSemanticPhysicalBytes bytes " +
            "after subtracting semantic entry compressed sizes; internal limit is " +
            "$maxNonSemanticPhysicalBytes bytes"
    }
    check(totalPhysicalBytes <= maxTotalPhysicalBytes) {
        "$artifactLabel physical size is $totalPhysicalBytes bytes; " +
            "limit is $maxTotalPhysicalBytes bytes"
    }
}

fun requireSemanticArchiveEntrySize(
    entryName: String,
    sizeBytes: Long,
    semanticEntryPrefix: String,
    artifactLabel: String,
) {
    val assetName = entryName.removePrefix(semanticEntryPrefix)
    check(sizeBytes in 1..semanticAssetMaximumBytes.getValue(assetName)) {
        "$artifactLabel semantic model entry exceeds its pre-read size limit: $entryName"
    }
}

fun inspectReleaseArchiveSize(
    archive: File,
    artifactLabel: String,
    expectedSemanticEntries: Set<String>,
    classifierEntry: String,
    semanticEntryPrefix: String,
    maxNonSemanticPhysicalBytes: Long,
    maxTotalPhysicalBytes: Long,
): ReleaseBundleSizeReport {
    check(archive.isFile) {
        "$artifactLabel does not exist: ${archive.absolutePath}"
    }
    val expectedEntryCounts = expectedSemanticEntries.associateWith { 0 }.toMutableMap()
    val unexpectedSemanticEntries = mutableListOf<String>()
    var semanticClassifierRawBytes = 0L
    var semanticPayloadRawBytes = 0L
    var semanticPayloadCompressedBytes = 0L
    val semanticPayloadBytes = mutableMapOf<String, ByteArray>()

    ZipFile(archive).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.name in expectedSemanticEntries) {
                check(!entry.isDirectory) {
                    "$artifactLabel semantic model entry must be a file: ${entry.name}"
                }
                check(entry.size >= 0L && entry.compressedSize >= 0L) {
                    "$artifactLabel semantic model entry has unknown size: ${entry.name}"
                }
                val assetName = entry.name.removePrefix(semanticEntryPrefix)
                requireSemanticArchiveEntrySize(
                    entryName = entry.name,
                    sizeBytes = entry.size,
                    semanticEntryPrefix = semanticEntryPrefix,
                    artifactLabel = artifactLabel,
                )
                expectedEntryCounts[entry.name] = expectedEntryCounts.getValue(entry.name) + 1
                semanticPayloadRawBytes += entry.size
                semanticPayloadCompressedBytes += entry.compressedSize
                val bytes = zip.getInputStream(entry).use { input -> input.readBytes() }
                check(bytes.size.toLong() == entry.size) {
                    "$artifactLabel semantic model entry size changed while reading: ${entry.name}"
                }
                semanticPayloadBytes[assetName] = bytes
                if (entry.name == classifierEntry) {
                    semanticClassifierRawBytes += entry.size
                }
            } else if (!entry.isDirectory && semanticAssetEntryPattern.matches(entry.name)) {
                unexpectedSemanticEntries += entry.name
            }
        }
    }

    check(unexpectedSemanticEntries.isEmpty()) {
        "$artifactLabel contains unexpected semantic model assets: " +
            unexpectedSemanticEntries.sorted().joinToString()
    }
    val duplicateEntries =
        expectedEntryCounts
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
    check(duplicateEntries.isEmpty()) {
        "$artifactLabel contains duplicate semantic model entries: ${duplicateEntries.joinToString()}"
    }

    val hasSemanticClassifier = expectedEntryCounts.getValue(classifierEntry) == 1
    if (hasSemanticClassifier) {
        val missingEntries =
            expectedEntryCounts
                .filterValues { count -> count != 1 }
                .keys
                .sorted()
        check(missingEntries.isEmpty()) {
            "$artifactLabel semantic model payload is incomplete; expected each entry exactly once. " +
                "Missing: ${missingEntries.joinToString()}"
        }
    }
    check(
        semanticAssetPayloadVerifier(
            semanticPayloadBytes,
            true,
        ) == hasSemanticClassifier,
    ) {
        "$artifactLabel semantic payload presence is inconsistent"
    }

    val totalPhysicalBytes = archive.length()
    val nonSemanticPhysicalBytes = totalPhysicalBytes - semanticPayloadCompressedBytes
    check(nonSemanticPhysicalBytes >= 0L) {
        "$artifactLabel semantic compressed size exceeds its physical file size"
    }
    requireSemanticClassifierSize(semanticClassifierRawBytes)
    requireReleaseArchiveSizeLimits(
        artifactLabel = artifactLabel,
        nonSemanticPhysicalBytes = nonSemanticPhysicalBytes,
        totalPhysicalBytes = totalPhysicalBytes,
        maxNonSemanticPhysicalBytes = maxNonSemanticPhysicalBytes,
        maxTotalPhysicalBytes = maxTotalPhysicalBytes,
    )

    return ReleaseBundleSizeReport(
        nonSemanticPhysicalBytes = nonSemanticPhysicalBytes,
        semanticClassifierRawBytes = semanticClassifierRawBytes,
        semanticPayloadRawBytes = semanticPayloadRawBytes,
        semanticPayloadCompressedBytes = semanticPayloadCompressedBytes,
        totalPhysicalBytes = totalPhysicalBytes,
        hasSemanticClassifier = hasSemanticClassifier,
    )
}

fun inspectReleaseBundleSize(bundle: File): ReleaseBundleSizeReport =
    inspectReleaseArchiveSize(
        archive = bundle,
        artifactLabel = "Release AAB",
        expectedSemanticEntries = semanticBundleEntries,
        classifierEntry = semanticClassifierBundleEntry,
        semanticEntryPrefix = "base/assets/",
        maxNonSemanticPhysicalBytes = maxNonSemanticAabPhysicalBytes,
        maxTotalPhysicalBytes = maxPhysicalBundleBytes,
    )

fun inspectReleaseApkSize(apk: File): ReleaseBundleSizeReport =
    inspectReleaseArchiveSize(
        archive = apk,
        artifactLabel = "Release APK",
        expectedSemanticEntries = semanticApkEntries,
        classifierEntry = semanticClassifierApkEntry,
        semanticEntryPrefix = "assets/",
        maxNonSemanticPhysicalBytes = maxNonSemanticApkPhysicalBytes,
        maxTotalPhysicalBytes = maxPhysicalApkBytes,
    )

fun requireReleaseSemanticPayload(
    report: ReleaseBundleSizeReport,
    classifierEntry: String = semanticClassifierBundleEntry,
) {
    check(report.hasSemanticClassifier) {
        "Release candidate must contain $classifierEntry and all semantic sidecars"
    }
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
        versionCode = appVersionCode
        versionName = appVersionName

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

    // Preserve every ABI supplied by runtime dependencies in the AAB. Play then delivers only the
    // matching ABI to each device. Do not add filters that narrow dependency-provided compatibility.
    bundle {
        abi {
            enableSplit = true
        }
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
            val report = inspectReleaseBundleSize(bundle)
            if (!report.hasSemanticClassifier) {
                logger.lifecycle(
                    "Semantic classifier is not present; exact four-entry completeness " +
                        "will be required when $semanticClassifierBundleEntry is packaged.",
                )
            }
            if (report.semanticClassifierRawBytes > semanticClassifierTargetBytes) {
                logger.warn(
                    "Release AAB semantic classifier exceeds the 30 MiB target: " +
                        "${report.semanticClassifierRawBytes} raw bytes",
                )
            }
            logger.lifecycle(
                "Release AAB size accounting: nonSemanticPhysical=" +
                    "${report.nonSemanticPhysicalBytes} bytes, " +
                    "classifier=${report.semanticClassifierRawBytes} raw bytes, " +
                    "semanticPayload=${report.semanticPayloadRawBytes} raw bytes " +
                    "(${report.semanticPayloadCompressedBytes} compressed bytes), " +
                    "total=${report.totalPhysicalBytes} bytes",
            )
        }
    }

val verifyReleaseBundleSizeAccountingFixture by tasks.registering {
    group = "verification"
    description = "Checks semantic AAB size and content integrity with tiny synthetic ZIP fixtures."

    doLast {
        fun runtimeSemanticEntries(mutateManifest: (MutableMap<String, Any?>) -> Unit = {}): Map<String, ByteArray> {
            val payloads =
                mapOf(
                    "semantic_notification_classifier.tflite" to "tiny-tflite".toByteArray(),
                    "semantic_vocab.txt" to "[PAD]\n[UNK]\n[CLS]\n[SEP]\n".toByteArray(),
                    "semantic_labels.txt" to
                        (
                            "MARKETING\nTRANSACTIONAL\nSECURITY\nDELIVERY\n" +
                                "SOCIAL\nOTHER\nAMBIGUOUS\n"
                        ).toByteArray(),
                )
            val files =
                payloads.mapValues { (_, content) ->
                    mapOf(
                        "sha256" to semanticAssetSha256(content),
                        "size_bytes" to content.size,
                    )
                }
            val modelHash = files.getValue("semantic_notification_classifier.tflite").getValue("sha256")
            val vocabHash = files.getValue("semantic_vocab.txt").getValue("sha256")

            fun provenance(sourceHash: String) =
                mapOf(
                    "schema_version" to "semantic-evaluation-provenance-v1",
                    "source_manifest_sha256" to sourceHash,
                    "backend" to "tensorflow-lite",
                    "model_artifact_sha256" to modelHash,
                    "vocab_sha256" to vocabHash,
                )
            val manifest =
                mutableMapOf<String, Any?>(
                    "schema_version" to "alarmcontrol-semantic-model-manifest-v2",
                    "files" to files,
                    "labels" to
                        listOf(
                            "MARKETING",
                            "TRANSACTIONAL",
                            "SECURITY",
                            "DELIVERY",
                            "SOCIAL",
                            "OTHER",
                            "AMBIGUOUS",
                        ),
                    "max_sequence_length" to 128,
                    "general_threshold" to 0.949999988079071,
                    "marketing_threshold" to 1.0,
                    "tokenizer" to
                        mapOf(
                            "type" to "bert-wordpiece",
                            "normalization" to "nfc",
                            "lowercase" to false,
                        ),
                    "conversion" to
                        mutableMapOf<String, Any?>(
                            "quantization" to
                                mapOf(
                                    "requested" to "dynamic-int8",
                                    "applied" to "dynamic-int8",
                                    "calibration_used" to false,
                                    "experimental_backend" to true,
                                    "fallback_reason" to null,
                                ),
                            "quantization_audit" to
                                mutableMapOf<String, Any?>(
                                    "schema_version" to "koelectra-dynamic-int8-audit-v1",
                                    "method" to
                                        "litert-interpreter-tensor-and-operator-inspection",
                                    "tensor_count" to 100,
                                    "int8_tensor_count" to 20,
                                    "operator_count" to 50,
                                    "quantize_operator_count" to 10,
                                    "passed" to true,
                                ),
                            "tensor_contract" to
                                mapOf(
                                    "inputs" to
                                        listOf(
                                            mapOf(
                                                "name" to "input_ids",
                                                "dtype" to "int32",
                                                "shape" to listOf(1, 128),
                                            ),
                                            mapOf(
                                                "name" to "attention_mask",
                                                "dtype" to "int32",
                                                "shape" to listOf(1, 128),
                                            ),
                                        ),
                                    "output" to
                                        mapOf(
                                            "name" to "logits",
                                            "dtype" to "float32",
                                            "shape" to listOf(1, 7),
                                        ),
                                ),
                        ),
                    "evidence" to
                        mutableMapOf<String, Any?>(
                            "conversion_manifest_sha256" to "a".repeat(64),
                            "threshold_selection_sha256" to "b".repeat(64),
                            "development_test_gate_sha256" to "c".repeat(64),
                            "test_parity_report_sha256" to "d".repeat(64),
                            "sealed_holdout_gate_sha256" to "e".repeat(64),
                        ),
                    "evaluation_provenance" to
                        mapOf(
                            "threshold_selection" to provenance("f".repeat(64)),
                            "development_test" to provenance("a".repeat(64)),
                            "sealed_holdout" to provenance("b".repeat(64)),
                        ),
                )
            mutateManifest(manifest)
            return payloads.mapKeys { (name, _) -> "base/assets/$name" } +
                mapOf(
                    "base/assets/semantic_model_manifest.json" to
                        (JsonOutput.toJson(manifest) + "\n").toByteArray(),
                )
        }

        val semanticFixtureEntries = runtimeSemanticEntries()

        fun writeFixture(
            target: File,
            semanticEntries: Map<String, ByteArray> = semanticFixtureEntries,
            extraEntries: Map<String, ByteArray> = emptyMap(),
            omittedEntries: Set<String> = emptySet(),
        ) {
            target.parentFile.mkdirs()
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                val entries =
                    semanticEntries.filterKeys { name -> name !in omittedEntries } +
                        extraEntries +
                        mapOf("base/dex/classes.dex" to ByteArray(128) { it.toByte() })
                entries.forEach { (name, content) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(content)
                    output.closeEntry()
                }
            }
        }

        val completeBundle = temporaryDir.resolve("complete.aab")
        writeFixture(completeBundle)
        val completeReport = inspectReleaseBundleSize(completeBundle)
        val expectedRawBytes =
            semanticFixtureEntries.values.sumOf { content -> content.size.toLong() }
        check(completeReport.hasSemanticClassifier)
        check(completeReport.semanticPayloadRawBytes == expectedRawBytes)
        check(
            completeReport.semanticClassifierRawBytes ==
                semanticFixtureEntries.getValue(semanticClassifierBundleEntry).size.toLong(),
        )
        check(
            completeReport.nonSemanticPhysicalBytes ==
                completeBundle.length() - completeReport.semanticPayloadCompressedBytes,
        )
        requireReleaseSemanticPayload(completeReport)

        val completeApk = temporaryDir.resolve("complete.apk")
        val apkSemanticFixtureEntries =
            semanticFixtureEntries.mapKeys { (name, _) -> name.removePrefix("base/") }
        writeFixture(
            target = completeApk,
            semanticEntries = apkSemanticFixtureEntries,
        )
        val completeApkReport = inspectReleaseApkSize(completeApk)
        check(completeApkReport.hasSemanticClassifier)
        check(completeApkReport.semanticPayloadRawBytes == expectedRawBytes)
        requireReleaseSemanticPayload(completeApkReport, semanticClassifierApkEntry)

        requireSemanticClassifierSize(maxSemanticClassifierBytes)
        val classifierLimitFailure =
            runCatching {
                requireSemanticClassifierSize(maxSemanticClassifierBytes + 1L)
            }.exceptionOrNull()
        check(classifierLimitFailure?.message?.contains("hard limit") == true) {
            "Semantic classifier hard-cap boundary did not reject 45 MiB + 1 byte"
        }
        val preReadLimitFailure =
            runCatching {
                requireSemanticArchiveEntrySize(
                    entryName = semanticClassifierBundleEntry,
                    sizeBytes = maxSemanticClassifierBytes + 1L,
                    semanticEntryPrefix = "base/assets/",
                    artifactLabel = "Release AAB",
                )
            }.exceptionOrNull()
        check(preReadLimitFailure?.message?.contains("pre-read size limit") == true) {
            "Semantic AAB entry pre-read cap did not reject 45 MiB + 1 byte"
        }
        requireReleaseBundleSizeLimits(
            nonSemanticAabPhysicalBytes = maxNonSemanticAabPhysicalBytes,
            totalPhysicalBytes = maxPhysicalBundleBytes,
        )
        val nonSemanticLimitFailure =
            runCatching {
                requireReleaseBundleSizeLimits(
                    nonSemanticAabPhysicalBytes = maxNonSemanticAabPhysicalBytes + 1L,
                    totalPhysicalBytes = maxPhysicalBundleBytes,
                )
            }.exceptionOrNull()
        check(nonSemanticLimitFailure?.message?.contains("non-semantic physical payload") == true) {
            "Non-semantic AAB boundary did not reject 60 MiB + 1 byte"
        }
        val physicalBundleLimitFailure =
            runCatching {
                requireReleaseBundleSizeLimits(
                    nonSemanticAabPhysicalBytes = maxNonSemanticAabPhysicalBytes,
                    totalPhysicalBytes = maxPhysicalBundleBytes + 1L,
                )
            }.exceptionOrNull()
        check(physicalBundleLimitFailure?.message?.contains("physical size") == true) {
            "Physical AAB boundary did not reject 105 MiB + 1 byte"
        }
        requireReleaseApkSizeLimits(
            nonSemanticPhysicalBytes = maxNonSemanticApkPhysicalBytes,
            totalPhysicalBytes = maxPhysicalApkBytes,
        )
        val nonSemanticApkLimitFailure =
            runCatching {
                requireReleaseApkSizeLimits(
                    nonSemanticPhysicalBytes = maxNonSemanticApkPhysicalBytes + 1L,
                    totalPhysicalBytes = maxPhysicalApkBytes,
                )
            }.exceptionOrNull()
        check(nonSemanticApkLimitFailure?.message?.contains("non-semantic physical payload") == true) {
            "Non-semantic APK boundary did not reject 140 MiB + 1 byte"
        }
        val physicalApkLimitFailure =
            runCatching {
                requireReleaseApkSizeLimits(
                    nonSemanticPhysicalBytes = maxNonSemanticApkPhysicalBytes,
                    totalPhysicalBytes = maxPhysicalApkBytes + 1L,
                )
            }.exceptionOrNull()
        check(physicalApkLimitFailure?.message?.contains("physical size") == true) {
            "Physical APK boundary did not reject 185 MiB + 1 byte"
        }

        fun assertAabManifestRejected(
            name: String,
            expectedMessage: String,
            mutation: (MutableMap<String, Any?>) -> Unit,
        ) {
            val bundle = temporaryDir.resolve("$name.aab")
            writeFixture(
                target = bundle,
                semanticEntries = runtimeSemanticEntries(mutation),
            )
            val failure = runCatching { inspectReleaseBundleSize(bundle) }.exceptionOrNull()
            check(failure?.message?.contains(expectedMessage) == true) {
                "$name AAB semantic fixture did not fail closed: ${failure?.message}"
            }
        }

        assertAabManifestRejected(
            name = "non-float32-general-threshold",
            expectedMessage = "exactly representable as float32",
        ) { manifest ->
            manifest["general_threshold"] = 0.95
        }
        assertAabManifestRejected(
            name = "non-float32-marketing-threshold",
            expectedMessage = "exactly representable as float32",
        ) { manifest ->
            manifest["marketing_threshold"] = 0.95
        }
        assertAabManifestRejected(
            name = "below-floor-threshold",
            expectedMessage = "below the runtime safety floor",
        ) { manifest ->
            manifest["general_threshold"] = 0.9499999284744263
        }
        assertAabManifestRejected(
            name = "above-ceiling-threshold",
            expectedMessage = "above 1.0",
        ) { manifest ->
            manifest["marketing_threshold"] = 1.0000001192092896
        }
        assertAabManifestRejected(
            name = "reversed-threshold-pair",
            expectedMessage = "greater than or equal",
        ) { manifest ->
            manifest["general_threshold"] = 1.0
            manifest["marketing_threshold"] = 0.949999988079071
        }
        assertAabManifestRejected(
            name = "old-scalar-threshold-schema",
            expectedMessage = "fields mismatch",
        ) { manifest ->
            manifest["schema_version"] = "alarmcontrol-semantic-model-manifest-v1"
            manifest.remove("general_threshold")
            manifest.remove("marketing_threshold")
            manifest["confidence_threshold"] = 0.949999988079071
        }
        assertAabManifestRejected(
            name = "missing-marketing-threshold",
            expectedMessage = "fields mismatch",
        ) { manifest ->
            manifest.remove("marketing_threshold")
        }
        assertAabManifestRejected(
            name = "failed-quantization-audit",
            expectedMessage = "audit must pass",
        ) { manifest ->
            @Suppress("UNCHECKED_CAST")
            val conversion = manifest.getValue("conversion") as MutableMap<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val audit = conversion.getValue("quantization_audit") as MutableMap<String, Any?>
            audit["passed"] = false
        }
        assertAabManifestRejected(
            name = "missing-test-parity-evidence",
            expectedMessage = "fields mismatch",
        ) { manifest ->
            @Suppress("UNCHECKED_CAST")
            val evidence = manifest.getValue("evidence") as MutableMap<String, Any?>
            evidence.remove("test_parity_report_sha256")
        }

        val corruptBundle = temporaryDir.resolve("corrupt.aab")
        writeFixture(
            corruptBundle,
            extraEntries =
                mapOf(
                    "base/assets/semantic_vocab.txt" to "corrupt".toByteArray(),
                ),
        )
        val corruptFailure =
            runCatching { inspectReleaseBundleSize(corruptBundle) }.exceptionOrNull()
        check(corruptFailure?.message?.contains("does not match its manifest") == true) {
            "Corrupt packaged semantic fixture did not fail closed"
        }

        val incompleteBundle = temporaryDir.resolve("incomplete.aab")
        writeFixture(
            incompleteBundle,
            omittedEntries = setOf("base/assets/semantic_model_manifest.json"),
        )
        val incompleteFailure =
            runCatching { inspectReleaseBundleSize(incompleteBundle) }.exceptionOrNull()
        check(incompleteFailure?.message?.contains("payload is incomplete") == true) {
            "Incomplete semantic fixture did not fail closed"
        }

        val classifierMissingBundle = temporaryDir.resolve("classifier-missing.aab")
        writeFixture(
            classifierMissingBundle,
            semanticEntries =
                mapOf(
                    "base/assets/semantic_labels.txt" to
                        semanticFixtureEntries.getValue("base/assets/semantic_labels.txt"),
                ),
        )
        val classifierMissingReport = inspectReleaseBundleSize(classifierMissingBundle)
        val classifierMissingFailure =
            runCatching {
                requireReleaseSemanticPayload(classifierMissingReport)
            }.exceptionOrNull()
        check(classifierMissingFailure?.message?.contains("must contain") == true) {
            "Release-candidate semantic fixture did not fail when the classifier was absent"
        }

        val partialPrePromotionBundle = temporaryDir.resolve("partial-pre-promotion.aab")
        writeFixture(
            partialPrePromotionBundle,
            omittedEntries = setOf(semanticClassifierBundleEntry),
        )
        val partialPrePromotionFailure =
            runCatching { inspectReleaseBundleSize(partialPrePromotionBundle) }.exceptionOrNull()
        check(partialPrePromotionFailure?.message?.contains("payload is incomplete") == true) {
            "Partial pre-promotion AAB semantic payload did not fail closed"
        }

        val unexpectedBundle = temporaryDir.resolve("unexpected.aab")
        writeFixture(
            unexpectedBundle,
            extraEntries =
                mapOf(
                    "base/assets/semantic_shadow_model.tflite" to
                        byteArrayOf(1, 2, 3),
                ),
        )
        val unexpectedFailure =
            runCatching { inspectReleaseBundleSize(unexpectedBundle) }.exceptionOrNull()
        check(unexpectedFailure?.message?.contains("unexpected semantic model assets") == true) {
            "Unexpected semantic asset fixture did not fail closed"
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyReleaseBundleSizeAccountingFixture)
}

// Normal local/CI compilation may intentionally produce unsigned release artifacts. Direct GitHub
// distribution uses the explicit releaseCandidate gate below so an unsigned APK can never be
// mistaken for a publishable artifact.
val verifyReleaseSigningConfiguration by tasks.registering {
    group = "verification"
    description = "Fails unless release credentials and the public certificate pin are configured."

    doLast {
        check(hasCompleteReleaseSigning) {
            "Release signing is not configured. Set ALARMCONTROL_KEYSTORE_FILE, " +
                "ALARMCONTROL_KEYSTORE_PASSWORD, ALARMCONTROL_KEY_ALIAS, and ALARMCONTROL_KEY_PASSWORD."
        }
        requireReleaseSigningCertificatePin()
    }
}

tasks
    .matching { it.name == "assembleRelease" }
    .configureEach {
        mustRunAfter(verifyReleaseSigningConfiguration)
    }

val verifyReleaseApkSigning by tasks.registering {
    group = "verification"
    description = "Builds the universal release APK and verifies its APK signature and payload."
    dependsOn(verifyReleaseSigningConfiguration, "assembleRelease")

    doLast {
        val apks =
            layout.buildDirectory
                .dir("outputs/apk/release")
                .get()
                .asFile
                .listFiles { file ->
                    file.extension == "apk" && !file.name.endsWith("-unsigned.apk")
                }.orEmpty()
        check(apks.size == 1) {
            "Expected one universal release APK, found ${apks.size}"
        }
        val apk = apks.single()
        val report = inspectReleaseApkSize(apk)
        requireReleaseSemanticPayload(report, semanticClassifierApkEntry)

        val apksigner =
            android.sdkDirectory
                .resolve("build-tools")
                .resolve(android.buildToolsVersion)
                .resolve("apksigner")
        check(apksigner.isFile && apksigner.canExecute()) {
            "Android SDK apksigner for AGP-selected Build Tools ${android.buildToolsVersion} " +
                "was not found at ${apksigner.absolutePath}"
        }
        val verification =
            providers
                .exec {
                    commandLine(
                        apksigner.absolutePath,
                        "verify",
                        "--verbose",
                        "--print-certs",
                        "--min-sdk-version",
                        "26",
                        apk.absolutePath,
                    )
                    isIgnoreExitValue = true
                }
        check(verification.result.get().exitValue == 0) {
            "Release APK signature verification failed for ${apk.absolutePath}"
        }
        val signerDigests =
            apksignerCertificateDigestPattern
                .findAll(verification.standardOutput.asText.get())
                .map { match -> match.groupValues[1].lowercase() }
                .toList()
        check(signerDigests.size == 1) {
            "Expected exactly one APK signer certificate digest, found ${signerDigests.size}"
        }
        val expectedSignerDigest = requireReleaseSigningCertificatePin()
        check(signerDigests.single() == expectedSignerDigest) {
            "Release APK signer certificate does not match the pinned Android update certificate."
        }
        logger.lifecycle(
            "Verified signed universal release APK: ${apk.absolutePath} " +
                "(${report.totalPhysicalBytes} bytes)",
        )
    }
}

tasks.register("releaseCandidate") {
    group = "build"
    description = "Runs all device-independent gates and produces a verified signed universal APK."
    dependsOn(
        ":core:check",
        ":data:check",
        ":ml:check",
        ":notifications:check",
        ":automation:check",
        ":app:check",
        ":baselineprofile:check",
        ":ml:verifyBundledSemanticAssets",
        ":app:assembleRelease",
        ":app:assembleDebugAndroidTest",
        ":data:assembleDebugAndroidTest",
        ":ml:assembleDebugAndroidTest",
        ":baselineprofile:assembleNonMinifiedRelease",
        ":baselineprofile:assembleBenchmarkRelease",
        verifyReleaseApkSigning,
    )
}
