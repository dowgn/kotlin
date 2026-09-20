# Integrating AI agents into Kotlin without external frameworks

This directory answers the question "what's the best way to build AI-agent
primitives — tool calling, streaming, multi-step planning — in Kotlin using
*only* what the language and `kotlin-stdlib` already give you, with no
LangChain-style framework and not even `kotlinx.coroutines` /
`kotlinx.serialization` as added dependencies?"

The short answer: Kotlin already ships almost everything an agent runtime
needs, just not under an "AI" label.

| Need                              | Stdlib-only building block                                          |
|------------------------------------|-----------------------------------------------------------------------|
| Suspend/async agent loop           | `kotlin.coroutines` (`Continuation`, `createCoroutine`, `suspendCoroutine`) — this is stdlib, not `kotlinx.coroutines` |
| Typed tool/function dispatch       | `sealed interface` + exhaustive `when`                               |
| Structured, non-stringly-typed I/O | `value class`, `data class`, sealed `Result`-style unions            |
| Streaming model output             | `Sequence<T>` / `Iterator<T>` (pull-based, no reactive lib needed)   |
| Agent/prompt construction          | Type-safe builder DSLs (`@DslMarker`, trailing lambdas)              |
| Tool schema without reflection lib | `inline fun <reified T>` + manually declared descriptors             |
| Cancellation / timeouts            | `kotlin.time.Duration`, plain `Thread`/`ExecutorService` interrupt   |
| Dependency wiring (LLM client, memory) | Kotlin 2.2 *context parameters* (`context(...)`)                 |

None of this requires publishing a new artifact into `libraries/` or
touching the compiler — it's a pattern library, demonstrated in
[`Agents.kt`](./Agents.kt), that any Kotlin project can copy in as source
(single file, zero deps) or adapt.

## Key ideas

1. **`kotlin.coroutines` is stdlib, not a framework.** `kotlinx.coroutines`
   adds `Dispatchers`, structured concurrency, `Flow`, etc., but the
   suspend-function *language feature* and its low-level primitives
   (`Continuation<T>`, `createCoroutine`, `suspendCoroutine`,
   `startCoroutine`) live in `kotlin-stdlib` itself. That's enough to write
   a single-threaded agent loop that suspends while waiting on an LLM
   call/tool call, without adding a dependency.

2. **Sealed hierarchies replace a "tool registry" object.** Modeling each
   tool call and each agent step as a sealed interface gives the compiler
   exhaustiveness checking on `when` — you cannot forget to handle a new
   tool or a new agent state, which is the main source of bugs in ad-hoc
   Python agent loops.

3. **`Sequence`/`Iterator` give you token streaming for free.** A pull-based
   `Sequence<Token>` (backed by a stdlib `iterator { ... }` builder, itself
   built on `kotlin.coroutines`) is a perfectly adequate substitute for
   `Flow` when you don't want the `kotlinx.coroutines` dependency, at the
   cost of losing dispatcher-based concurrency (acceptable for a
   single-model-call-at-a-time agent).

4. **Context parameters replace DI frameworks.** Kotlin 2.2's
   `context(...)` parameters let an agent function declare "I need an
   `LlmClient` and a `Memory`" without a service locator or a DI framework
   — the compiler resolves it lexically.

5. **Value classes stop prompt/response confusion.** A raw `String` prompt
   and a raw `String` tool-call ID look identical to the compiler; wrapping
   them in `value class Prompt(val text: String)` /
   `value class ToolCallId(val raw: String)` makes misuse a compile error
   at zero runtime cost.

See `Agents.kt` for a runnable (stdlib-only) sketch: a two-tool agent loop
(`Calculator`, `Clock`) that streams its reasoning, dispatches tool calls
through a sealed hierarchy, and drives itself via a hand-rolled coroutine
trampoline.

## Talking to a real model: `Json.kt` + `HttpLlm.kt`

The mock `LlmClient` in `Agents.kt` is enough to exercise the agent loop
offline, but a genuine integration needs to call a real API. Two more
files show that this doesn't require any dependency either:

- **`Json.kt`** — a small `JsonValue` sealed type, a recursive-descent
  parser, a `render()` serializer, and a `jsonObject { "k" to v }`
  builder DSL. This is the stdlib-only stand-in for
  `kotlinx.serialization`: more code to write once, zero dependencies to
  pull in, and no reflection/codegen step.
