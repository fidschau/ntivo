package io.ntivo.agent

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * The answer models, pinned here so a change is a reviewed code change. Claude is used
 * when `NTIVO_ANTHROPIC_API_KEY` is set, Gemini otherwise (see `AppContext`).
 *
 * Koog 1.3.0 lists Claude models up to the 5 family, so Sonnet 5.5 is Sonnet 5 with the
 * newer id; the Anthropic client must also be told the id (`anthropicSettings`).
 */
val CLAUDE_ANSWER_MODEL: LLModel = AnthropicModels.Sonnet_5.copy(id = "claude-sonnet-5-5")
val GEMINI_ANSWER_MODEL: LLModel = GoogleModels.Gemini2_5Flash

/** Guardrail: low temperature so the same question gets the same answer. */
const val ANSWER_TEMPERATURE = 0.2

/**
 * The temperature to send. Claude 5 models reject anything but the default, so Claude
 * gets 1.0 and leans on the prompt and structured output for consistency instead.
 */
fun temperatureFor(model: LLModel, preferred: Double = ANSWER_TEMPERATURE): Double =
    if (model.provider == LLMProvider.Anthropic) 1.0 else preferred

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
    model: LLModel
): AIAgent<String, String> = AIAgent(
    promptExecutor = executor,
    llmModel = model,
    toolRegistry = agentToolRegistry(searchTools, graphTools),
    systemPrompt = systemPrompt,
    temperature = temperatureFor(model),
    maxIterations = MAX_AGENT_ITERATIONS
)

/** A tool-less agent that turns a malformed final message into the JSON object. */
fun createRepairAgent(executor: PromptExecutor, model: LLModel): AIAgent<String, String> = AIAgent(
    promptExecutor = executor,
    llmModel = model,
    systemPrompt = """
        You convert an answer into JSON. Reply with one JSON object and nothing else, in this shape:
        {"verdict": {"kind": "mismatch|confirmed|neutral", "text": "..."} | null, "prose": "...",
         "evidence": [{"label": "...", "text": "...", "pieceId": "..." | null}], "couldNotSee": ["..."]}
        Keep the wording of the answer. Use only pieceIds that appear in the answer. Return only the JSON object.
    """.trimIndent(),
    temperature = temperatureFor(model, preferred = 0.0),
    maxIterations = 4
)
