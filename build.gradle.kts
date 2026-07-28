import groovy.json.JsonSlurper
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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

    val githubDirectory = layout.projectDirectory.dir(".github")
    inputs.dir(githubDirectory)

    doLast {
        val usesPattern = Regex("""(?m)^\s*(?:-\s*)?uses:\s+([^\s#]+)""")
        val immutableAction = Regex("""[^@\s]+@[0-9a-fA-F]{40}""")
        val immutableContainer = Regex("""docker://[^@\s]+@sha256:[0-9a-fA-F]{64}""")
        val yamlFiles =
            githubDirectory.asFile
                .walkTopDown()
                .filter { file -> file.isFile && file.extension in setOf("yml", "yaml") }
                .toList()
        check(yamlFiles.any { file -> file.parentFile.name == "workflows" }) {
            "No GitHub Actions workflows were found"
        }
        val unpinned =
            yamlFiles.flatMap { yaml ->
                usesPattern
                    .findAll(yaml.readText())
                    .map { match -> match.groupValues[1] }
                    .filterNot { action ->
                        action.startsWith("./") ||
                            immutableContainer.matches(action) ||
                            immutableAction.matches(action)
                    }.map { action -> "${yaml.relativeTo(githubDirectory.asFile)}:$action" }
                    .toList()
            }
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
