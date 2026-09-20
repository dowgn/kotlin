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
