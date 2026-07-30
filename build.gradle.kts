import groovy.json.JsonSlurper
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.snakeyaml:snakeyaml-engine:2.7")
    }
}

// Root build file. Plugins are declared here (apply false) and applied per-module so the
// version catalog stays the single source of truth.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
}

// Shared by :ml source-asset verification and :app AAB-entry verification so both release
// boundaries enforce the same deployment manifest contract.
private val semanticAssetNames =
    setOf(
        "semantic_notification_classifier.tflite",
        "semantic_vocab.txt",
        "semantic_labels.txt",
        "semantic_model_manifest.json",
    )
private val semanticPayloadNames = semanticAssetNames - "semantic_model_manifest.json"
private val semanticAssetMaximumBytes =
    mapOf(
        "semantic_notification_classifier.tflite" to 45L * 1_024 * 1_024,
        "semantic_vocab.txt" to 5L * 1_024 * 1_024,
        "semantic_labels.txt" to 1_024L,
        "semantic_model_manifest.json" to 64L * 1_024,
    )
private val semanticConfidenceThresholdFloor = 0.949999988079071
private val semanticLabels =
    listOf(
        "MARKETING",
        "TRANSACTIONAL",
        "SECURITY",
        "DELIVERY",
        "SOCIAL",
        "OTHER",
        "AMBIGUOUS",
    )

private fun semanticSha256(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun semanticUtf8(bytes: ByteArray, context: String): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw IllegalStateException("$context must be valid UTF-8", error)
    }

private fun semanticObject(
    value: Any?,
    expectedFields: Set<String>,
    context: String,
): Map<String, Any?> {
    check(value is Map<*, *>) { "$context must be an object" }
    check(value.keys.all { key -> key is String }) { "$context keys must be strings" }
    val result = value.entries.associate { entry -> entry.key as String to entry.value }
    check(result.keys == expectedFields) {
        "$context fields mismatch; missing=${(expectedFields - result.keys).sorted()}, " +
            "unexpected=${(result.keys - expectedFields).sorted()}"
    }
    return result
}

private fun semanticString(
    value: Any?,
    context: String,
): String {
    check(value is String && value.isNotBlank()) { "$context must be a nonempty string" }
    return value
}

private fun semanticBoolean(
    value: Any?,
    context: String,
): Boolean {
    check(value is Boolean) { "$context must be a boolean" }
    return value
}

private fun semanticInteger(
    value: Any?,
    context: String,
): Long {
    check(value is Number) { "$context must be an integer" }
    val result = value.toLong()
    check(value.toDouble().isFinite() && value.toDouble() == result.toDouble()) {
        "$context must be an integer"
    }
    return result
}

private fun semanticPositiveBoundedInteger(
    value: Any?,
    context: String,
): Long {
    val result =
        when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> error("$context must be an integer")
        }
    check(result in 1..Int.MAX_VALUE.toLong()) {
        "$context must be within 1..${Int.MAX_VALUE}"
    }
    return result
}

private fun semanticFloat32Threshold(
    value: Any?,
    context: String,
): Double {
    check(value is Number) { "$context must be a number" }
    val result = value.toDouble()
    check(result.isFinite() && result.toFloat().toDouble() == result) {
        "$context must be finite and exactly representable as float32"
    }
    check(result in semanticConfidenceThresholdFloor..1.0) {
        "$context is below the runtime safety floor or above 1.0"
    }
    return result
}

private fun semanticSha256Value(
    value: Any?,
    context: String,
): String {
    val result = semanticString(value, context)
    check(
        result.length == 64 &&
            result.all { character -> character in '0'..'9' || character in 'a'..'f' },
    ) {
        "$context must be a lowercase SHA-256"
    }
    return result
}

private fun semanticStringList(
    value: Any?,
    context: String,
): List<String> {
    check(value is List<*> && value.all { item -> item is String }) {
        "$context must be a string array"
    }
    return value.map { item -> item as String }
}

private fun semanticIntList(
    value: Any?,
    context: String,
): List<Int> {
    check(value is List<*>) { "$context must be an integer array" }
    return value.mapIndexed { index, item ->
        semanticInteger(item, "$context[$index]").also { integer ->
            check(integer in Int.MIN_VALUE..Int.MAX_VALUE) {
                "$context[$index] is outside the integer range"
            }
        }.toInt()
    }
}

private fun verifySemanticTensor(
    value: Any?,
    expectedShape: List<Int>,
    context: String,
): String {
    val tensor =
        semanticObject(
            value,
            setOf("name", "dtype", "shape"),
            context,
        )
    val name = semanticString(tensor["name"], "$context.name")
    check(tensor["dtype"] == if (expectedShape.last() == semanticLabels.size) "float32" else "int32") {
        "$context.dtype is incompatible"
    }
    check(semanticIntList(tensor["shape"], "$context.shape") == expectedShape) {
        "$context.shape is incompatible"
    }
    return name
}

