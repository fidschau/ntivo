package io.ntivo.agent

import io.ntivo.model.Verdict
import io.ntivo.model.VerdictKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** What the model must return as its final message (see the system prompt in [buildSystemPrompt]). */
@Serializable
data class ModelOutput(
    val verdict: Verdict? = null,
    val prose: String,
    val evidence: List<ModelEvidence> = emptyList(),
    val couldNotSee: List<String> = emptyList()
)

@Serializable
data class ModelEvidence(val label: String, val text: String, val pieceId: String? = null)

private val lenientJson = Json { isLenient = true; ignoreUnknownKeys = true }

/**
 * Reads the model's final message. Strips code fences and any text around the outermost
 * `{`...`}`, then reads fields leniently: unknown keys are ignored, a verdict kind is
 * matched case-insensitively (unknown kinds become `neutral`), malformed evidence rows
 * are skipped. Returns null when there is no JSON object or it has no `prose`.
 */
fun parseModelOutput(text: String): ModelOutput? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val obj = runCatching { lenientJson.parseToJsonElement(text.substring(start, end + 1)).jsonObject }
        .getOrNull() ?: return null

    val prose = obj.string("prose") ?: return null
    val verdict = (obj["verdict"] as? JsonObject)?.let { v ->
        val kindName = v.string("kind")?.trim()?.lowercase()
        val kind = VerdictKind.entries.firstOrNull { it.name == kindName } ?: VerdictKind.neutral
        v.string("text")?.takeIf { it.isNotBlank() }?.let { Verdict(kind, it) }
    }
    val evidence = (obj["evidence"] as? JsonArray).orEmpty().mapNotNull { row ->
        val r = row as? JsonObject ?: return@mapNotNull null
        val label = r.string("label").orEmpty()
        val rowText = r.string("text") ?: return@mapNotNull null
        ModelEvidence(label, rowText, r.string("pieceId")?.trim()?.takeIf { it.isNotEmpty() })
    }
    val couldNotSee = (obj["couldNotSee"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }

    return ModelOutput(verdict, prose, evidence, couldNotSee)
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
