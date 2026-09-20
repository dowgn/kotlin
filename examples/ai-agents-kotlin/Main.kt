/*
 * End-to-end wiring: runAgentLoop driven by a real provider (Anthropic,
 * OpenAI or Gemini), selected at runtime, still with zero non-JDK/stdlib
 * dependencies.
 *
 * Usage:
 *   ANTHROPIC_API_KEY=... java -cp ... MainKt "What is 2 + 3?"
 *   OPENAI_API_KEY=...    java -cp ... MainKt "What time is it?"
 *   GEMINI_API_KEY=...    java -cp ... MainKt "What is 2 + 3?"
 *   MISTRAL_API_KEY=...   java -cp ... MainKt "What is 2 + 3?"
 *
 * Falls back to the offline mockLlm from Agents.kt if none of the four
 * API key environment variables are set, so the wiring can be exercised
 * without network access or credentials.
 */

private val allTools: List<Tool> = listOf(Calculator, Clock)

private fun selectLlmClient(): Pair<String, LlmClient> {
    System.getenv("ANTHROPIC_API_KEY")?.let { key ->
        return "anthropic" to AnthropicHttpClient(apiKey = key, tools = allTools)
    }
    System.getenv("OPENAI_API_KEY")?.let { key ->
        return "openai" to OpenAiHttpClient(apiKey = key, tools = allTools)
    }
    System.getenv("GEMINI_API_KEY")?.let { key ->
        return "gemini" to GeminiHttpClient(apiKey = key, tools = allTools)
    }
    System.getenv("MISTRAL_API_KEY")?.let { key ->
        return "mistral" to MistralHttpClient(apiKey = key, tools = allTools)
    }
    return "mock (no ANTHROPIC_API_KEY / OPENAI_API_KEY / GEMINI_API_KEY / MISTRAL_API_KEY set)" to mockLlm
}

fun main(args: Array<String>) {
    val question = args.firstOrNull() ?: "What is 2 + 3?"
    val (providerLabel, llm) = selectLlmClient()
    println("Using provider: $providerLabel")
    println("Prompt: $question")

    val answer = runAgentBlocking {
        runAgentLoop(llm, Prompt(question))
    }

    print("Answer: ")
    answer.streamWords().forEach { print("$it ") }
    println()
}
