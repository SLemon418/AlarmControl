import groovy.json.JsonOutput
import java.nio.file.Files

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

val semanticAssetNames =
    setOf(
        "semantic_notification_classifier.tflite",
        "semantic_vocab.txt",
        "semantic_labels.txt",
        "semantic_model_manifest.json",
    )
val semanticModelAssetName = "semantic_notification_classifier.tflite"
val semanticManifestAssetName = "semantic_model_manifest.json"
val semanticLabels =
    listOf(
        "MARKETING",
        "TRANSACTIONAL",
        "SECURITY",
        "DELIVERY",
        "SOCIAL",
        "OTHER",
        "AMBIGUOUS",
    )

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

fun verifySemanticAssetDirectory(
    assetDirectory: File,
    allowMissingClassifier: Boolean,
): Boolean {
    check(assetDirectory.isDirectory) {
        "Semantic asset directory does not exist: ${assetDirectory.absolutePath}"
    }
    val semanticFiles =
        Files
            .walk(assetDirectory.toPath())
            .use { paths ->
                paths
                    .filter { path ->
                        path != assetDirectory.toPath() &&
                            path.fileName.toString().startsWith("semantic_")
                    }.map { path -> assetDirectory.toPath().relativize(path).toString() to path.toFile() }
                    .toList()
            }.toMap()
    val unexpected = semanticFiles.keys - semanticAssetNames
    check(unexpected.isEmpty()) {
        "Unexpected semantic assets: ${unexpected.sorted().joinToString()}"
    }
    semanticFiles.values.forEach { file ->
        check(!Files.isSymbolicLink(file.toPath()) && file.isFile) {
            "Semantic assets must be regular non-symlink files: ${file.absolutePath}"
        }
    }
    semanticFiles.forEach { (name, file) ->
        check(file.length() in 1..semanticAssetMaximumBytes.getValue(name)) {
            "Semantic asset $name exceeds its source size limit"
        }
    }
    return semanticAssetPayloadVerifier(
        semanticFiles.mapValues { (_, file) -> file.readBytes() },
        allowMissingClassifier,
    )
}

val verifyBundledSemanticAssets by tasks.registering {
    group = "verification"
    description = "Verifies bundled semantic asset completeness and manifest-bound integrity."

    doLast {
        val assets = projectDir.resolve("src/main/assets")
        val present =
            verifySemanticAssetDirectory(
                assetDirectory = assets,
                allowMissingClassifier = true,
            )
        if (!present) {
            logger.lifecycle(
                "Semantic classifier is not promoted yet; strict presence is enforced by :app:releaseCandidate.",
            )
        }
    }
}