private fun verifySemanticAssetPayload(
    entries: Map<String, ByteArray>,
    allowMissingClassifier: Boolean,
): Boolean {
    val unexpected = entries.keys - semanticAssetNames
    check(unexpected.isEmpty()) {
        "Unexpected semantic assets: ${unexpected.sorted().joinToString()}"
    }
    entries.forEach { (name, bytes) ->
        check(bytes.size.toLong() in 1..semanticAssetMaximumBytes.getValue(name)) {
            "Semantic asset $name exceeds its runtime size limit"
        }
    }
    if (entries.keys != semanticAssetNames) {
        if (allowMissingClassifier && entries.keys == setOf("semantic_labels.txt")) {
            val labelLines =
                semanticUtf8(
                    entries.getValue("semantic_labels.txt"),
                    "semantic labels",
                ).replace("\r\n", "\n")
                    .split('\n')
                    .let { lines ->
                        if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
                    }
            check(
                labelLines.none(String::isEmpty) && labelLines == semanticLabels,
            ) {
                "Pre-promotion semantic labels do not match the runtime order"
            }
            return false
        }
        val missing = semanticAssetNames - entries.keys
        error(
            "Bundled semantic payload is incomplete; missing: " +
                missing.sorted().joinToString(),
        )
    }

    val manifestBytes = entries.getValue("semantic_model_manifest.json")
    check(manifestBytes.size in 1..64 * 1_024) {
        "Semantic model manifest must be within 1..65536 bytes"
    }
    val manifestValue =
        try {
            JsonSlurper().parseText(
                semanticUtf8(manifestBytes, "semantic model manifest"),
            )
        } catch (error: Exception) {
            throw IllegalStateException("Semantic model manifest must be valid JSON", error)
        }
    val manifest =
        semanticObject(
            manifestValue,
            setOf(
                "schema_version",
                "files",
                "labels",
                "max_sequence_length",
                "general_threshold",
                "marketing_threshold",
                "tokenizer",
                "conversion",
                "evidence",
                "evaluation_provenance",
            ),
            "semantic model manifest",
        )
    check(manifest["schema_version"] == "alarmcontrol-semantic-model-manifest-v2") {
        "Unsupported semantic model manifest schema"
    }
    check(semanticStringList(manifest["labels"], "semantic model manifest.labels") == semanticLabels) {
        "Semantic model manifest labels do not match the runtime order"
    }
    check(semanticInteger(manifest["max_sequence_length"], "semantic model manifest.max_sequence_length") == 128L) {
        "Semantic model manifest max_sequence_length must be 128"
    }
    val generalThreshold =
        semanticFloat32Threshold(
            manifest["general_threshold"],
            "semantic model manifest.general_threshold",
        )
    val marketingThreshold =
        semanticFloat32Threshold(
            manifest["marketing_threshold"],
            "semantic model manifest.marketing_threshold",
        )
    check(marketingThreshold >= generalThreshold) {
        "Semantic marketing threshold must be greater than or equal to the general threshold"
    }

    val tokenizer =
        semanticObject(
            manifest["tokenizer"],
            setOf("type", "normalization", "lowercase"),
            "semantic model manifest.tokenizer",
        )
    check(tokenizer["type"] == "bert-wordpiece") {
        "Semantic tokenizer type is incompatible"
    }
    check(tokenizer["normalization"] == "nfc") {
        "Semantic tokenizer normalization is incompatible"
    }
    check(!semanticBoolean(tokenizer["lowercase"], "semantic model manifest.tokenizer.lowercase")) {
        "Semantic tokenizer lowercase must be false"
    }

    val files =
        semanticObject(
            manifest["files"],
            semanticPayloadNames,
            "semantic model manifest.files",
        )
    val declaredHashes = mutableMapOf<String, String>()
    semanticPayloadNames.forEach { name ->
        val contract =
            semanticObject(
                files[name],
                setOf("sha256", "size_bytes"),
                "semantic model manifest.files.$name",
            )
        val declaredHash =
            semanticSha256Value(
                contract["sha256"],
                "semantic model manifest.files.$name.sha256",
            )
        val declaredSize =
            semanticInteger(
                contract["size_bytes"],
                "semantic model manifest.files.$name.size_bytes",
            )
        check(declaredSize in 1..semanticAssetMaximumBytes.getValue(name)) {
            "Semantic asset $name exceeds its runtime size limit"
        }
        val payload = entries.getValue(name)
        check(payload.size.toLong() == declaredSize) {
            "Semantic asset $name size does not match its manifest"
        }
        check(semanticSha256(payload) == declaredHash) {
            "Semantic asset $name SHA-256 does not match its manifest"
        }
        declaredHashes[name] = declaredHash
    }
    val labelText = semanticUtf8(entries.getValue("semantic_labels.txt"), "semantic labels")
    val normalizedLabelText = labelText.replace("\r\n", "\n")
    val labelLines =
        normalizedLabelText
            .split('\n')
            .let { lines -> if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines }
    check(labelLines.none(String::isEmpty) && labelLines == semanticLabels) {
        "Semantic labels asset does not match the runtime order"
    }

    val conversion =
        semanticObject(
            manifest["conversion"],
            setOf("quantization", "quantization_audit", "tensor_contract"),
            "semantic model manifest.conversion",
        )
    val quantization =
        semanticObject(
            conversion["quantization"],
            setOf(
                "requested",
                "applied",
                "calibration_used",
                "experimental_backend",
                "fallback_reason",
            ),
            "semantic model manifest.conversion.quantization",
        )
    val requested =
        semanticString(
            quantization["requested"],
            "semantic model manifest.conversion.quantization.requested",
        )
    val applied =
        semanticString(
            quantization["applied"],
            "semantic model manifest.conversion.quantization.applied",
        )
    check(requested in setOf("auto", "dynamic-int8", "float32")) {
        "Semantic quantization request is incompatible"
    }
    check(applied == "dynamic-int8") {
        "Deployable semantic quantization must apply dynamic-int8"
    }
    check(requested == "auto" || requested == applied) {
        "Semantic quantization requested/applied values contradict"
    }
    check(
        !semanticBoolean(
            quantization["calibration_used"],
            "semantic model manifest.conversion.quantization.calibration_used",
        ),
    ) {
        "Semantic quantization must not use calibration data"
    }
    check(
        semanticBoolean(
            quantization["experimental_backend"],
            "semantic model manifest.conversion.quantization.experimental_backend",
        ) == (applied == "dynamic-int8"),
    ) {
        "Semantic quantization experimental_backend is inconsistent"
    }
    val fallbackReason = quantization["fallback_reason"]
    check(fallbackReason == null) {
        "Semantic quantization fallback_reason must be null"
    }

    val quantizationAudit =
        semanticObject(
            conversion["quantization_audit"],
            setOf(
                "schema_version",
                "method",
                "tensor_count",
                "int8_tensor_count",
                "operator_count",
                "quantize_operator_count",
                "passed",
            ),
            "semantic model manifest.conversion.quantization_audit",
        )
    check(quantizationAudit["schema_version"] == "koelectra-dynamic-int8-audit-v1") {
        "Semantic quantization audit schema is incompatible"
    }
    check(
        quantizationAudit["method"] ==
            "litert-interpreter-tensor-and-operator-inspection",
    ) {
        "Semantic quantization audit method is incompatible"
    }
    val tensorCount =
        semanticPositiveBoundedInteger(
            quantizationAudit["tensor_count"],
            "semantic model manifest.conversion.quantization_audit.tensor_count",
        )
    val int8TensorCount =
        semanticPositiveBoundedInteger(
            quantizationAudit["int8_tensor_count"],
            "semantic model manifest.conversion.quantization_audit.int8_tensor_count",
        )
    val operatorCount =
        semanticPositiveBoundedInteger(
            quantizationAudit["operator_count"],
            "semantic model manifest.conversion.quantization_audit.operator_count",
        )
    val quantizeOperatorCount =
        semanticPositiveBoundedInteger(
            quantizationAudit["quantize_operator_count"],
            "semantic model manifest.conversion.quantization_audit.quantize_operator_count",
        )
    check(int8TensorCount <= tensorCount) {
        "Semantic quantization audit INT8 tensor count exceeds tensor count"
    }
    check(quantizeOperatorCount <= operatorCount) {
        "Semantic quantization audit QUANTIZE operator count exceeds operator count"
    }
    check(
        semanticBoolean(
            quantizationAudit["passed"],
            "semantic model manifest.conversion.quantization_audit.passed",
        ),
    ) {
        "Semantic quantization audit must pass"
    }

    val tensorContract =
        semanticObject(
            conversion["tensor_contract"],
            setOf("inputs", "output"),
            "semantic model manifest.conversion.tensor_contract",
        )
    val inputs = tensorContract["inputs"]
    check(inputs is List<*> && inputs.size in 2..3) {
        "Semantic tensor inputs must contain two or three entries"
    }
    val inputNames =
        inputs.mapIndexed { index, input ->
            verifySemanticTensor(
                input,
                listOf(1, 128),
                "semantic model manifest.conversion.tensor_contract.inputs[$index]",
            )
        }
    check(
        inputNames == listOf("input_ids", "attention_mask") ||
            inputNames == listOf("input_ids", "attention_mask", "token_type_ids"),
    ) {
        "Semantic tensor input names or order are incompatible"
    }
    verifySemanticTensor(
        tensorContract["output"],
        listOf(1, semanticLabels.size),
        "semantic model manifest.conversion.tensor_contract.output",
    )

    val evidence =
        semanticObject(
            manifest["evidence"],
            setOf(
                "conversion_manifest_sha256",
                "threshold_selection_sha256",
                "development_test_gate_sha256",
                "test_parity_report_sha256",
                "sealed_holdout_gate_sha256",
            ),
            "semantic model manifest.evidence",
        )
    evidence.forEach { (field, value) ->
        semanticSha256Value(value, "semantic model manifest.evidence.$field")
    }

    val provenance =
        semanticObject(
            manifest["evaluation_provenance"],
            setOf("threshold_selection", "development_test", "sealed_holdout"),
            "semantic model manifest.evaluation_provenance",
        )
    provenance.forEach { (entry, value) ->
        val record =
            semanticObject(
                value,
                setOf(
                    "schema_version",
                    "source_manifest_sha256",
                    "backend",
                    "model_artifact_sha256",
                    "vocab_sha256",
                ),
                "semantic model manifest.evaluation_provenance.$entry",
            )
        check(record["schema_version"] == "semantic-evaluation-provenance-v1") {
            "Semantic evaluation provenance schema is incompatible"
        }
        check(record["backend"] == "tensorflow-lite") {
            "Semantic evaluation provenance backend is incompatible"
        }
        semanticSha256Value(
            record["source_manifest_sha256"],
            "semantic model manifest.evaluation_provenance.$entry.source_manifest_sha256",
        )
        check(
            semanticSha256Value(
                record["model_artifact_sha256"],
                "semantic model manifest.evaluation_provenance.$entry.model_artifact_sha256",
            ) == declaredHashes.getValue("semantic_notification_classifier.tflite"),
        ) {
            "Semantic evaluation provenance model hash is inconsistent"
        }
        check(
            semanticSha256Value(
                record["vocab_sha256"],
                "semantic model manifest.evaluation_provenance.$entry.vocab_sha256",
            ) == declaredHashes.getValue("semantic_vocab.txt"),
        ) {
            "Semantic evaluation provenance vocabulary hash is inconsistent"
        }
    }
    return true
}

