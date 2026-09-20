/*
 * A real LlmClient backed by the JDK's own java.net.http.HttpClient
 * (JDK 11+, part of the standard library — not an added dependency)
 * plus the hand-rolled JSON in Json.kt. Speaks the Anthropic Messages
 * API wire format directly.
 *
 * No kotlinx.coroutines, no kotlinx.serialization, no OkHttp/Retrofit,
 * no agent framework.
 */

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Suspends the calling coroutine until the async HTTP call completes, using only java.net.http + kotlin.coroutines. */
suspend fun HttpClient.sendSuspend(request: HttpRequest): HttpResponse<String> =
    suspendCoroutine { cont ->
        sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, throwable ->
                if (throwable != null) cont.resumeWithException(throwable) else cont.resume(response)
            }
    }

class AnthropicHttpClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-5",
    private val tools: List<Tool>,
    private val baseUri: URI = URI.create("https://api.anthropic.com/v1/messages"),
) : LlmClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private fun historyToMessages(history: List<AgentStep>): List<JsonValue> {
        val messages = mutableListOf<JsonValue>()
        for (step in history) {
            when (step) {
                is AgentStep.Thought ->
                    messages += jsonObject { "role" to "assistant"; "content" to step.text }
                is AgentStep.ToolCall ->
                    messages += jsonObject {
                        "role" to "assistant"
                        "content" to jsonArray(
                            jsonObject {
                                "type" to "tool_use"
                                "id" to step.id.raw
                                "name" to step.tool.name
                                "input" to jsonObject { step.args.forEach { (k, v) -> k to v } }
                            },
                        )
                    }
                is AgentStep.ToolResult ->
                    messages += jsonObject {
                        "role" to "user"
                        "content" to jsonArray(
                            jsonObject {
                                "type" to "tool_result"
                                "tool_use_id" to step.id.raw
                                "content" to step.output
                            },
                        )
                    }
                is AgentStep.FinalAnswer ->
                    messages += jsonObject { "role" to "assistant"; "content" to step.text }
            }
        }
        return messages
    }

    private fun toolsJson(): JsonValue = jsonArrayOf(
        tools.map { tool ->
            jsonObject {
                "name" to tool.name
                "description" to tool.description
                "input_schema" to tool.inputSchema
            }
        },
    )

    private fun toolByName(name: String): Tool =
        tools.firstOrNull { it.name == name } ?: error("model requested unknown tool '$name'")

    override suspend fun next(prompt: Prompt, history: List<AgentStep>): AgentStep {
        val requestBody = jsonObject {
            "model" to model
            "max_tokens" to 1024.0
            "system" to "You are a helpful assistant with access to tools."
            "messages" to jsonArrayOf(
                listOf(jsonObject { "role" to "user"; "content" to prompt.text }) + historyToMessages(history),
            )
            "tools" to toolsJson()
        }

        val request = HttpRequest.newBuilder(baseUri)
            .header("content-type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
            .build()

        val response = http.sendSuspend(request)
        check(response.statusCode() in 200..299) {
            "LLM call failed with HTTP ${response.statusCode()}: ${response.body()}"
        }

        val body = parseJson(response.body()).asObject()
        val content = body["content"]?.asArray().orEmpty()

        val toolUse = content.firstOrNull { (it as? JsonValue.JsonObject)?.get("type")?.asStringOrNull() == "tool_use" }
        if (toolUse != null) {
            val obj = toolUse.asObject()
            val name = obj["name"]!!.asString()
            val id = obj["id"]!!.asString()
            val input = obj["input"]!!.asObject()
            val args = input.entries.mapValues { (_, v) -> v.asStringOrNull() ?: v.render() }
            return AgentStep.ToolCall(ToolCallId(id), toolByName(name), args)
        }

        val text = content.firstOrNull { (it as? JsonValue.JsonObject)?.get("type")?.asStringOrNull() == "text" }
            ?.asObject()?.get("text")?.asString()
            ?: error("model response had neither text nor tool_use content: ${response.body()}")
        return AgentStep.FinalAnswer(text)
    }
}
