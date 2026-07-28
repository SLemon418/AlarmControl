package com.alarmcontrol.ml.semantic

import com.alarmcontrol.core.filtering.SemanticIntent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticModelManifestTest {
    @Test
    fun `parses exact packaged contract`() {
        val manifest = requireNotNull(SemanticModelManifest.parse(validManifest().toString()))

        assertEquals(SemanticIntent.entries.toList(), manifest.labels)
        assertEquals(128, manifest.maxSequenceLength)
        assertEquals(0.95f, manifest.confidenceThresholds.general)
        assertEquals(1f, manifest.confidenceThresholds.marketing)
        assertEquals(listOf("input_ids", "attention_mask"), manifest.inputNames)
        assertEquals(1024L, manifest.model.sizeBytes)
    }

    @Test
    fun `accepts inclusive threshold boundaries`() {
        val minimum =
            requireNotNull(
                SemanticModelManifest.parse(
                    validManifest()
                        .put("general_threshold", 0.949999988079071)
                        .put("marketing_threshold", 0.949999988079071)
                        .toString(),
                ),
            )
        val maximum =
            requireNotNull(
                SemanticModelManifest.parse(
                    validManifest()
                        .put("general_threshold", 1.0)
                        .put("marketing_threshold", 1.0)
                        .toString(),
                ),
            )

        assertEquals(0.95f, minimum.confidenceThresholds.general)
        assertEquals(0.95f, minimum.confidenceThresholds.marketing)
        assertEquals(1f, maximum.confidenceThresholds.general)
        assertEquals(1f, maximum.confidenceThresholds.marketing)
    }

    @Test
    fun `rejects non-float32 below-floor and reversed threshold pairs`() {
        listOf<(JSONObject) -> Unit>(
            { it.put("general_threshold", 0.95) },
            { it.put("marketing_threshold", 0.95) },
            { it.put("general_threshold", 0.9499999284744263) },
            { it.put("marketing_threshold", 1.0000001192092896) },
            {
                it
                    .put("general_threshold", 1.0)
                    .put("marketing_threshold", 0.949999988079071)
            },
        ).forEach { mutation ->
            val value = validManifest()
            mutation(value)
            assertNull(SemanticModelManifest.parse(value.toString()))
        }
    }

    @Test
    fun `rejects old scalar and incomplete dual-threshold schemas`() {
        val old =
            validManifest().apply {
                put("schema_version", "alarmcontrol-semantic-model-manifest-v1")
                remove("general_threshold")
                remove("marketing_threshold")
                put("confidence_threshold", 0.949999988079071)
            }
        val incomplete = validManifest().apply { remove("marketing_threshold") }

        assertNull(SemanticModelManifest.parse(old.toString()))
        assertNull(SemanticModelManifest.parse(incomplete.toString()))
    }

    @Test
    fun `rejects quantization audit drift`() {
        listOf<(JSONObject) -> Unit>(
            {
                it
                    .getJSONObject("conversion")
                    .getJSONObject("quantization")
                    .put("applied", "float32")
            },
            {
                it
                    .getJSONObject("conversion")
                    .getJSONObject("quantization_audit")
                    .put("passed", false)
            },
            {
                it
                    .getJSONObject("conversion")
                    .getJSONObject("quantization_audit")
                    .put("tensor_count", 0)
            },
            {
                it
                    .getJSONObject("conversion")
                    .getJSONObject("quantization_audit")
                    .put("int8_tensor_count", 101)
            },
            {
                it
                    .getJSONObject("conversion")
                    .getJSONObject("quantization_audit")
                    .remove("method")
            },
        ).forEach { mutation -> assertRejected(mutation) }
    }

    @Test
    fun `rejects evidence and provenance drift`() {
        listOf<(JSONObject) -> Unit>(
            {
                it
                    .getJSONObject("evidence")
                    .remove("development_test_gate_sha256")
            },
            {
                it
                    .getJSONObject("evidence")
                    .put("test_parity_report_sha256", "not-a-hash")
            },
            {
                it
                    .getJSONObject("evaluation_provenance")
                    .remove("development_test")
            },
        ).forEach { mutation -> assertRejected(mutation) }
    }

    @Test
    fun `rejects hash labels tokenizer and tensor drift`() {
        listOf<(JSONObject) -> Unit>(
            {
                it
                    .getJSONObject("files")
                    .getJSONObject("semantic_vocab.txt")
                    .put("sha256", "not-a-hash")
            },
            { it.put("labels", JSONArray(listOf("MARKETING"))) },
            { it.getJSONObject("tokenizer").put("lowercase", true) },
            {
                it
                    .getJSONObject("conversion")
                    .getJSONObject("tensor_contract")
                    .getJSONArray("inputs")
                    .getJSONObject(0)
                    .put("shape", JSONArray(listOf(1, 64)))
            },
            {
                it
                    .getJSONObject("evaluation_provenance")
                    .getJSONObject("sealed_holdout")
                    .put("model_artifact_sha256", "b".repeat(64))
            },
        ).forEach { mutation -> assertRejected(mutation) }
    }

    private fun validManifest(): JSONObject {
        val hash = "a".repeat(64)

        fun file(size: Long) =
            JSONObject()
                .put("sha256", hash)
                .put("size_bytes", size)

        fun tensor(
            name: String,
            dtype: String,
            shape: List<Int>,
        ) = JSONObject()
            .put("name", name)
            .put("dtype", dtype)
            .put("shape", JSONArray(shape))

        return JSONObject()
            .put("schema_version", "alarmcontrol-semantic-model-manifest-v2")
            .put(
                "files",
                JSONObject()
                    .put("semantic_notification_classifier.tflite", file(1024))
                    .put("semantic_vocab.txt", file(256))
                    .put("semantic_labels.txt", file(65)),
            ).put(
                "labels",
                JSONArray(SemanticIntent.entries.map(SemanticIntent::name)),
            ).put("max_sequence_length", 128)
            .put("general_threshold", 0.949999988079071)
            .put("marketing_threshold", 1.0)
            .put(
                "tokenizer",
                JSONObject()
                    .put("type", "bert-wordpiece")
                    .put("normalization", "nfc")
                    .put("lowercase", false),
            ).put(
                "conversion",
                JSONObject()
                    .put(
                        "quantization",
                        JSONObject()
                            .put("requested", "auto")
                            .put("applied", "dynamic-int8")
                            .put("calibration_used", false)
                            .put("experimental_backend", true)
                            .put("fallback_reason", JSONObject.NULL),
                    ).put(
                        "quantization_audit",
                        JSONObject()
                            .put("schema_version", "koelectra-dynamic-int8-audit-v1")
                            .put(
                                "method",
                                "litert-interpreter-tensor-and-operator-inspection",
                            ).put("tensor_count", 100)
                            .put("int8_tensor_count", 20)
                            .put("operator_count", 50)
                            .put("quantize_operator_count", 10)
                            .put("passed", true),
                    ).put(
                        "tensor_contract",
                        JSONObject()
                            .put(
                                "inputs",
                                JSONArray()
                                    .put(tensor("input_ids", "int32", listOf(1, 128)))
                                    .put(tensor("attention_mask", "int32", listOf(1, 128))),
                            ).put("output", tensor("logits", "float32", listOf(1, 7))),
                    ),
            ).put(
                "evidence",
                JSONObject()
                    .put("conversion_manifest_sha256", hash)
                    .put("threshold_selection_sha256", hash)
                    .put("development_test_gate_sha256", hash)
                    .put("test_parity_report_sha256", hash)
                    .put("sealed_holdout_gate_sha256", hash),
            ).put(
                "evaluation_provenance",
                JSONObject()
                    .put("threshold_selection", provenance(hash))
                    .put("development_test", provenance(hash))
                    .put("sealed_holdout", provenance(hash)),
            )
    }

    private fun assertRejected(mutation: (JSONObject) -> Unit) {
        val value = validManifest()
        mutation(value)
        assertNull(SemanticModelManifest.parse(value.toString()))
    }

    private fun provenance(hash: String): JSONObject =
        JSONObject()
            .put("schema_version", "semantic-evaluation-provenance-v1")
            .put("source_manifest_sha256", hash)
            .put("backend", "tensorflow-lite")
            .put("model_artifact_sha256", hash)
            .put("vocab_sha256", hash)
}
