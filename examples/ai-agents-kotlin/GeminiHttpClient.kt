/*
 * LlmClient backed by Google's Gemini generateContent API, using only
 * java.net.http.HttpClient (JDK) and the hand-rolled Json.kt — same
 * zero-dependency constraint as AnthropicHttpClient / OpenAiHttpClient.
 */

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration

class GeminiHttpClient(
    private val apiKey: String,
    private val model: String = "gemini-2.0-flash",
    private val tools: List<Tool>,
    private val systemPrompt: String = "You are a helpful assistant with access to tools.",
) : LlmClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private fun toolByName(name: String): Tool =
        tools.firstOrNull { it.name == name } ?: error("model requested unknown tool '$name'")

    /** Gemini's functionResponse needs the tool *name*, but ToolResult only carries an id — recover it from the matching ToolCall. */
    private fun toolNameForCallId(history: List<AgentStep>, id: ToolCallId): String =
        history.filterIsInstance<AgentStep.ToolCall>().first { it.id == id }.tool.name

    private fun historyToContents(history: List<AgentStep>): List<JsonValue> {
        val contents = mutableListOf<JsonValue>()
        for (step in history) {
            when (step) {
                is AgentStep.Thought ->
                    contents += jsonObject { "role" to "model"; "parts" to jsonArray(jsonObject { "text" to step.text }) }
                is AgentStep.ToolCall ->
                    contents += jsonObject {
                        "role" to "model"
                        "parts" to jsonArray(
                            jsonObject {
                                "functionCall" to jsonObject {
                                    "name" to step.tool.name
                                    "args" to jsonObject { step.args.forEach { (k, v) -> k to v } }
                                }
                            },
                        )
                    }
                is AgentStep.ToolResult ->
                    contents += jsonObject {
                        "role" to "user"
                        "parts" to jsonArray(
                            jsonObject {
                                "functionResponse" to jsonObject {
                                    "name" to toolNameForCallId(history, step.id)
                                    "response" to jsonObject { "content" to step.output }
                                }
                            },
                        )
                    }
                is AgentStep.FinalAnswer ->
                    contents += jsonObject { "role" to "model"; "parts" to jsonArray(jsonObject { "text" to step.text }) }
            }
        }
        return contents
    }

    private fun toolsJson(): JsonValue = jsonArray(
        jsonObject {
            "functionDeclarations" to jsonArrayOf(
                tools.map { tool ->
                    jsonObject {
                        "name" to tool.name
                        "description" to tool.description
                        "parameters" to tool.inputSchema
                    }
                },
            )
        },
    )

    override suspend fun next(prompt: Prompt, history: List<AgentStep>): AgentStep {
        val contents = jsonArrayOf(
            listOf(jsonObject { "role" to "user"; "parts" to jsonArray(jsonObject { "text" to prompt.text }) }) +
                historyToContents(history),
        )
        val requestBody = jsonObject {
            "contents" to contents
            "systemInstruction" to jsonObject { "parts" to jsonArray(jsonObject { "text" to systemPrompt }) }
            "tools" to toolsJson()
        }

        val uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val request = HttpRequest.newBuilder(uri)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
            .build()

        val response = http.sendSuspend(request)
        check(response.statusCode() in 200..299) {
            "Gemini call failed with HTTP ${response.statusCode()}: ${response.body()}"
        }

        val body = parseJson(response.body()).asObject()
        val parts = body["candidates"]!!.asArray().first().asObject()["content"]!!.asObject()["parts"]!!.asArray()

        val functionCallPart = parts.firstOrNull { (it as? JsonValue.JsonObject)?.get("functionCall") != null }
        if (functionCallPart != null) {
            val call = functionCallPart.asObject()["functionCall"]!!.asObject()
            val name = call["name"]!!.asString()
            val argsObj = call["args"]!!.asObject()
            val args = argsObj.entries.mapValues { (_, v) -> v.asStringOrNull() ?: v.render() }
            // Gemini has no client-assigned call id; synthesize one from position in history.
            val id = ToolCallId("gemini-${history.size}")
            return AgentStep.ToolCall(id, toolByName(name), args)
        }

        val text = parts.firstOrNull { (it as? JsonValue.JsonObject)?.get("text") != null }
            ?.asObject()?.get("text")?.asString()
            ?: error("model response had neither text nor functionCall: ${response.body()}")
        return AgentStep.FinalAnswer(text)
    }
}
