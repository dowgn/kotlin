/*
 * LlmClient backed by OpenAI's Chat Completions API, using only
 * java.net.http.HttpClient (JDK) and the hand-rolled Json.kt — same
 * zero-dependency constraint as AnthropicHttpClient.
 */

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

class OpenAiHttpClient(
    private val apiKey: String,
    private val model: String = "gpt-4o",
    private val tools: List<Tool>,
    private val systemPrompt: String = "You are a helpful assistant with access to tools.",
    private val baseUri: URI = URI.create("https://api.openai.com/v1/chat/completions"),
) : LlmClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private fun toolByName(name: String): Tool =
        tools.firstOrNull { it.name == name } ?: error("model requested unknown tool '$name'")

    private fun historyToMessages(history: List<AgentStep>): List<JsonValue> {
        val messages = mutableListOf<JsonValue>()
        for (step in history) {
            when (step) {
                is AgentStep.Thought ->
                    messages += jsonObject { "role" to "assistant"; "content" to step.text }
                is AgentStep.ToolCall ->
                    messages += jsonObject {
                        "role" to "assistant"
                        "content" to JsonValue.JsonNull
                        "tool_calls" to jsonArray(
                            jsonObject {
                                "id" to step.id.raw
                                "type" to "function"
                                "function" to jsonObject {
                                    "name" to step.tool.name
                                    "arguments" to jsonObject { step.args.forEach { (k, v) -> k to v } }.render()
                                }
                            },
                        )
                    }
                is AgentStep.ToolResult ->
                    messages += jsonObject {
                        "role" to "tool"
                        "tool_call_id" to step.id.raw
                        "content" to step.output
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
                "type" to "function"
                "function" to jsonObject {
                    "name" to tool.name
                    "description" to tool.description
                    "parameters" to tool.inputSchema
                }
            }
        },
    )

    override suspend fun next(prompt: Prompt, history: List<AgentStep>): AgentStep {
        val messages = jsonArrayOf(
            listOf(
                jsonObject { "role" to "system"; "content" to systemPrompt },
                jsonObject { "role" to "user"; "content" to prompt.text },
            ) + historyToMessages(history),
        )
        val requestBody = jsonObject {
            "model" to model
            "messages" to messages
            "tools" to toolsJson()
        }

        val request = HttpRequest.newBuilder(baseUri)
            .header("content-type", "application/json")
            .header("authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
            .build()

        val response = http.sendSuspend(request)
        check(response.statusCode() in 200..299) {
            "OpenAI call failed with HTTP ${response.statusCode()}: ${response.body()}"
        }

        val body = parseJson(response.body()).asObject()
        val message = body["choices"]!!.asArray().first().asObject()["message"]!!.asObject()

        val toolCalls = message["tool_calls"]?.asArray().orEmpty()
        if (toolCalls.isNotEmpty()) {
            val call = toolCalls.first().asObject()
            val id = call["id"]!!.asString()
            val function = call["function"]!!.asObject()
            val name = function["name"]!!.asString()
            val argsJson = parseJson(function["arguments"]!!.asString()).asObject()
            val args = argsJson.entries.mapValues { (_, v) -> v.asStringOrNull() ?: v.render() }
            return AgentStep.ToolCall(ToolCallId(id), toolByName(name), args)
        }

        val text = message["content"]?.asStringOrNull()
            ?: error("model response had neither content nor tool_calls: ${response.body()}")
        return AgentStep.FinalAnswer(text)
    }
}
