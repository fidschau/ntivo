package io.ntivo

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor

/**
 * Creates the Ntivo codebase agent — an AI agent with the [searchCodebase] tool.
 *
 * Unlike the simple agent from Phase 1, this agent can decide on its own whether
 * to search the indexed codebase before answering. When a user asks "how does
 * authentication work?", the agent calls searchCodebase. When they ask "what is
 * Kotlin?", it answers directly.
 *
 * This is the Phase 2 step 4 milestone: the shift from script to agent.
 */
fun createCodebaseAgent(
    apiKey: String,
    embedder: GeminiEmbedder,
    collection: String = "chunks_demo"
): AIAgent<String, String> {
    val searchTools = CodeSearchTools(embedder, collection)

    return AIAgent(
        promptExecutor = simpleGoogleAIExecutor(apiKey),
        llmModel = GoogleModels.Gemini2_5Flash,
        systemPrompt = """
            You are Ntivo, a codebase assistant. You help engineers understand their code.

            You have access to a searchCodebase tool that searches an indexed codebase
            using semantic vector search. Use it when:
            - The user asks about specific code, functions, or implementations
            - The user wants to know how something works in the codebase
            - The user asks about relationships between code components

            Do NOT use the tool when:
            - The user asks general programming questions not about their codebase
            - The user is asking you to explain a concept
            - The question can be answered without looking at code

            When you use the tool and find results:
            - Summarize what you found in clear, natural language
            - Reference specific function names and what they do
            - If the code is short, include relevant snippets

            When the tool returns no results:
            - Tell the user no matching code was found
            - Suggest they index more code if needed

            Be concise. Engineers value brevity.
        """.trimIndent(),
        toolRegistry = ToolRegistry {
            tools(searchTools)
        }
    )
}
