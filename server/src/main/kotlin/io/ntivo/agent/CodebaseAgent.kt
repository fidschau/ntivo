package io.ntivo.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel

/** The chat model, pinned here so a change is a reviewed code change. */
val ANSWER_MODEL: LLModel = GoogleModels.Gemini2_5Flash

/** Guardrail: low temperature so the same question gets the same answer. */
const val ANSWER_TEMPERATURE = 0.2

/**
 * Koog graph iterations per run. The tool budget ([MAX_TOOL_CALLS]) is the real limit;
 * this only stops a model that keeps calling tools after being told the budget is spent.
 */
private const val MAX_AGENT_ITERATIONS = 40

/** Every tool the agent may use: search by meaning and graph queries. All read-only. */
fun agentToolRegistry(searchTools: CodeSearchTools, graphTools: GraphTools): ToolRegistry = ToolRegistry {
    tools(searchTools)
    tools(graphTools)
}

/**
 * Creates the Ntivo answering agent for one question.
 *
 * Create one agent per question, each with its own tools (and so its own [TraceRecorder]),
 * so concurrent questions never share state. The [executor] can be shared.
 */
fun createCodebaseAgent(
    executor: PromptExecutor,
    systemPrompt: String,
    searchTools: CodeSearchTools,
    graphTools: GraphTools,
    model: LLModel = ANSWER_MODEL
): AIAgent<String, String> = AIAgent(
    promptExecutor = executor,
    llmModel = model,
    toolRegistry = agentToolRegistry(searchTools, graphTools),
    systemPrompt = systemPrompt,
    temperature = ANSWER_TEMPERATURE,
    maxIterations = MAX_AGENT_ITERATIONS
)

/** A tool-less agent that turns a malformed final message into the JSON object. */
fun createRepairAgent(executor: PromptExecutor, model: LLModel = ANSWER_MODEL): AIAgent<String, String> = AIAgent(
    promptExecutor = executor,
    llmModel = model,
    systemPrompt = """
        You convert an answer into JSON. Reply with one JSON object and nothing else, in this shape:
        {"verdict": {"kind": "mismatch|confirmed|neutral", "text": "..."} | null, "prose": "...",
         "evidence": [{"label": "...", "text": "...", "pieceId": "..." | null}], "couldNotSee": ["..."]}
        Keep the wording of the answer. Use only pieceIds that appear in the answer. Return only the JSON object.
    """.trimIndent(),
    temperature = 0.0,
    maxIterations = 4
)
