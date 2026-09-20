/*
 * Exercises AnthropicHttpClient's real HTTP code path end-to-end without
 * a paid API key, by running a tiny local server (com.sun.net.httpserver,
 * bundled with the JDK — no added dependency) that speaks the same
 * wire format as the real Anthropic Messages API.
 *
 * This proves the client -> HTTP -> JSON -> AgentStep -> tool execution
 * -> HTTP -> JSON -> FinalAnswer round trip actually works, using the
 * unmodified AnthropicHttpClient class. The only thing it does NOT
 * prove is that a real model's output parses correctly - only that our
 * request/response handling does, against a server that speaks the
 * documented shape.
 */

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI

private fun startStubAnthropicServer(): HttpServer {
    val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    server.createContext("/v1/messages") { exchange ->
        val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val request = parseJson(requestBody).asObject()
        val messages = request["messages"]!!.asArray()

        val hasToolResult = messages.any { message ->
            val content = (message.asObject()["content"] as? JsonValue.JsonArray)?.items.orEmpty()
            content.any { (it as? JsonValue.JsonObject)?.get("type")?.asStringOrNull() == "tool_result" }
        }

        val responseJson = if (!hasToolResult) {
            jsonObject {
                "id" to "msg_stub_1"
                "role" to "assistant"
                "content" to jsonArray(
                    jsonObject {
                        "type" to "tool_use"
                        "id" to "call_stub_1"
                        "name" to "calculator"
                        "input" to jsonObject { "a" to "2"; "b" to "3"; "op" to "+" }
                    },
                )
            }
        } else {
            jsonObject {
                "id" to "msg_stub_2"
                "role" to "assistant"
                "content" to jsonArray(
                    jsonObject { "type" to "text"; "text" to "The stub server says 2 + 3 = 5" },
                )
            }
        }

        val bytes = responseJson.render().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("content-type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return server
}

fun main() {
    val server = startStubAnthropicServer()
    try {
        val port = server.address.port
        val client = AnthropicHttpClient(
            apiKey = "stub-key-not-checked-by-local-server",
            tools = listOf(Calculator, Clock),
            baseUri = URI.create("http://localhost:$port/v1/messages"),
        )

        val answer = runAgentBlocking {
            runAgentLoop(client, Prompt("What is 2 + 3?"))
        }

        check(answer.text == "The stub server says 2 + 3 = 5") {
            "unexpected final answer: ${answer.text}"
        }
        println("End-to-end round trip through AnthropicHttpClient succeeded:")
        println("  ${answer.text}")
    } finally {
        server.stop(0)
    }
}
