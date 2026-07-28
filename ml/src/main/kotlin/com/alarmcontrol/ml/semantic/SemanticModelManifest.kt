package com.alarmcontrol.ml.semantic

import com.alarmcontrol.core.filtering.SemanticIntent
import org.json.JSONArray
import org.json.JSONObject

/** Validated deployment contract binding the bundled encoder and its three sidecars. */
internal data class SemanticModelManifest(
    val model: SemanticAssetContract,
    val vocabulary: SemanticAssetContract,
    val labelsAsset: SemanticAssetContract,
    val labels: List<SemanticIntent>,
    val maxSequenceLength: Int,
    val confidenceThresholds: SemanticConfidenceThresholds,
    val inputNames: List<String>,
) {
    companion object {
        fun parse(serialized: String): SemanticModelManifest? =
            try {
                parseStrict(JSONObject(serialized))
            } catch (_: LinkageError) {
                null
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }

        private fun parseStrict(root: JSONObject): SemanticModelManifest {
            root.requireFields(ROOT_FIELDS)
            require(root.getString("schema_version") == SCHEMA_VERSION)
            val labels = root.semanticLabels()
            val maxSequenceLength = root.getInt("max_sequence_length")
            require(maxSequenceLength == EXPECTED_MAX_SEQUENCE_LENGTH)
            val confidenceThresholds =
                SemanticConfidenceThresholds(
                    general = root.requireFloat32Threshold("general_threshold"),
                    marketing = root.requireFloat32Threshold("marketing_threshold"),
                )
            root.requireTokenizerContract()
            val assets = root.assetContracts()
            val inputNames = root.requireConversionContract()
            root.requireEvidenceContract(
                model = assets.model,
                vocabulary = assets.vocabulary,
            )
            return SemanticModelManifest(
                model = assets.model,
                vocabulary = assets.vocabulary,
                labelsAsset = assets.labels,
                labels = labels,
                maxSequenceLength = maxSequenceLength,
                confidenceThresholds = confidenceThresholds,
                inputNames = inputNames,
            )
        }

        private fun JSONObject.semanticLabels(): List<SemanticIntent> =
            getJSONArray("labels")
                .strings()
                .map(SemanticIntent::valueOf)
                .also { require(it == SemanticIntent.entries.toList()) }

        private fun JSONObject.requireTokenizerContract() {
            getJSONObject("tokenizer").apply {
                requireFields(TOKENIZER_FIELDS)
                require(getString("type") == "bert-wordpiece")
                require(getString("normalization") == "nfc")
                require(!getBoolean("lowercase"))
            }
        }

        private fun JSONObject.assetContracts(): SemanticFileContracts {
            val files = getJSONObject("files")
            files.requireFields(FILE_NAMES)
            val model = files.assetContract(MODEL_ASSET)
            require(model.sizeBytes in 1..MAX_MODEL_BYTES)
            val vocabulary = files.assetContract(VOCAB_ASSET)
            require(vocabulary.sizeBytes in 1..MAX_VOCAB_BYTES)
            val labelsAsset = files.assetContract(LABELS_ASSET)
            require(labelsAsset.sizeBytes in 1..MAX_LABELS_BYTES)
            return SemanticFileContracts(model, vocabulary, labelsAsset)
        }

        private fun JSONObject.requireConversionContract(): List<String> {
            val conversion = getJSONObject("conversion")
            conversion.requireFields(CONVERSION_FIELDS)
            conversion.getJSONObject("quantization").apply {
                requireFields(QUANTIZATION_FIELDS)
                val requested = getString("requested")
                val applied = getString("applied")
                require(requested in setOf("auto", "dynamic-int8", "float32"))
                require(applied == "dynamic-int8")
                require(requested == "auto" || requested == applied)
                require(!getBoolean("calibration_used"))
                require(getBoolean("experimental_backend"))
                require(isNull("fallback_reason"))
            }
            conversion.getJSONObject("quantization_audit").apply {
                requireFields(QUANTIZATION_AUDIT_FIELDS)
                require(getString("schema_version") == QUANTIZATION_AUDIT_SCHEMA)
                require(getString("method") == QUANTIZATION_AUDIT_METHOD)
                val tensorCount = requirePositiveBoundedInteger("tensor_count")
                val int8TensorCount = requirePositiveBoundedInteger("int8_tensor_count")
                val operatorCount = requirePositiveBoundedInteger("operator_count")
                val quantizeOperatorCount =
                    requirePositiveBoundedInteger("quantize_operator_count")
                require(int8TensorCount <= tensorCount)
                require(quantizeOperatorCount <= operatorCount)
                require(get("passed") == true)
            }
            val tensorContract = conversion.getJSONObject("tensor_contract")
            tensorContract.requireFields(TENSOR_CONTRACT_FIELDS)
            val inputs = tensorContract.getJSONArray("inputs")
            require(inputs.length() in 2..MAX_INPUT_TENSOR_COUNT)
            val inputNames =
                List(inputs.length()) { index ->
                    inputs.getJSONObject(index).requireTensor(
                        expectedShape = listOf(1, EXPECTED_MAX_SEQUENCE_LENGTH),
                        expectedDtype = "int32",
                    )
                }
            require(
                inputNames == listOf("input_ids", "attention_mask") ||
                    inputNames == listOf("input_ids", "attention_mask", "token_type_ids"),
            )
            val outputName =
                tensorContract
                    .getJSONObject("output")
                    .requireTensor(
                        expectedShape = listOf(1, SemanticIntent.entries.size),
                        expectedDtype = "float32",
                    )
            require(outputName.isNotBlank())
            return inputNames
        }

        private fun JSONObject.requireEvidenceContract(
            model: SemanticAssetContract,
            vocabulary: SemanticAssetContract,
        ) {
            getJSONObject("evidence").apply {
                requireFields(EVIDENCE_FIELDS)
                EVIDENCE_FIELDS.forEach { field -> requireSha256(getString(field)) }
            }
            getJSONObject("evaluation_provenance").apply {
                requireFields(EVALUATION_PROVENANCE_ENTRIES)
                EVALUATION_PROVENANCE_ENTRIES.forEach { entry ->
                    getJSONObject(entry).apply {
                        requireFields(EVALUATION_PROVENANCE_FIELDS)
                        require(getString("schema_version") == EVALUATION_PROVENANCE_SCHEMA)
                        require(getString("backend") == "tensorflow-lite")
                        requireSha256(getString("source_manifest_sha256"))
                        require(getString("model_artifact_sha256") == model.sha256)
                        require(getString("vocab_sha256") == vocabulary.sha256)
                    }
                }
            }
        }

        private fun JSONObject.assetContract(name: String): SemanticAssetContract {
            val value = getJSONObject(name)
            value.requireFields(ASSET_FIELDS)
            val sha256 = value.getString("sha256")
            requireSha256(sha256)
            val sizeBytes = value.getLong("size_bytes")
            require(sizeBytes > 0)
            return SemanticAssetContract(sha256, sizeBytes)
        }

        private fun JSONObject.requireTensor(
            expectedShape: List<Int>,
            expectedDtype: String,
        ): String {
            requireFields(TENSOR_FIELDS)
            require(getString("dtype") == expectedDtype)
            require(getJSONArray("shape").ints() == expectedShape)
            return getString("name").also { require(it.isNotBlank()) }
        }

        private fun JSONObject.requireFields(expected: Set<String>) {
            val actual = keys().asSequence().toSet()
            require(actual == expected)
        }

        private fun JSONObject.requirePositiveBoundedInteger(name: String): Int {
            val value = get(name)
            val result =
                when (value) {
                    is Int -> value.toLong()
                    is Long -> value
                    else -> error("$name must be an integer")
                }
            require(result in 1..Int.MAX_VALUE.toLong())
            return result.toInt()
        }

        private fun JSONObject.requireFloat32Threshold(name: String): Float {
            val value = get(name)
            require(value is Number)
            val threshold = value.toDouble()
            val floatThreshold = threshold.toFloat()
            require(
                threshold.isFinite() &&
                    floatThreshold.isFinite() &&
                    floatThreshold.toDouble() == threshold &&
                    threshold in MINIMUM_CONFIDENCE_THRESHOLD..1.0,
            )
            return floatThreshold
        }

        private fun JSONArray.strings(): List<String> = List(length()) { index -> getString(index) }

        private fun JSONArray.ints(): List<Int> = List(length()) { index -> getInt(index) }

        private fun requireSha256(value: String) {
            require(value.length == SHA256_LENGTH)
            require(value.all { it in '0'..'9' || it in 'a'..'f' })
        }

        private const val SCHEMA_VERSION = "alarmcontrol-semantic-model-manifest-v2"
        private const val EXPECTED_MAX_SEQUENCE_LENGTH = 128
        private const val MINIMUM_CONFIDENCE_THRESHOLD = 0.949999988079071
        private const val MAX_INPUT_TENSOR_COUNT = 3
        private const val SHA256_LENGTH = 64
        private const val MAX_MODEL_BYTES = 45L * 1_024 * 1_024
        private const val MAX_VOCAB_BYTES = 5L * 1_024 * 1_024
        private const val MAX_LABELS_BYTES = 1_024L
        private const val MODEL_ASSET = "semantic_notification_classifier.tflite"
        private const val VOCAB_ASSET = "semantic_vocab.txt"
        private const val LABELS_ASSET = "semantic_labels.txt"
        private val FILE_NAMES = setOf(MODEL_ASSET, VOCAB_ASSET, LABELS_ASSET)
        private val ROOT_FIELDS =
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
            )
        private val TOKENIZER_FIELDS = setOf("type", "normalization", "lowercase")
        private val ASSET_FIELDS = setOf("sha256", "size_bytes")
        private val CONVERSION_FIELDS =
            setOf("quantization", "quantization_audit", "tensor_contract")
        private val QUANTIZATION_FIELDS =
            setOf(
                "requested",
                "applied",
                "calibration_used",
                "experimental_backend",
                "fallback_reason",
            )
        private const val QUANTIZATION_AUDIT_SCHEMA = "koelectra-dynamic-int8-audit-v1"
        private const val QUANTIZATION_AUDIT_METHOD =
            "litert-interpreter-tensor-and-operator-inspection"
        private val QUANTIZATION_AUDIT_FIELDS =
            setOf(
                "schema_version",
                "method",
                "tensor_count",
                "int8_tensor_count",
                "operator_count",
                "quantize_operator_count",
                "passed",
            )
        private val TENSOR_CONTRACT_FIELDS = setOf("inputs", "output")
        private val TENSOR_FIELDS = setOf("name", "dtype", "shape")
        private val EVIDENCE_FIELDS =
            setOf(
                "conversion_manifest_sha256",
                "threshold_selection_sha256",
                "development_test_gate_sha256",
                "test_parity_report_sha256",
                "sealed_holdout_gate_sha256",
            )
        private const val EVALUATION_PROVENANCE_SCHEMA = "semantic-evaluation-provenance-v1"
        private val EVALUATION_PROVENANCE_ENTRIES =
            setOf("threshold_selection", "development_test", "sealed_holdout")
        private val EVALUATION_PROVENANCE_FIELDS =
            setOf(
                "schema_version",
                "source_manifest_sha256",
                "backend",
                "model_artifact_sha256",
                "vocab_sha256",
            )
    }
}

private data class SemanticFileContracts(
    val model: SemanticAssetContract,
    val vocabulary: SemanticAssetContract,
    val labels: SemanticAssetContract,
)

internal data class SemanticConfidenceThresholds(
    val general: Float,
    val marketing: Float,
) {
    init {
        require(general.isFinite() && general in MINIMUM_THRESHOLD..1f)
        require(marketing.isFinite() && marketing in MINIMUM_THRESHOLD..1f)
        require(marketing >= general)
    }

    private companion object {
        const val MINIMUM_THRESHOLD = 0.95f
    }
}

internal data class SemanticAssetContract(
    val sha256: String,
    val sizeBytes: Long,
)