val verifyBundledSemanticAssetsFixture by tasks.registering {
    group = "verification"
    description = "Exercises semantic asset verification with tiny synthetic fixtures."

    doLast {
        fun writeCompleteFixture(
            directory: File,
            mutateManifest: (MutableMap<String, Any?>) -> Unit = {},
        ) {
            directory.mkdirs()
            val payloads =
                mapOf(
                    semanticModelAssetName to "tiny-tflite".toByteArray(),
                    "semantic_vocab.txt" to "[PAD]\n[UNK]\n[CLS]\n[SEP]\n".toByteArray(),
                    "semantic_labels.txt" to (semanticLabels.joinToString("\n") + "\n").toByteArray(),
                )
            payloads.forEach { (name, content) -> directory.resolve(name).writeBytes(content) }
            val files =
                payloads.mapValues { (_, content) ->
                    mutableMapOf<String, Any?>(
                        "sha256" to semanticAssetSha256(content),
                        "size_bytes" to content.size,
                    )
                }
            val modelHash = files.getValue(semanticModelAssetName).getValue("sha256")
            val vocabHash = files.getValue("semantic_vocab.txt").getValue("sha256")

            fun provenance(sourceHash: String) =
                mutableMapOf<String, Any?>(
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
                    "labels" to semanticLabels,
                    "max_sequence_length" to 128,
                    "general_threshold" to 0.949999988079071,
                    "marketing_threshold" to 1.0,
                    "tokenizer" to
                        mutableMapOf<String, Any?>(
                            "type" to "bert-wordpiece",
                            "normalization" to "nfc",
                            "lowercase" to false,
                        ),
                    "conversion" to
                        mutableMapOf<String, Any?>(
                            "quantization" to
                                mutableMapOf<String, Any?>(
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
                                mutableMapOf<String, Any?>(
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
            directory.resolve(semanticManifestAssetName).writeText(
                JsonOutput.toJson(manifest) + "\n",
            )
        }

        val complete = temporaryDir.resolve("complete")
        writeCompleteFixture(complete)
        check(verifySemanticAssetDirectory(complete, allowMissingClassifier = false))

        fun assertManifestRejected(
            name: String,
            expectedMessage: String,
            mutation: (MutableMap<String, Any?>) -> Unit,
        ) {
            val directory = temporaryDir.resolve(name)
            writeCompleteFixture(directory, mutation)
            val failure =
                runCatching {
                    verifySemanticAssetDirectory(directory, allowMissingClassifier = false)
                }.exceptionOrNull()
            check(failure?.message?.contains(expectedMessage) == true) {
                "$name semantic fixture did not fail closed: ${failure?.message}"
            }
        }

        assertManifestRejected(
            name = "non-float32-general-threshold",
            expectedMessage = "exactly representable as float32",
        ) { manifest ->
            manifest["general_threshold"] = 0.95
        }
        assertManifestRejected(
            name = "non-float32-marketing-threshold",
            expectedMessage = "exactly representable as float32",
        ) { manifest ->
            manifest["marketing_threshold"] = 0.95
        }
        assertManifestRejected(
            name = "below-floor-threshold",
            expectedMessage = "below the runtime safety floor",
        ) { manifest ->
            manifest["general_threshold"] = 0.9499999284744263
        }
        assertManifestRejected(
            name = "above-ceiling-threshold",
            expectedMessage = "above 1.0",
        ) { manifest ->
            manifest["marketing_threshold"] = 1.0000001192092896
        }
        assertManifestRejected(
            name = "reversed-threshold-pair",
            expectedMessage = "greater than or equal",
        ) { manifest ->
            manifest["general_threshold"] = 1.0
            manifest["marketing_threshold"] = 0.949999988079071
        }
        assertManifestRejected(
            name = "old-scalar-threshold-schema",
            expectedMessage = "fields mismatch",
        ) { manifest ->
            manifest["schema_version"] = "alarmcontrol-semantic-model-manifest-v1"
            manifest.remove("general_threshold")
            manifest.remove("marketing_threshold")
            manifest["confidence_threshold"] = 0.949999988079071
        }
        assertManifestRejected(
            name = "missing-marketing-threshold",
            expectedMessage = "fields mismatch",
        ) { manifest ->
            manifest.remove("marketing_threshold")
        }
        assertManifestRejected(
            name = "failed-quantization-audit",
            expectedMessage = "audit must pass",
        ) { manifest ->
            @Suppress("UNCHECKED_CAST")
            val conversion = manifest.getValue("conversion") as MutableMap<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val audit = conversion.getValue("quantization_audit") as MutableMap<String, Any?>
            audit["passed"] = false
        }
        assertManifestRejected(
            name = "missing-development-evidence",
            expectedMessage = "fields mismatch",
        ) { manifest ->
            @Suppress("UNCHECKED_CAST")
            val evidence = manifest.getValue("evidence") as MutableMap<String, Any?>
            evidence.remove("development_test_gate_sha256")
        }
        assertManifestRejected(
            name = "float32-deployment",
            expectedMessage = "must apply dynamic-int8",
        ) { manifest ->
            @Suppress("UNCHECKED_CAST")
            val conversion = manifest.getValue("conversion") as MutableMap<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val quantization = conversion.getValue("quantization") as MutableMap<String, Any?>
            quantization["requested"] = "float32"
            quantization["applied"] = "float32"
            quantization["experimental_backend"] = false
        }

        val oversizedSource = temporaryDir.resolve("oversized-source")
        writeCompleteFixture(oversizedSource)
        oversizedSource.resolve("semantic_labels.txt").writeBytes(ByteArray(1_025))
        val oversizedSourceFailure =
            runCatching {
                verifySemanticAssetDirectory(oversizedSource, allowMissingClassifier = false)
            }.exceptionOrNull()
        check(oversizedSourceFailure?.message?.contains("source size limit") == true) {
            "Oversized source semantic asset was read instead of failing its pre-read cap"
        }

        val corrupted = temporaryDir.resolve("corrupted")
        writeCompleteFixture(corrupted)
        corrupted.resolve("semantic_vocab.txt").appendText("corrupt\n")
        val corruptFailure =
            runCatching {
                verifySemanticAssetDirectory(corrupted, allowMissingClassifier = false)
            }.exceptionOrNull()
        check(corruptFailure?.message?.contains("does not match its manifest") == true) {
            "Corrupt semantic fixture did not fail closed"
        }

        val incomplete = temporaryDir.resolve("incomplete")
        writeCompleteFixture(incomplete)
        incomplete.resolve("semantic_vocab.txt").delete()
        val incompleteFailure =
            runCatching {
                verifySemanticAssetDirectory(incomplete, allowMissingClassifier = false)
            }.exceptionOrNull()
        check(incompleteFailure?.message?.contains("payload is incomplete") == true) {
            "Incomplete semantic fixture did not fail closed"
        }

        val missingField = temporaryDir.resolve("missing-field")
        writeCompleteFixture(missingField) { manifest -> manifest.remove("tokenizer") }
        val missingFieldFailure =
            runCatching {
                verifySemanticAssetDirectory(missingField, allowMissingClassifier = false)
            }.exceptionOrNull()
        check(missingFieldFailure?.message?.contains("fields mismatch") == true) {
            "Missing manifest field fixture did not fail closed"
        }

        val extraField = temporaryDir.resolve("extra-field")
        writeCompleteFixture(extraField) { manifest -> manifest["unexpected"] = true }
        val extraFieldFailure =
            runCatching {
                verifySemanticAssetDirectory(extraField, allowMissingClassifier = false)
            }.exceptionOrNull()
        check(extraFieldFailure?.message?.contains("fields mismatch") == true) {
            "Extra manifest field fixture did not fail closed"
        }

        val invalidField = temporaryDir.resolve("invalid-field")
        writeCompleteFixture(invalidField) { manifest ->
            @Suppress("UNCHECKED_CAST")
            val tokenizer = manifest.getValue("tokenizer") as MutableMap<String, Any?>
            tokenizer["normalization"] = "nfkc"
        }
        val invalidFieldFailure =
            runCatching {
                verifySemanticAssetDirectory(invalidField, allowMissingClassifier = false)
            }.exceptionOrNull()
        check(invalidFieldFailure?.message?.contains("normalization") == true) {
            "Invalid manifest field fixture did not fail closed"
        }

        val prePromotion = temporaryDir.resolve("pre-promotion")
        prePromotion.mkdirs()
        prePromotion.resolve("semantic_labels.txt").writeText(semanticLabels.joinToString("\n"))
        check(!verifySemanticAssetDirectory(prePromotion, allowMissingClassifier = true))

        val partialPrePromotion = temporaryDir.resolve("partial-pre-promotion")
        partialPrePromotion.mkdirs()
        partialPrePromotion.resolve("semantic_labels.txt").writeText(semanticLabels.joinToString("\n"))
        partialPrePromotion.resolve("semantic_vocab.txt").writeText("[PAD]\n[UNK]\n[CLS]\n[SEP]\n")
        val partialPrePromotionFailure =
            runCatching {
                verifySemanticAssetDirectory(
                    partialPrePromotion,
                    allowMissingClassifier = true,
                )
            }.exceptionOrNull()
        check(partialPrePromotionFailure?.message?.contains("payload is incomplete") == true) {
            "Partial pre-promotion semantic payload did not fail closed"
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyBundledSemanticAssets, verifyBundledSemanticAssetsFixture)
}
