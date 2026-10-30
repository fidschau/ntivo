package io.ntivo.model

import kotlinx.serialization.Serializable

// The API contract shared by the server and the static app. Enum entries are
// lowercase on purpose: they are serialized by name and read as-is by the UI.

@Serializable
enum class SourceKind { github, upload, paste }

@Serializable
enum class SourceState { indexing, ready, failed }

@Serializable
enum class StageName { fetch, split, embed, connect }

@Serializable
enum class StageState { todo, running, done, failed }

@Serializable
data class Stage(
    val name: StageName,
    val state: StageState = StageState.todo,
    val done: Int = 0,
    val total: Int = 0,
    val durationMs: Long = 0
)

@Serializable
data class Source(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val state: SourceState,
    val languages: List<String> = emptyList(),
    val files: Int = 0,
    val pieces: Int = 0,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
    val stages: List<Stage> = StageName.entries.map { Stage(it) }
)

@Serializable
data class Piece(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val language: String,
    val path: String,
    val name: String,
    val qualifiedName: String,
    val startLine: Int,
    val endLine: Int,
    val text: String
)

@Serializable
data class Citation(
    val pieceId: String,
    val sourceName: String,
    val path: String,
    val startLine: Int,
    val endLine: Int
)

@Serializable
enum class VerdictKind { mismatch, confirmed, neutral }

@Serializable
data class Verdict(val kind: VerdictKind, val text: String)

@Serializable
data class EvidenceRow(val label: String, val text: String, val citation: Citation? = null)

@Serializable
data class Answer(
    val id: String,
    val question: String,
    val verdict: Verdict? = null,
    val prose: String,
    val evidence: List<EvidenceRow> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val couldNotSee: List<String> = emptyList(),
    val unverifiedCitations: Int = 0,
    val durationMs: Long
)

@Serializable
data class TracePiece(
    val pieceId: String,
    val name: String,
    val sourceName: String,
    val language: String,
    val score: Float
)

@Serializable
data class TraceHop(val from: String, val relation: String, val to: String, val confidence: Float)

@Serializable
data class ToolCall(val tool: String, val args: String, val summary: String, val durationMs: Long)

@Serializable
data class Trace(
    val answerId: String,
    val explanation: String,
    val pieces: List<TracePiece>,
    val hops: List<TraceHop>,
    val unavailable: List<String>,
    val toolCalls: List<ToolCall>,
    val limitsHit: List<String>,
    val model: String
)