extra["semanticAssetPayloadVerifier"] =
    { entries: Map<String, ByteArray>, allowMissingClassifier: Boolean ->
        verifySemanticAssetPayload(entries, allowMissingClassifier)
    }
extra["semanticAssetSha256"] = { bytes: ByteArray -> semanticSha256(bytes) }
extra["semanticAssetMaximumBytes"] = semanticAssetMaximumBytes

// AGP lint's Kotlin analysis session is not safe when separate Android modules analyze in parallel
// (it can fail with a duplicated BuiltinsVirtualFileProvider). Keep the rest of Gradle parallel,
// but serialize only lint analysis tasks through this zero-state shared service.
abstract class AndroidLintAnalysisLock : BuildService<BuildServiceParameters.None>, AutoCloseable {
    override fun close() = Unit
}

val androidLintAnalysisLock =
    gradle.sharedServices.registerIfAbsent(
        "androidLintAnalysisLock",
        AndroidLintAnalysisLock::class,
    ) {
        maxParallelUsages.set(1)
    }

val verifyCiActionPins by tasks.registering {
    group = "verification"
    description = "Fails when a remote GitHub Action or container is not immutably pinned."

    val repositoryDirectory = layout.projectDirectory
    inputs.files(
        repositoryDirectory.asFileTree.matching {
            include("**/*.yml", "**/*.yaml", "**/Dockerfile", "**/Dockerfile.*")
            exclude(
                ".git/**",
                ".gradle/**",
                ".idea/**",
                ".kotlin/**",
                ".managed-avd/**",
                "**/__pycache__/**",
                "**/build/**",
                "**/generated/**",
                "**/node_modules/**",
                "**/out/**",
                "**/vendor/**",
            )
        },
    )

    doLast {
        val immutableAction = Regex("""[^@\s]+@[0-9a-fA-F]{40}""")
        val immutableContainer = Regex("""(?:docker://)?[^@\s]+@sha256:[0-9a-fA-F]{64}""")
        val immutableDockerfileFrontend =
            Regex(
                """(?:docker\.io/)?docker/dockerfile(?::[^@\s]+)?@sha256:[0-9a-fA-F]{64}""",
            )
        val excludedDirectoryNames =
            setOf(
                ".git",
                ".gradle",
                ".idea",
                ".kotlin",
                ".managed-avd",
                "__pycache__",
                "build",
                "generated",
                "node_modules",
                "out",
                "vendor",
            )
        val repositoryRoot = repositoryDirectory.asFile.canonicalFile
        fun yamlReferences(yaml: String): Pair<List<String>, List<String>> {
            val loadSettings =
                LoadSettings
                    .builder()
                    .setAllowDuplicateKeys(false)
                    .setAllowRecursiveKeys(false)
                    .setMaxAliasesForCollections(50)
                    .setCodePointLimit(2_000_000)
                    .build()
            val actions = mutableListOf<String>()
            val images = mutableListOf<String>()
            fun scalarReference(
                value: Any?,
                key: String,
            ): String {
                check(value is String && value.isNotBlank()) {
                    "GitHub Actions YAML '$key' values must be nonempty scalar strings"
                }
                return value
            }

            fun schemaEntries(
                value: Any?,
                context: String,
                depth: Int,
            ): List<Pair<Any?, Any?>> {
                val mapping =
                    value as? Map<*, *>
                        ?: error("GitHub Actions YAML '$context' must be a mapping")
                val active = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
                fun resolveMapping(
                    current: Map<*, *>,
                    currentDepth: Int,
                ): LinkedHashMap<Any?, Any?> {
                    check(currentDepth <= 100) {
                        "GitHub Actions YAML merge nesting exceeds 100 levels"
                    }
                    if (!active.add(current)) return linkedMapOf()
                    val resolved = linkedMapOf<Any?, Any?>()
                    current
                        .filterKeys { key -> key == "<<" }
                        .forEach { (_, child) ->
                            when (child) {
                                is Map<*, *> ->
                                    resolveMapping(child, currentDepth + 1)
                                        .forEach { (key, value) ->
                                            if (key !in resolved) resolved[key] = value
                                        }
                                is Iterable<*> ->
                                    child.forEach { merged ->
                                        val mergedMapping =
                                            merged as? Map<*, *>
                                                ?: error(
                                                    "GitHub Actions YAML merge values must be mappings",
                                                )
                                        resolveMapping(mergedMapping, currentDepth + 1)
                                            .forEach { (key, value) ->
                                                if (key !in resolved) resolved[key] = value
                                            }
                                    }
                                else ->
                                    error(
                                        "GitHub Actions YAML merge values must be mappings or lists",
                                    )
                            }
                        }
                    current
                        .filterKeys { key -> key != "<<" }
                        .forEach { (key, child) -> resolved[key] = child }
                    active.remove(current)
                    return resolved
                }
                return resolveMapping(mapping, depth).map { (key, child) -> key to child }
            }

            fun collectSteps(
                value: Any?,
                context: String,
                depth: Int,
            ) {
                val steps =
                    value as? Iterable<*>
                        ?: error("GitHub Actions YAML '$context' must be a list")
                steps.forEachIndexed { index, step ->
                    schemaEntries(step, "$context[$index]", depth + 1)
                        .filter { (key, _) -> key == "uses" }
                        .forEach { (_, child) ->
                            actions += scalarReference(child, "$context[$index].uses")
                        }
                }
            }

            fun collectWorkflow(
                jobsValue: Any?,
                depth: Int,
            ) {
                schemaEntries(jobsValue, "jobs", depth + 1).forEach { (jobId, jobValue) ->
                    val jobContext = "jobs.$jobId"
                    val jobEntries = schemaEntries(jobValue, jobContext, depth + 2)
                    jobEntries
                        .filter { (key, _) -> key == "uses" }
                        .forEach { (_, child) ->
                            actions += scalarReference(child, "$jobContext.uses")
                        }
                    jobEntries
                        .filter { (key, _) -> key == "container" }
                        .forEach { (_, child) ->
                            when (child) {
                                is String ->
                                    images += scalarReference(child, "$jobContext.container")
                                is Map<*, *> ->
                                    schemaEntries(child, "$jobContext.container", depth + 3)
                                        .filter { (key, _) -> key == "image" }
                                        .forEach { (_, image) ->
                                            images +=
                                                scalarReference(
                                                    image,
                                                    "$jobContext.container.image",
                                                )
                                        }
                                else ->
                                    error(
                                        "GitHub Actions YAML '$jobContext.container' must be " +
                                            "a scalar string or mapping",
                                    )
                            }
                        }
                    jobEntries
                        .filter { (key, _) -> key == "services" }
                        .forEach { (_, servicesValue) ->
                            schemaEntries(servicesValue, "$jobContext.services", depth + 3)
                                .forEach { (serviceId, serviceValue) ->
                                    val serviceContext = "$jobContext.services.$serviceId"
                                    schemaEntries(serviceValue, serviceContext, depth + 4)
                                        .filter { (key, _) -> key == "image" }
                                        .forEach { (_, image) ->
                                            images +=
                                                scalarReference(image, "$serviceContext.image")
                                        }
                                }
                        }
                    jobEntries
                        .filter { (key, _) -> key == "steps" }
                        .forEach { (_, steps) ->
                            collectSteps(steps, "$jobContext.steps", depth + 3)
                        }
                }
            }

            fun collectAction(
                runsValue: Any?,
                depth: Int,
            ) {
                val runsEntries = schemaEntries(runsValue, "runs", depth + 1)
                runsEntries
                    .filter { (key, _) -> key == "image" }
                    .forEach { (_, image) ->
                        images += scalarReference(image, "runs.image")
                    }
                runsEntries
                    .filter { (key, _) -> key == "steps" }
                    .forEach { (_, steps) ->
                        collectSteps(steps, "runs.steps", depth + 2)
                    }
            }

            Load(loadSettings)
                .loadAllFromString(yaml)
                .forEach { document ->
                    if (document == null) return@forEach
                    val rootEntries = schemaEntries(document, "document", 0)
                    rootEntries
                        .filter { (key, _) -> key == "jobs" }
                        .forEach { (_, jobs) -> collectWorkflow(jobs, 1) }
                    rootEntries
                        .filter { (key, _) -> key == "runs" }
                        .forEach { (_, runs) -> collectAction(runs, 1) }
                    rootEntries
                        .filter { (key, _) -> key == "steps" }
                        .forEach { (_, steps) -> collectSteps(steps, "steps", 1) }
                }
            return actions.distinct() to images.distinct()
        }
        fun referencedActions(yaml: String): List<String> = yamlReferences(yaml).first
        fun referencedImages(yaml: String): List<String> = yamlReferences(yaml).second
        fun isImmutableActionReference(action: String): Boolean =
            if (action.startsWith("docker://")) {
                immutableContainer.matches(action)
            } else {
                immutableAction.matches(action)
            }
        fun isCompositeActionManifest(file: File): Boolean =
            file.name == "action.yml" || file.name == "action.yaml"
        fun isRepositoryFile(file: File): Boolean =
            file.canonicalFile.toPath().startsWith(repositoryRoot.toPath())
        fun hasSymbolicLinkComponent(
            root: File,
            target: File,
        ): Boolean {
            val rootPath = root.absoluteFile.normalize().toPath()
            val targetPath = target.absoluteFile.normalize().toPath()
            if (!targetPath.startsWith(rootPath)) return true
            var current = rootPath
            return rootPath.relativize(targetPath).any { component ->
                current = current.resolve(component)
                Files.isSymbolicLink(current)
            }
        }
        fun isExcludedRepositoryPath(file: File): Boolean =
            file.canonicalFile
                .relativeTo(repositoryRoot)
                .toPath()
                .any { component -> component.toString() in excludedDirectoryNames }
        fun localReferenceFailure(action: String): String? {
            val target = repositoryRoot.resolve(action.removePrefix("./"))
            if (!isRepositoryFile(target)) return "local reference escapes the repository"
            if (isExcludedRepositoryPath(target)) return "local reference points into an excluded directory"
            if (hasSymbolicLinkComponent(repositoryRoot, target)) {
                return "local reference contains a symbolic link"
            }
            if (target.isDirectory) {
                val manifests =
                    listOf(target.resolve("action.yml"), target.resolve("action.yaml"))
                        .filter(File::isFile)
                return if (manifests.size == 1) {
                    null
                } else {
                    "local action directory must contain exactly one action.yml or action.yaml"
                }
            }
            val relative = target.canonicalFile.relativeTo(repositoryRoot).invariantSeparatorsPath
            return if (
                target.isFile &&
                relative.startsWith(".github/workflows/") &&
                target.extension in setOf("yml", "yaml")
            ) {
                null
            } else {
                "local reference is neither an action directory nor a reusable workflow"
            }
        }
        fun dockerContinuationCandidate(rawLine: String): String =
            rawLine.trimEnd { character -> character == ' ' || character == '\t' }

        fun hasDockerContinuation(rawLine: String): Boolean =
            dockerContinuationCandidate(rawLine)
                .takeLastWhile { character -> character == '\\' }
                .length == 1

        fun logicalDockerfileLines(dockerfile: File): List<Pair<Int, String>> {
            val lines = mutableListOf<Pair<Int, String>>()
            val current = StringBuilder()
            var instructionStart = 0
            var physicalLine = 0
            dockerfile.forEachLine { rawLine ->
                physicalLine += 1
                val continuationCandidate = dockerContinuationCandidate(rawLine)
                if (
                    continuationCandidate.trimStart().startsWith("#") ||
                    continuationCandidate.isBlank()
                ) {
                    return@forEachLine
                }
                if (current.isEmpty()) instructionStart = physicalLine
                val continued = hasDockerContinuation(rawLine)
                val segment =
                    if (continued) continuationCandidate.dropLast(1) else continuationCandidate
                current.append(if (current.isEmpty()) segment.trimStart() else segment)
                if (!continued && current.isNotEmpty()) {
                    lines += instructionStart to current.toString().trim()
                    current.clear()
                }
            }
            if (current.isNotEmpty()) lines += instructionStart to current.toString().trim()
            return lines
        }
        fun dockerfileFailures(dockerfile: File): List<String> {
            if (!dockerfile.isFile) return listOf("referenced Dockerfile does not exist")
            if (!isRepositoryFile(dockerfile)) return listOf("referenced Dockerfile escapes the repository")
            if (dockerfile.canonicalFile != dockerfile.absoluteFile.normalize()) {
                return listOf("referenced Dockerfile must not be a symbolic link")
            }
            val rawLines = dockerfile.readLines()
            var fromCount = 0
            val failures = mutableListOf<String>()
            val stageIndexes = mutableMapOf<String, Int>()
            val stageName = Regex("""[A-Za-z0-9][A-Za-z0-9_.-]*""")
            val parserDirective =
                Regex(
                    """^\s*#\s*(syntax|escape|check)\s*=\s*(\S+)\s*\z""",
                    RegexOption.IGNORE_CASE,
                )
            val activeParserDirectives = mutableListOf<Pair<String, String>>()
            for (line in rawLines) {
                if (line.isBlank()) break
                val directive = parserDirective.matchEntire(line) ?: break
                activeParserDirectives +=
                    directive.groupValues[1].lowercase() to directive.groupValues[2]
            }
            val syntaxReferences =
                activeParserDirectives
                    .filter { (name, _) -> name == "syntax" }
                    .map { (_, value) -> value }
            if (syntaxReferences.size > 1) {
                failures += "Dockerfile contains multiple syntax directives"
            }
            syntaxReferences.forEach { reference ->
                if (!immutableDockerfileFrontend.matches(reference)) {
                    failures +=
                        "Dockerfile syntax frontend is not pinned by SHA-256 digest from the " +
                        "allowed docker/dockerfile repository"
                }
            }
            val escapeValues =
                activeParserDirectives
                    .filter { (name, _) -> name == "escape" }
                    .map { (_, value) -> value }
            if (escapeValues.size > 1) {
                failures += "Dockerfile contains multiple escape directives"
            }
            if (escapeValues.any { value -> value != "\\" }) {
                failures += "Dockerfile alternate escape directives are unsupported"
            }
            if (rawLines.lastOrNull()?.let(::hasDockerContinuation) == true) {
                failures += "Dockerfile ends with an unterminated continuation"
            }

            fun externalSourceFailure(
                reference: String,
                currentStageIndex: Int,
            ): String? {
                val stageIndex = stageIndexes[reference.lowercase()]
                if (stageIndex != null && stageIndex < currentStageIndex) return null
                val numericIndex = reference.toIntOrNull()
                if (numericIndex != null && numericIndex in 0 until currentStageIndex) return null
                if (immutableContainer.matches(reference)) return null
                return "external image '$reference' is not pinned by SHA-256 digest"
            }

            fun leadingOptions(arguments: String): Pair<List<String>, String?> {
                val options = mutableListOf<String>()
                var index = 0
                while (index < arguments.length) {
                    while (index < arguments.length && arguments[index].isWhitespace()) index += 1
                    if (
                        index >= arguments.length ||
                        arguments[index] != '-' ||
                        index + 1 >= arguments.length ||
                        arguments[index + 1] != '-'
                    ) {
                        return options to null
                    }

                    val option = StringBuilder()
                    var quote: Char? = null
                    while (index < arguments.length) {
                        val character = arguments[index]
                        if (quote == null && character.isWhitespace()) break
                        when {
                            character == '\\' -> {
                                if (index + 1 >= arguments.length) {
                                    return options to
                                        "Dockerfile builder option contains a dangling escape"
                                }
                                index += 1
                                option.append(arguments[index])
                            }
                            quote == null && (character == '\'' || character == '"') -> {
                                quote = character
                            }
                            quote == character -> {
                                quote = null
                            }
                            else -> option.append(character)
                        }
                        index += 1
                    }
                    if (quote != null) {
                        return options to "Dockerfile builder option contains an unterminated quote"
                    }
                    val normalized = option.toString()
                    if (normalized == "--") return options to null
                    options += normalized
                }
                return options to null
            }

            fun csvFields(value: String): Pair<List<String>, String?> {
                if (value.isEmpty()) return emptyList<String>() to "RUN mount option is empty"
                val fields = mutableListOf<String>()
                var index = 0
                while (index <= value.length) {
                    val field = StringBuilder()
                    if (index < value.length && value[index] == '"') {
                        index += 1
                        var closed = false
                        while (index < value.length) {
                            val character = value[index]
                            if (character != '"') {
                                field.append(character)
                                index += 1
                            } else if (index + 1 < value.length && value[index + 1] == '"') {
                                field.append('"')
                                index += 2
                            } else {
                                closed = true
                                index += 1
                                break
                            }
                        }
                        if (!closed) return fields to "RUN mount CSV contains an unterminated quote"
                        if (index < value.length && value[index] != ',') {
                            return fields to "RUN mount CSV contains characters after a closing quote"
                        }
                    } else {
                        while (index < value.length && value[index] != ',') {
                            if (value[index] == '"') {
                                return fields to "RUN mount CSV contains a bare quote"
                            }
                            field.append(value[index])
                            index += 1
                        }
                    }
                    fields += field.toString()
                    if (index >= value.length) break
                    index += 1
                    if (index == value.length) {
                        fields += ""
                        break
                    }
                }
                return fields to null
            }

            logicalDockerfileLines(dockerfile).forEach { (lineNumber, line) ->
                val parsed =
                    Regex("""^([A-Za-z]+)(?:\s+(.*))?\z""")
                        .matchEntire(line)
                if (parsed == null) {
                    failures += "line $lineNumber is not a supported Dockerfile instruction"
                    return@forEach
                }
                val instruction = parsed.groupValues[1].uppercase()
                val arguments = parsed.groupValues[2].trim()
                if (arguments.contains("<<")) {
                    failures += "line $lineNumber Dockerfile heredocs are unsupported"
                    return@forEach
                }
                when (instruction) {
                    "FROM" -> {
                        val tokens =
                            arguments
                                .split(Regex("""\s+"""))
                                .filter(String::isNotEmpty)
                        val imageIndex = tokens.indexOfFirst { token -> !token.startsWith("--") }
                        val image = tokens.getOrNull(imageIndex)
                        val fromOptions = if (imageIndex >= 0) tokens.take(imageIndex) else tokens
                        if (fromOptions.any { option -> !option.startsWith("--platform=") }) {
                            failures += "line $lineNumber FROM contains an unsupported option"
                        }
                        val priorStageIndex = image?.let { stageIndexes[it.lowercase()] }
                        if (
                            image == null ||
                            (
                                !image.equals("scratch", ignoreCase = true) &&
                                    priorStageIndex == null &&
                                    !immutableContainer.matches(image)
                            )
                        ) {
                            failures += "line $lineNumber FROM image is not pinned by SHA-256 digest"
                        }
                        val suffix = if (imageIndex >= 0) tokens.drop(imageIndex + 1) else emptyList()
                        val alias =
                            when {
                                suffix.isEmpty() -> null
                                suffix.size == 2 && suffix[0].equals("AS", ignoreCase = true) -> suffix[1]
                                else -> {
                                    failures += "line $lineNumber FROM has malformed stage syntax"
                                    null
                                }
                            }
                        if (alias != null) {
                            val normalized = alias.lowercase()
                            when {
                                !stageName.matches(alias) ->
                                    failures += "line $lineNumber FROM has an invalid stage alias"
                                normalized in stageIndexes ->
                                    failures += "line $lineNumber FROM reuses a stage alias"
                                else -> stageIndexes[normalized] = fromCount
                            }
                        }
                        fromCount += 1
                    }
                    "COPY" -> {
                        val (copyOptions, optionFailure) = leadingOptions(arguments)
                        if (optionFailure != null) {
                            failures += "line $lineNumber $optionFailure"
                        }
                        val fromOptions =
                            copyOptions
                                .filter { option -> option.startsWith("--from", ignoreCase = true) }
                        if (fromOptions.size > 1) {
                            failures += "line $lineNumber COPY contains duplicate --from options"
                        }
                        fromOptions.forEach { option ->
                            val reference =
                                option
                                    .takeIf { it.startsWith("--from=", ignoreCase = true) }
                                    ?.substringAfter('=')
                            val failure =
                                if (reference.isNullOrBlank()) {
                                    "COPY has a malformed --from option"
                                } else {
                                    externalSourceFailure(reference, fromCount - 1)
                                }
                            if (failure != null) failures += "line $lineNumber $failure"
                        }
                    }
                    "ADD" -> {
                        failures +=
                            "line $lineNumber ADD is unsupported because it can fetch mutable remote sources"
                    }
                    "RUN" -> {
                        val (runOptions, optionFailure) = leadingOptions(arguments)
                        if (optionFailure != null) {
                            failures += "line $lineNumber $optionFailure"
                        }
                        runOptions
                            .filter { option -> option.startsWith("--mount", ignoreCase = true) }
                            .forEach { option ->
                                val mount =
                                    option
                                        .takeIf { it.startsWith("--mount=", ignoreCase = true) }
                                        ?.substringAfter('=')
                                if (mount == null) {
                                    failures += "line $lineNumber RUN has a malformed --mount option"
                                } else {
                                    val (mountFields, csvFailure) = csvFields(mount)
                                    if (csvFailure != null) {
                                        failures += "line $lineNumber $csvFailure"
                                    } else {
                                        val fromEntries =
                                            mountFields.filter {
                                                it.substringBefore('=').equals("from", ignoreCase = true)
                                            }
                                        if (fromEntries.size > 1) {
                                            failures +=
                                                "line $lineNumber RUN mount contains duplicate from entries"
                                        }
                                        fromEntries.forEach { entry ->
                                            val reference = entry.substringAfter('=', "").trim()
                                            val failure =
                                                if (reference.isBlank()) {
                                                    "RUN mount has a malformed from entry"
                                                } else {
                                                    externalSourceFailure(reference, fromCount - 1)
                                                }
                                            if (failure != null) failures += "line $lineNumber $failure"
                                        }
                                    }
                                }
                            }
                    }
                    "ONBUILD" -> {
                        failures +=
                            "line $lineNumber ONBUILD external sources are unsupported because " +
                            "deferred instructions cannot be resolved safely"
                    }
                }
            }
            if (fromCount == 0) failures += "Dockerfile contains no FROM instruction"
            return failures
        }
        fun localDockerfileFailures(
            yaml: File,
            image: String,
        ): List<String>? {
            if (!isCompositeActionManifest(yaml)) return null
            val normalized = image.removePrefix("./")
            if (normalized != "Dockerfile" && !normalized.startsWith("Dockerfile.")) return null
            val dockerfile = yaml.parentFile.resolve(normalized)
            if (dockerfile.parentFile.canonicalFile != yaml.parentFile.canonicalFile) {
                return listOf("referenced Dockerfile escapes the local action directory")
            }
            return dockerfileFailures(dockerfile)
        }

        check(
            referencedActions(
                """
                steps:
                  - { name: Checkout, uses : actions/checkout@v4 }
                  - "uses": 'docker://alpine:latest'
                """.trimIndent(),
            ) == listOf("actions/checkout@v4", "docker://alpine:latest"),
        ) {
            "GitHub Action pin scanner must recognize block and flow YAML mappings"
        }
        val escapedUsesKey = "\\" + "u0075ses"
        val escapedImageKey = "\\" + "u0069mage"
        check(
            referencedActions(
                """
                steps:
                  - "$escapedUsesKey": actions/checkout@v4
                """.trimIndent(),
            ) == listOf("actions/checkout@v4") &&
                referencedImages(
                    """
                    runs:
                      "$escapedImageKey": Dockerfile
                    """.trimIndent(),
                ) == listOf("Dockerfile"),
        ) {
            "GitHub Action pin scanner must normalize escaped YAML keys"
        }
        check(
            referencedImages(
                """
                inputs:
                  image:
                    description: A caller-provided label, not a container image
                  uses:
                    description: Another ordinary action input
                runs:
                  using: composite
                  steps:
                    - uses: actions/checkout@v4
                      with:
                        image: ubuntu:latest
                """.trimIndent(),
            ).isEmpty(),
        ) {
            "GitHub Action pin scanner must ignore inputs.image and with.image outside image schemas"
        }
        check(
            referencedActions(
                """
                steps:
                  - ? uses
                    : >-
                      actions/checkout@v4
                """.trimIndent(),
            ) == listOf("actions/checkout@v4"),
        ) {
            "GitHub Action pin scanner must recognize explicit keys and block scalars"
        }
        check(
            referencedActions(
                """
                action-key: &action-key uses
                steps:
                  - ? *action-key
                    : actions/checkout@v4
                """.trimIndent(),
            ) == listOf("actions/checkout@v4"),
        ) {
            "GitHub Action pin scanner must resolve aliases used as keys"
        }
        check(
            referencedActions(
                """
                default-step: &default-step
                  uses: actions/checkout@v4
                steps:
                  - <<: *default-step
                """.trimIndent(),
            ) == listOf("actions/checkout@v4"),
        ) {
            "GitHub Action pin scanner must safely resolve YAML merge keys"
        }
        check(
            referencedActions(
                """
                default-step: &default-step
                  uses: actions/checkout@v4
                steps:
                  - <<: *default-step
                    uses: actions/checkout@${"a".repeat(40)}
                """.trimIndent(),
            ) == listOf("actions/checkout@${"a".repeat(40)}"),
        ) {
            "GitHub Action pin scanner must honor explicit values over YAML merge defaults"
        }
        check(
            runCatching {
                referencedActions(
                    """
                    steps:
                      - uses: actions/checkout@${"a".repeat(40)}
                        uses: actions/checkout@v4
                    """.trimIndent(),
                )
            }.isFailure,
        ) {
            "GitHub Action pin scanner must reject duplicate YAML keys"
        }
        check(
            runCatching {
                referencedActions("uses: !untrusted actions/checkout@v4")
            }.isFailure,
        ) {
            "GitHub Action pin scanner must reject unrecognized YAML tags"
        }
        check(
            isCompositeActionManifest(repositoryRoot.resolve("ci/action.yml")) &&
                localReferenceFailure("./vendor/ignored-action")
                    ?.contains("excluded directory") == true,
        ) {
            "Repository-wide composite scanning must not trust local actions hidden in excluded directories"
        }
        check(
            !isImmutableActionReference(
                "docker://alpine@aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ) &&
                isImmutableActionReference(
                    "docker://alpine@sha256:" +
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
        ) {
            "Docker actions must require a SHA-256 image digest, not a Git-style commit pin"
        }
        check(
            referencedImages(
                """
                jobs:
                  test:
                    container: ubuntu:latest
                    services: { db: { image : 'postgres:16' } }
                  pinned:
                    container:
                      image: ghcr.io/example/tool@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                runs:
                  image: Dockerfile
                """.trimIndent(),
            ) ==
                listOf(
                    "ubuntu:latest",
                    "postgres:16",
                    "ghcr.io/example/tool@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "Dockerfile",
                ),
        ) {
            "GitHub container pin scanner must recognize image keys and scalar containers"
        }
        val pinnedDockerfile = temporaryDir.resolve("pinned.Dockerfile")
        pinnedDockerfile.writeText(
            """
            # syntax=docker/dockerfile@sha256:${"b".repeat(64)}
            # escape=\
            FROM alpine@sha256:${"a".repeat(64)} AS base
            FROM base AS final
            COPY --from=base /bin/sh /copy-by-name
            COPY --from=0 /bin/sh /copy-by-index
            COPY --from=busybox@sha256:${"c".repeat(64)} /bin/sh /copy-by-digest
            RUN --mount=type=bind,from=base,source=/,target=/base true
            RUN --mount=type=bind,from=0,source=/,target=/index true
            RUN --mount=type=bind,from=busybox@sha256:${"d".repeat(64)},source=/,target=/digest true
            COPY --fr"om"=base /bin/sh /copy-by-quoted-name
            COPY --fr\om=0 /bin/sh /copy-by-escaped-name
            RUN --mo"unt"=type=bind,fr"om"=base,source=/,target=/quoted-key true
            RUN --mo\unt=type=bind,from=0,source=/,target=/escaped-name true
            RUN --mount=type=bind,\"from=base\",source=/,target=/csv-quoted-key true
            """.trimIndent(),
        )
        val pinnedDockerfileFailures = dockerfileFailures(pinnedDockerfile)
        check(pinnedDockerfileFailures.isEmpty()) {
            "Local Docker action scanner must accept pinned frontends/images and prior local stages: " +
                pinnedDockerfileFailures.joinToString()
        }
        val mutableDockerfile = temporaryDir.resolve("mutable.Dockerfile")
        mutableDockerfile.writeText(
            """
            # syntax=docker/dockerfile:1
            FROM alpine:latest AS base
            FROM scratch
            COPY --from=nginx:latest /bin/nginx /nginx
            RUN --mount=type=bind,from=ubuntu:latest,source=/,target=/host true
            """.trimIndent(),
        )
        val mutableDockerfileFailures = dockerfileFailures(mutableDockerfile)
        check(
            mutableDockerfileFailures.any { it.contains("syntax frontend is not pinned") } &&
                mutableDockerfileFailures.any { it.contains("FROM image is not pinned") } &&
                mutableDockerfileFailures.any { it.contains("nginx:latest") } &&
                mutableDockerfileFailures.any { it.contains("ubuntu:latest") },
        ) {
            "Local Docker action scanner must reject mutable FROM, COPY, RUN, and syntax images"
        }
        val spacedDirectiveDockerfile = temporaryDir.resolve("spaced-directive.Dockerfile")
        spacedDirectiveDockerfile.writeText(
            """
            # syntax = docker/dockerfile:1
            FROM scratch
            """.trimIndent(),
        )
        check(
            dockerfileFailures(spacedDirectiveDockerfile)
                .any { it.contains("syntax frontend is not pinned") },
        ) {
            "Local Docker action scanner must apply Docker's parser-directive whitespace rules"
        }
        val customFrontendDockerfile = temporaryDir.resolve("custom-frontend.Dockerfile")
        customFrontendDockerfile.writeText(
            """
            # syntax=ghcr.io/example/custom@sha256:${"e".repeat(64)}
            FROM scratch
            IMAGE alpine:latest
            """.trimIndent(),
        )
        check(
            dockerfileFailures(customFrontendDockerfile)
                .any { it.contains("allowed docker/dockerfile repository") },
        ) {
            "Local Docker action scanner must reject custom frontends with unrecognized semantics"
        }
        val dynamicDockerfile = temporaryDir.resolve("dynamic.Dockerfile")
        dynamicDockerfile.writeText(
            """
            FROM scratch AS base
            COPY --from=${'$'}{IMAGE} /source /target
            RUN --mount=type=bind,from=unknown,source=/,target=/unknown true
            RUN --mount=type=bind,from=0,from=1,source=/,target=/duplicate true
            ADD https://example.com/tool /target
            ONBUILD COPY --from=base /source /target
            """.trimIndent(),
        )
        val dynamicDockerfileFailures = dockerfileFailures(dynamicDockerfile)
        check(
                dynamicDockerfileFailures.any { it.contains("\${IMAGE}") } &&
                dynamicDockerfileFailures.any { it.contains("unknown") } &&
                dynamicDockerfileFailures.any { it.contains("duplicate from") } &&
                dynamicDockerfileFailures.any { it.contains("ADD is unsupported") } &&
                dynamicDockerfileFailures.any { it.contains("ONBUILD external sources") },
        ) {
            "Local Docker action scanner must reject dynamic, unknown, duplicate, and deferred sources"
        }
        val normalizedOptionsDockerfile = temporaryDir.resolve("normalized-options.Dockerfile")
        normalizedOptionsDockerfile.writeText(
            """
            FROM scratch
            COPY --fr"om"=nginx:latest /source /quoted-copy
            COPY --fr\om=busybox:latest /source /escaped-copy
            RUN --mo"unt"=type=bind,from=ubuntu:latest,source=/,target=/quoted-mount true
            RUN --mo\unt=type=bind,from=debian:latest,source=/,target=/escaped-mount true
            RUN --mount=type=bind,fr"om"=fedora:latest,source=/,target=/quoted-key true
            RUN --mount=type=bind,from=alma"linux:latest",source=/,target=/quoted-value true
            RUN --mount=type=bind,\"from=rockylinux:latest\",source=/,target=/csv-quoted-key true
            """.trimIndent(),
        )
        val normalizedOptionsFailures = dockerfileFailures(normalizedOptionsDockerfile)
        check(
            listOf(
                "nginx:latest",
                "busybox:latest",
                "ubuntu:latest",
                "debian:latest",
                "fedora:latest",
                "almalinux:latest",
                "rockylinux:latest",
            ).all { reference ->
                normalizedOptionsFailures.any { failure -> failure.contains(reference) }
            },
        ) {
            "Local Docker action scanner must normalize quoted and escaped builder flags and mount CSV: " +
                normalizedOptionsFailures.joinToString()
        }
        val splicedDockerfile = temporaryDir.resolve("spliced.Dockerfile")
        splicedDockerfile.writeText(
            """
            FR\
            # a comment cannot hide a continued instruction
            OM alpine:latest AS base
            FROM scratch
            COPY --fr\
            # nor can it hide a continued option
            om=nginx:latest /source /target
            """.trimIndent(),
        )
        val splicedDockerfileFailures = dockerfileFailures(splicedDockerfile)
        check(
            splicedDockerfileFailures.any { it.contains("FROM image is not pinned") } &&
                splicedDockerfileFailures.any { it.contains("nginx:latest") },
        ) {
            "Local Docker action scanner must inspect tokens split across continuations"
        }
        val escapedContinuationDockerfile = temporaryDir.resolve("escaped-continuation.Dockerfile")
        escapedContinuationDockerfile.writeText(
            """
            FROM scratch
            RUN echo escaped-backslash \\
            FROM alpine:latest
            """.trimIndent(),
        )
        check(
            dockerfileFailures(escapedContinuationDockerfile)
                .any { it.contains("FROM image is not pinned") },
        ) {
            "Local Docker action scanner must not treat an escaped trailing escape as a continuation"
        }
        val tripleEscapeDockerfile = temporaryDir.resolve("triple-escape.Dockerfile")
        tripleEscapeDockerfile.writeText(
            """
            FROM scratch
            RUN echo triple-backslash \\\
            FROM alpine
            """.trimIndent(),
        )
        check(
            dockerfileFailures(tripleEscapeDockerfile)
                .any { it.contains("FROM image is not pinned") },
        ) {
            "Local Docker action scanner must match BuildKit's current three-escape behavior"
        }
        val indentedContinuationDockerfile =
            temporaryDir.resolve("indented-continuation.Dockerfile")
        indentedContinuationDockerfile.writeText(
            """
            FROM scratch
            FROM\
             alpine
            """.trimIndent(),
        )
        check(
            dockerfileFailures(indentedContinuationDockerfile)
                .any { it.contains("FROM image is not pinned") },
        ) {
            "Local Docker action scanner must preserve whitespace on continuation lines"
        }
        val unicodeWhitespaceDockerfile =
            temporaryDir.resolve("unicode-whitespace.Dockerfile")
        unicodeWhitespaceDockerfile.writeText(
            "FROM scratch\n" +
                "RUN echo unicode-space \\" +
                '\u00a0' +
                "\nFROM alpine\n",
        )
        check(
            dockerfileFailures(unicodeWhitespaceDockerfile)
                .any { it.contains("FROM image is not pinned") },
        ) {
            "Local Docker action scanner must only trim Docker-supported continuation whitespace"
        }
        val terminalNonContinuationDockerfile =
            temporaryDir.resolve("terminal-non-continuation.Dockerfile")
        terminalNonContinuationDockerfile.writeText(
            "FROM scratch\n" +
                "RUN echo escaped-backslash \\\\\n" +
                "RUN echo unicode-space \\" +
                '\u00a0' +
                "\n",
        )
        check(dockerfileFailures(terminalNonContinuationDockerfile).isEmpty()) {
            "Local Docker action scanner must not report non-continuation terminal escapes"
        }
        val alternateEscapeDockerfile = temporaryDir.resolve("alternate-escape.Dockerfile")
        alternateEscapeDockerfile.writeText(
            """
            # escape=`
            FROM scratch
            """.trimIndent(),
        )
        check(
            dockerfileFailures(alternateEscapeDockerfile)
                .any { it.contains("alternate escape directives are unsupported") },
        ) {
            "Local Docker action scanner must reject unsupported alternate escape directives"
        }
        val inactiveDirectiveDockerfile = temporaryDir.resolve("inactive-directive.Dockerfile")
        inactiveDirectiveDockerfile.writeText(
            """
            FROM scratch
            # syntax=docker/dockerfile:1
            # escape=`
            """.trimIndent(),
        )
        check(dockerfileFailures(inactiveDirectiveDockerfile).isEmpty()) {
            "Local Docker action scanner must ignore parser-like comments after the first instruction"
        }
        val heredocDockerfile = temporaryDir.resolve("heredoc.Dockerfile")
        heredocDockerfile.writeText(
            """
            FROM scratch
            COPY <<EOF /note
            FROM scratch AS nginx
            EOF
            FROM scratch
            COPY --from=nginx /bin/sh /bin/sh
            """.trimIndent(),
        )
        check(
            dockerfileFailures(heredocDockerfile)
                .any { it.contains("Dockerfile heredocs are unsupported") },
        ) {
            "Local Docker action scanner must fail closed before heredoc bodies can create fake stages"
        }
        val symlinkFixtureRoot =
            Files.createTempDirectory(temporaryDir.toPath(), "symlink-fixture-").toFile()
        val symlinkFixtureTarget = symlinkFixtureRoot.resolve("actual")
        Files.createDirectories(symlinkFixtureTarget.toPath())
        val symlinkFixtureLink = symlinkFixtureRoot.resolve("linked")
        Files.createSymbolicLink(
            symlinkFixtureLink.toPath(),
            symlinkFixtureTarget.toPath(),
        )
        check(
            hasSymbolicLinkComponent(
                symlinkFixtureRoot,
                symlinkFixtureLink.resolve("action.yml"),
            ),
        ) {
            "GitHub Action pin scanner must reject symbolic-link path components"
        }
        val repositoryPath = repositoryRoot.toPath()
        val githubPath = repositoryPath.resolve(".github")
        val yamlFiles = mutableListOf<File>()
        val unsafeYamlPaths = mutableListOf<Path>()
        Files.walkFileTree(
            repositoryPath,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult =
                    if (
                        directory != repositoryPath &&
                        directory.fileName.toString() in excludedDirectoryNames
                    ) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }

                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    val candidate =
                        file.fileName.toString() in setOf("action.yml", "action.yaml") ||
                            (
                                file.startsWith(githubPath) &&
                                    file.fileName.toString().substringAfterLast('.', "") in
                                    setOf("yml", "yaml")
                            )
                    if (Files.isSymbolicLink(file)) {
                        if (file.startsWith(githubPath) || candidate) unsafeYamlPaths.add(file)
                    } else if (attributes.isRegularFile && candidate) {
                        yamlFiles += file.toFile()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    error: java.io.IOException,
                ): FileVisitResult {
                    throw GradleException("Unable to inspect repository path $file", error)
                }
            },
        )
        check(unsafeYamlPaths.isEmpty()) {
            "GitHub workflow/action scan paths must not contain symbolic links: " +
                unsafeYamlPaths.joinToString { path -> repositoryPath.relativize(path).toString() }
        }
        check(yamlFiles.any { file -> file.parentFile.name == "workflows" }) {
            "No GitHub Actions workflows were found"
        }
        val unsafeYamlFiles =
            yamlFiles.filter { yaml ->
                !isRepositoryFile(yaml) ||
                    yaml.canonicalFile != yaml.absoluteFile.normalize()
            }
        check(unsafeYamlFiles.isEmpty()) {
            "GitHub workflow/action manifests must be regular repository files: " +
                unsafeYamlFiles.joinToString { yaml -> yaml.relativeTo(repositoryRoot).path }
        }
        val referencesByFile =
            yamlFiles.associateWith { yaml ->
                try {
                    yamlReferences(yaml.readText())
                } catch (error: Exception) {
                    throw GradleException(
                        "Unable to safely parse ${yaml.relativeTo(repositoryRoot)}",
                        error,
                    )
                }
            }
        val unpinnedActions =
            referencesByFile.flatMap { (yaml, references) ->
                references.first
                    .asSequence()
                    .mapNotNull { action ->
                        when {
                            action.startsWith("./") ->
                                localReferenceFailure(action)?.let { failure ->
                                    "${yaml.relativeTo(repositoryRoot)}:$action ($failure)"
                                }
                            isImmutableActionReference(action) -> null
                            else -> "${yaml.relativeTo(repositoryRoot)}:$action"
                        }
                    }
                    .toList()
            }
        val unpinnedImages =
            referencesByFile.flatMap { (yaml, references) ->
                references.second
                    .asSequence()
                    .flatMap { image ->
                        when {
                            immutableContainer.matches(image) -> emptySequence()
                            else -> {
                                val localFailures = localDockerfileFailures(yaml, image)
                                if (localFailures == null) {
                                    sequenceOf("${yaml.relativeTo(repositoryRoot)}:$image")
                                } else {
                                    localFailures
                                        .asSequence()
                                        .map { failure ->
                                            "${yaml.relativeTo(repositoryRoot)}:$image ($failure)"
                                        }
                                }
                            }
                        }
                    }
                    .toList()
            }
        val unpinned = unpinnedActions + unpinnedImages
        check(unpinned.isEmpty()) {
            "GitHub Actions must use 40-character commit SHAs and containers must use SHA-256 digests: " +
                unpinned.joinToString()
        }
    }
}

// Code quality is applied to every module from here so the rules are uniform and live in one place.
// Both plugins auto-wire into `check` (and therefore `build`), so `./gradlew build` enforces them.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel = true
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        filter { exclude { entry -> entry.file.path.contains("/build/") } }
    }

    tasks.configureEach {
        if (name.startsWith("lintAnalyze") || name.startsWith("lintVitalAnalyze")) {
            usesService(androidLintAnalysisLock)
        }
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyCiActionPins"))
    }
}
