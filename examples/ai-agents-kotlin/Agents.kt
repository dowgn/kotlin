/*
 * Zero-dependency AI agent primitives in Kotlin.
 *
 * Compiles against `kotlin-stdlib` alone: no kotlinx.coroutines, no
 * kotlinx.serialization, no third-party agent framework. See README.md
 * for the rationale behind each building block.
 *
 * This file is a standalone sketch, not wired into the Gradle build.
 */

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.CountDownLatch

// ---------------------------------------------------------------------
// 1. Typed wrappers instead of stringly-typed prompts/ids (value classes)
// ---------------------------------------------------------------------

@JvmInline
value class Prompt(val text: String)

@JvmInline
value class ToolCallId(val raw: String)

// ---------------------------------------------------------------------
// 2. Tools and agent steps as sealed hierarchies -> exhaustive `when`
// ---------------------------------------------------------------------

sealed interface Tool {
    val name: String
    fun run(args: Map<String, String>): String
}

data object Calculator : Tool {
    override val name = "calculator"
    override fun run(args: Map<String, String>): String {
        val a = args.getValue("a").toDouble()
        val b = args.getValue("b").toDouble()
        return when (args["op"]) {
            "+" -> (a + b).toString()
            "-" -> (a - b).toString()
            "*" -> (a * b).toString()
            "/" -> (a / b).toString()
            else -> error("unknown op ${args["op"]}")
        }
    }
}

data object Clock : Tool {
    override val name = "clock"
    override fun run(args: Map<String, String>): String = System.currentTimeMillis().toString()
}

sealed interface AgentStep {
    data class Thought(val text: String) : AgentStep
    data class ToolCall(val id: ToolCallId, val tool: Tool, val args: Map<String, String>) : AgentStep
    data class ToolResult(val id: ToolCallId, val output: String) : AgentStep
    data class FinalAnswer(val text: String) : AgentStep
}

// ---------------------------------------------------------------------
// 3. `suspend` model client, using only kotlin.coroutines (stdlib)
// ---------------------------------------------------------------------

fun interface LlmClient {
    suspend fun next(prompt: Prompt, history: List<AgentStep>): AgentStep
}

/** Simulates network latency using a suspend point, with no external dispatcher library. */
private suspend fun simulateLatency(millis: Long) = suspendCoroutine<Unit> { cont ->
    Thread {
        Thread.sleep(millis)
        cont.resume(Unit)
    }.start()
}

/** A deterministic mock model so the sketch runs without a real API key. */
val mockLlm = LlmClient { prompt, history ->
    simulateLatency(20)
    val lastResult = history.lastOrNull() as? AgentStep.ToolResult
    when {
        lastResult != null -> AgentStep.FinalAnswer("The answer is ${lastResult.output}")
        "time" in prompt.text -> AgentStep.ToolCall(ToolCallId("1"), Clock, emptyMap())
        else -> AgentStep.ToolCall(ToolCallId("1"), Calculator, mapOf("a" to "2", "b" to "3", "op" to "+"))
    }
}

// ---------------------------------------------------------------------
// 4. A hand-rolled coroutine trampoline: `runBlocking` without kotlinx
// ---------------------------------------------------------------------

fun <T> runAgentBlocking(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
        outcome = result
        latch.countDown()
    })
    latch.await()
    return outcome!!.getOrThrow()
}

// ---------------------------------------------------------------------
// 5. The agent loop itself: sealed-state machine, tail-recursive
// ---------------------------------------------------------------------

tailrec suspend fun runAgentLoop(
    llm: LlmClient,
    prompt: Prompt,
    history: List<AgentStep> = emptyList(),
    maxSteps: Int = 8,
): AgentStep.FinalAnswer {
    check(history.size < maxSteps) { "agent did not converge in $maxSteps steps" }
    when (val step = llm.next(prompt, history)) {
        is AgentStep.FinalAnswer -> return step
        is AgentStep.ToolCall -> {
            val output = step.tool.run(step.args)
            return runAgentLoop(llm, prompt, history + step + AgentStep.ToolResult(step.id, output), maxSteps)
        }
        is AgentStep.Thought -> return runAgentLoop(llm, prompt, history + step, maxSteps)
        is AgentStep.ToolResult -> return runAgentLoop(llm, prompt, history + step, maxSteps)
    }
}

// ---------------------------------------------------------------------
// 6. Streaming a final answer word-by-word via a stdlib Sequence
//    (a pull-based substitute for Flow when kotlinx.coroutines is unwanted)
// ---------------------------------------------------------------------

fun AgentStep.FinalAnswer.streamWords(): Sequence<String> = sequence {
    for (word in text.split(" ")) {
        yield(word)
    }
}

// ---------------------------------------------------------------------
// 7. Type-safe builder DSL for composing an agent's static configuration
// ---------------------------------------------------------------------

@DslMarker
annotation class AgentDsl

@AgentDsl
class AgentConfigBuilder {
    var systemPrompt: String = ""
    private val tools = mutableListOf<Tool>()
    fun tool(t: Tool) {
        tools += t
    }
    fun build(): AgentConfig = AgentConfig(systemPrompt, tools.toList())
}

data class AgentConfig(val systemPrompt: String, val tools: List<Tool>)

fun agent(block: AgentConfigBuilder.() -> Unit): AgentConfig = AgentConfigBuilder().apply(block).build()

// ---------------------------------------------------------------------
// Demo
// ---------------------------------------------------------------------

fun main() {
    val config = agent {
        systemPrompt = "You are a helpful assistant."
        tool(Calculator)
        tool(Clock)
    }
    println("Configured agent with tools: ${config.tools.map { it.name }}")

    val answer = runAgentBlocking {
        runAgentLoop(mockLlm, Prompt("What is 2 + 3?"))
    }
    answer.streamWords().forEach { print("$it ") }
    println()
}
