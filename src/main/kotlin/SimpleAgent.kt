package io.ntivo

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import kotlinx.coroutines.runBlocking

/**
 * A simple Koog agent that proves the AI plumbing works.
 *
 * Run this with: ./gradlew run -PmainClass=io.ntivo.SimpleAgentKt
 * Or run the main() directly from IntelliJ.
 *
 * Requires: GEMINI_API_KEY environment variable set.
 * Get a free key at https://aistudio.google.com
 */
fun main() = runBlocking {
    val apiKey = System.getenv("GEMINI_API_KEY")
        ?: error("Set the GEMINI_API_KEY environment variable. Get a free key at https://aistudio.google.com")

    val agent = AIAgent(
        promptExecutor = simpleGoogleAIExecutor(apiKey),
        llmModel = GoogleModels.Gemini2_5Flash,
        systemPrompt = "You are a helpful assistant that knows about codebases. " +
            "You help engineers understand code, trace features across repositories, " +
            "and answer questions about software architecture."
    )

    println("🧠 Ntivo Agent ready. Type a message (or 'quit' to exit):\n")

    while (true) {
        print("> ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.equals("quit", ignoreCase = true) || input.equals("exit", ignoreCase = true)) break
        if (input.isBlank()) continue

        val response = agent.run(input)
        println("\n$response\n")
    }

    println("Goodbye!")
}
