package io.ntivo.agent

import io.ntivo.model.Answer
import io.ntivo.model.Trace
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One question's outcome as kept on disk: the answer shown and how it was produced. */
@Serializable
data class AnswerRecord(val answer: Answer, val trace: Trace)

/** Answers and traces as JSON files, `{dir}/{answerId}.json`. */
class AnswerStore(private val dir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun save(record: AnswerRecord) {
        val file = fileFor(record.answer.id) ?: throw IllegalArgumentException("Invalid answer id")
        dir.mkdirs()
        file.writeText(json.encodeToString(AnswerRecord.serializer(), record))
    }

    fun record(answerId: String): AnswerRecord? {
        val file = fileFor(answerId)?.takeIf { it.isFile } ?: return null
        return runCatching { json.decodeFromString(AnswerRecord.serializer(), file.readText()) }.getOrNull()
    }

    fun trace(answerId: String): Trace? = record(answerId)?.trace

    /** Null for ids that could escape [dir]; answer ids are UUIDs. */
    private fun fileFor(answerId: String): File? =
        if (ID_PATTERN.matches(answerId)) File(dir, "$answerId.json") else null

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
    }
}