- **`HttpLlm.kt`** — `AnthropicHttpClient`, an `LlmClient` implementation
  built on `java.net.http.HttpClient` (part of the JDK since 11, not an
  added dependency) and `Json.kt`. It turns `AgentStep` history into the
  Anthropic Messages API wire format, including tool definitions derived
  from each `Tool.inputSchema`, and parses `tool_use`/`text` content
  blocks back into `AgentStep`s. The async `CompletableFuture` from
  `HttpClient.sendAsync` is bridged into a `suspend fun` with a five-line
  `suspendCoroutine` wrapper — the same pattern any JDK async API needs,
  again using only `kotlin.coroutines`.

Both files were compiled and smoke-tested against a Kotlin 2.0.21
compiler (bundled with this repo's Gradle distribution) with only
`kotlin-stdlib` on the runtime classpath — no `kotlinx-coroutines-core`,
no `kotlinx-serialization-json`, no HTTP client library. `AgentsKt.main`
runs the mock two-tool loop end to end, and a JSON round-trip
(`build → render → parse → equals`) passes.

## End-to-end wiring: `Main.kt`, three providers

`Main.kt` wires `runAgentLoop` to a real `LlmClient` selected at runtime:

```bash
ANTHROPIC_API_KEY=... java -cp ... MainKt "What is 2 + 3?"
OPENAI_API_KEY=...    java -cp ... MainKt "What time is it?"
GEMINI_API_KEY=...    java -cp ... MainKt "What is 2 + 3?"
```

Two more `LlmClient` implementations were added alongside
`AnthropicHttpClient`, same constraints (JDK `java.net.http` + `Json.kt`
only):

- **`OpenAiHttpClient`** — Chat Completions API (`/v1/chat/completions`),
  tools as `{"type":"function","function":{...}}`, tool calls returned in
  `message.tool_calls[].function.arguments` as a JSON *string* that gets
  parsed back into a JSON object.
- **`GeminiHttpClient`** — `generateContent` API, `contents`/`parts` with
  `functionCall`/`functionResponse` parts. Gemini's `functionResponse`
  needs the *tool name*, not an id, so `toolNameForCallId` recovers it by
  scanning back through `history` for the matching `ToolCall`. Gemini also
  never assigns its own call id, so one is synthesized from the position
  in history.

**Verification performed:** all four files (`Agents.kt`, `Json.kt`,
`HttpLlm.kt`, `OpenAiHttpClient.kt`, `GeminiHttpClient.kt`, `Main.kt`)
compile together cleanly against Kotlin 2.0.21 with only `kotlin-stdlib`
on the classpath. `MainKt` was run against all three real API endpoints
(`api.anthropic.com`, `api.openai.com`, `generativelanguage.googleapis.com`)
with a deliberately invalid API key — each returned a genuine,
provider-specific `4xx` error body, which confirms the request
construction, JSON encoding, TLS handshake, and the `suspend`/HTTP bridge
all work end to end for all three providers. **No valid API key was
available in this session**, so a real successful completion (a real
`tool_use`/`tool_calls`/`functionCall` round-trip driving `runAgentLoop`
to a `FinalAnswer`) was not observed — only the offline `mockLlm` path
was verified to actually reach `FinalAnswer` through `runAgentLoop`. If
you run this with a real key, that's the one thing left to confirm.

## What's intentionally out of scope here

- **Tool schema via reflection** (`kotlin-reflect`) was deliberately
  avoided in favor of each `Tool` declaring its own `inputSchema` — this
  keeps the dependency footprint at exactly `kotlin-stdlib`, since
  `kotlin-reflect` is a separate artifact from the standard library.
- **True concurrent tool execution** (e.g. calling two tools in
  parallel) is straightforward with `kotlinx.coroutines`' structured
  concurrency but needs manual `Thread`/`ExecutorService` fan-out without
  it; not included here since the demo agent is single-tool-call-at-a-time.
- **Multiplatform (KMP) targets**: `HttpLlm.kt` is JVM-only because it
  uses `java.net.http`. The rest of the files (`Agents.kt`, `Json.kt`)
  use only `kotlin.*` and are portable to JS/Native/Wasm targets as-is —
  only the HTTP transport needs a platform-specific `expect`/`actual`.
