# ui-message-stream

A small, **app-agnostic** Java library that streams the
[AI SDK v6 *UI Message Stream* protocol](https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol) from a
Spring AI backend to a `useChat` frontend.

> Not affiliated with Vercel. This implements the open wire protocol; "AI SDK" / "Vercel" are
> referenced only to describe compatibility.

## The protocol (v6)

- **Transport:** Server-Sent Events. Each frame is `data:<compact-json>\n\n`; the stream ends with
  the literal `data: [DONE]`.
- **Required response headers:** `Content-Type: text/event-stream` and
  `x-vercel-ai-ui-message-stream: v1` (the value is `v1` even though the SDK itself is v6).
- **Frames** (`type` first, `null` fields omitted, compact one-line JSON):
  - `start` (`messageId`), `start-step`
  - `text-start` / `text-delta` / `text-end` (each with a stable `id`)
  - `reasoning-start` / `reasoning-delta` / `reasoning-end`
  - `tool-input-start`, `tool-input-delta`, `tool-input-available`, `tool-input-error`
  - `tool-output-available`, `tool-output-error`
  - `tool-approval-request`, `tool-output-denied` — human-in-the-loop approval
  - `source-url`, `source-document`, `file`
  - `data-<name>` — generic application data; optional `id` reconciles/updates the part in place
  - `message-metadata`, `error` (`errorText`)
  - `finish-step`, `finish`, `abort`
- **Optional fields** — `providerMetadata`, `toolMetadata`, `providerExecuted`, `dynamic`, `title`,
  `preliminary`, `filename`, `transient`, `reason` (on `abort`), `finishReason`/`messageMetadata` are
  emitted when set and omitted when not.
  Tool parts default to `dynamic:true` (rendered via the client's generic `dynamic-tool` path).
  Validated against `ai@6.0.197`; pin `ai@^6.0.0` (covers all `6.0.x`). The v7-only chunks
  (`tool-approval-response`, `reasoning-file`, `custom`) are intentionally not modelled — the v6
  client validates with strict schemas and rejects unknown chunk types.
- **Forward compatibility** — `UiMessagePart.RawPart(type, body)` emits a chunk type the sealed union
  does not model (it still cannot bypass the writer's text-block lifecycle). Only emit raw types the
  connected client accepts.

## The two invariants this library enforces

1. **Text-block lifecycle.** Text and reasoning stream as `*-start` / `*-delta` / `*-end` under a
   stable id. Before *any* non-text/non-reasoning part is emitted, the currently open block is
   closed; the next `text()`/`reasoning()` opens a **fresh** block with a new id. So
   `text → data → text` is never merged into one block — this logic lives inside
   `UiMessageStreamWriter`, where a caller cannot get the ordering wrong.
2. **Reactive cancellation.** The Spring bridge is `Flux`-based. When the subscriber cancels
   (client disconnect), the upstream subscription is disposed — the underlying model call is
   cancelled. No blocking iteration.

## Module layout

| Module | Depends on | Purpose |
|--------|------------|---------|
| `ui-message-stream-core` | **Jackson only** | `UiMessagePart` (sealed records), `PartSerializer`, the stateful `UiMessageStreamWriter`. No Spring / Reactor / Spring AI — by design. |
| `ui-message-stream-spring` | core + spring-web + spring-webmvc + reactor + spring-ai *(all `provided`)* | Reactive `UiMessageStream` **and** servlet `UiMessageStreamEmitter` transports; `ResponseMapper` / `ChatClientResponseMapper.DEFAULT` + `.TEXT_ONLY`; `UiMessageStreamResponse` / `UiMessageStreamHttp`; the thread-safe per-request `SerializedPartSink` + `UiMessageStreamAdvisor` (sink injection via the `ChatClient` advisor chain); opt-in native tool I/O (`UiMessageStreamToolAdvisor`); inbound `UiMessageRequest` + adapter + pluggable `MediaResolver`. |
| `ui-message-stream-spring-boot-starter` | core + spring | `@AutoConfiguration` exposing the default `ResponseMapper` and, when enabled, the custom tool advisor plus a `ChatClientBuilderCustomizer`. |
| `ui-message-stream-demo` *(not published)* | starter | Runnable showcase app — see below. |

Built against Spring Boot 4.1.0 (Spring Framework 7) and Spring AI 2.0.0, Java 25.

## Try it: the demo app

A runnable showcase with a **scripted offline model — no API key needed** — and a bundled
mini-`useChat` web page that renders the chat on the left and the raw SSE frames on the right:

```bash
./mvnw -pl ui-message-stream-demo -am spring-boot:run   # then open http://localhost:8080
```

It exercises everything: streamed text deltas, native tool I/O, a custom `data-*` part pushed from
inside a tool, the clickable human-in-the-loop approval flow, masked tool errors, both transports
(reactive + servlet), and an imperative "protocol tour". See
[`ui-message-stream-demo/README.md`](ui-message-stream-demo/README.md) for the feature map.

## Install

Distributed via **[JitPack](https://jitpack.io/#uzinfowebuz/ui-message-stream)** — no Maven Central
release required. JitPack builds the modules on demand from a pushed git tag. Add the JitPack
repository and depend on the starter using JitPack's multi-module coordinate
(`com.github.<user>.<repo>:<module>:<tag>`):

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.uzinfowebuz.ui-message-stream</groupId>
    <artifactId>ui-message-stream-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

> The `version` is the git **tag** (e.g. `0.1.0`); use `master-SNAPSHOT` to track the latest commit.
> Because the project targets Java 25, [`jitpack.yml`](jitpack.yml) provisions a Temurin 25 JDK via
> SDKMAN before building. Publishing a release is just: `git tag 0.1.0 && git push origin 0.1.0`.

The starter brings `core` + `spring` transitively and auto-registers the default `ResponseMapper`
(`@ConditionalOnMissingBean`). The library's own Spring / Reactor / Spring AI dependencies are
`provided` — your Spring Boot app already supplies them.

## Usage

Reactive (WebFlux) controller streaming a Spring AI `ChatClient` response:

```java
@PostMapping("/chat")
ResponseEntity<Flux<ServerSentEvent<String>>> chat(@RequestBody UiMessageRequest body) {
    var messages = UiMessageRequestAdapter.toSpringAiMessages(body);
    var upstream = chatClient.prompt().messages(messages).stream().chatClientResponse();
    return UiMessageStreamResponse.of(new UiMessageStream().from(upstream));
}
```

Custom application data and a custom mapping are the *only* extension points:

```java
ResponseMapper mapper = (response, writer) -> {
    writer.text(response.chatResponse().getResults().get(0).getOutput().getText());
    writer.data("artifact", Map.of("id", "a1", "kind", "quiz")); // any JSON-serializable payload
};
UiMessageStreamResponse.of(new UiMessageStream().from(upstream, mapper));
```

Imperative producers (no Spring AI) use `create(...)`:

```java
UiMessageStreamResponse.of(new UiMessageStream().create(writer -> {
    writer.start("msg-1");
    writer.text("Hello");
    writer.data("artifact", Map.of("kind", "quiz"));
}));
```

### Servlet (Spring MVC) usage

For servlet-stack apps, `UiMessageStreamEmitter` drives the *same* core writer over a Spring MVC
`SseEmitter` (identical frames to the reactive bridge). The iteration is blocking, so hand it your
own `Executor` (e.g. a dedicated chat thread pool or a virtual-thread executor):

```java
@PostMapping("/chat")
SseEmitter chat(@RequestBody UiMessageRequest body, HttpServletResponse response) {
    UiMessageStreamHttp.applyHeaders(response); // the two required headers
    var messages = UiMessageRequestAdapter.toSpringAiMessages(body);
    var upstream = chatClient.prompt().messages(messages).stream().chatClientResponse();
    return new UiMessageStreamEmitter().from(upstream, ChatClientResponseMapper.DEFAULT, chatExecutor);
}
```

### Native tool input + output (opt-in)

Spring AI 2.0 executes tools through a `ToolCallingAdvisor`. The default response mapper can map model
tool calls, but it does not receive the `ToolCallingManager` result as a distinct response chunk. Opt in
to the library's custom tool advisor to record both sides of the loop:

```properties
uimessagestream.tool-io.native=true
```

Then create a per-request `SerializedPartSink`, publish it in the tool context, and pass the same sink
to the transport. `UiMessageStreamToolAdvisor` owns the Spring AI tool loop and delegates execution
through its private `RecordingToolCallingManager`, which emits paired `tool-input-available` and
`tool-output-available` parts onto that sink. The idiomatic way to publish the sink is the
`UiMessageStreamAdvisor` (a Spring AI `StreamAdvisor`):

```java
SerializedPartSink sink = new SerializedPartSink();
var upstream = chatClient.prompt()
        .messages(messages)
        .advisors(new UiMessageStreamAdvisor(sink))   // injects the sink into the tool context
        .stream().chatClientResponse();

// reactive
UiMessageStreamResponse.of(new UiMessageStream().from(upstream, ChatClientResponseMapper.TEXT_ONLY, sink));
// or servlet
new UiMessageStreamEmitter().from(upstream, ChatClientResponseMapper.TEXT_ONLY, sink, chatExecutor);
```

The same sink is the public hook for emitting custom `data-*` parts from inside your `@Tool` methods,
in correct order relative to text:

```java
sink.data("artifact", Map.of("id", "a1", "kind", "quiz"));
```

### Tool-call errors and human-in-the-loop approval (opt-in)

With native tool I/O enabled, the advisor's recording manager also emits `tool-output-error` when a
tool throws (opt into a throwing `ToolExecutionExceptionProcessor` so a failure surfaces instead of
becoming an ordinary tool result).

For human-in-the-loop, declare an `ApprovalPolicy` bean to gate specific tools:

```java
@Bean
ApprovalPolicy approvalPolicy() {
    return (toolName, input) -> "transferFunds".equals(toolName); // gate sensitive tools
}
```

A gated call emits `tool-approval-request` and **pauses the turn** (nothing runs); the stream finishes
and the client shows approve/deny. On the next request, parse the decision and hand it to the gate via
the tool context, alongside the replayed history:

```java
var decisions = UiMessageRequestAdapter.toolApprovalDecisions(body); // Map<toolCallId, approved>
var messages  = UiMessageRequestAdapter.toSpringAiMessages(body);     // replays prior tool turns

SerializedPartSink sink = new SerializedPartSink();
var upstream = chatClient.prompt()
        .messages(messages)
        .advisors(new UiMessageStreamAdvisor(sink))
        .tools(t -> t.context(RecordingToolCallingManager.APPROVALS_KEY, decisions))
        .stream().chatClientResponse();
UiMessageStreamResponse.of(new UiMessageStream().from(upstream, ChatClientResponseMapper.TEXT_ONLY, sink));
```

On approval the tool runs and emits `tool-output-available`; on denial it emits `tool-output-denied` and
the model is told so it can respond. **Scope:** the library owns the wire parts, the inbound parsing and
the gate; cross-request continuity follows the *stateless-replay* model (`useChat` resends the full
history, keyed by a stable `toolCallId`) — it does not persist server-side sessions.

> ⚠️ **Trust model.** Stateless replay means the *client* supplies the approval decisions and the prior
> tool turns. A hostile client can claim `approved: true` for any call, flip an earlier denial, or
> fabricate whole tool calls with invented outputs that the adapter will replay to the model as fact.
> If a gated tool protects anything that matters, verify server-side: persist the pending
> `approvalId`/`toolCallId` pairs (or sign them, e.g. HMAC over `toolCallId + toolName + input`) and
> check inbound decisions and replayed tool turns against your own record before executing.

## Security defaults

- **Error masking.** Failures stream as `{"type":"error","errorText":"An error occurred."}` —
  exception messages are never sent to the client by default (they leak paths, hosts, SQL, provider
  error bodies). Opt in per transport/advisor with `ErrorMessageResolver.MESSAGE` (or your own
  resolver), or set `uimessagestream.errors.include-message=true` for the starter-wired tool advisor.
- **Inbound `system` messages are dropped.** `useChat` never sends `role:"system"`; honouring one from
  the request body would let any client override your server-side system prompt. Opt in (only if you
  knowingly round-trip your own system message) via
  `UiMessageRequestAdapter.toSpringAiMessages(request, resolver, true)`.
- **`file` URLs are scheme-restricted.** `MediaResolver.DEFAULT` accepts only absolute `http(s)` URLs —
  `file:`, `data:` and friends are rejected. A custom resolver that fetches bytes must also guard
  against SSRF (block private/link-local ranges, cap response size).

## Build

```bash
cd ui-message-stream
./mvnw clean install
```

## Native Spring AI integration

The default path above (`ResponseMapper` over a `ChatClient` response `Flux`) remains the simplest seam.
[`docs/native-spring-ai-integration.md`](docs/native-spring-ai-integration.md) records how the shipped
Spring AI 2.0 integration composes `UiMessageStreamAdvisor`, `UiMessageStreamToolAdvisor`, and the
advisor-private recording manager without replacing the application's global tool manager.

## Notes & extension points

- **`tool-output-available`** is not emitted by the *default* mapper (`ChatClientResponseMapper.DEFAULT`
  emits text + `tool-input-available`): the manager result is not a distinct element on the
  `chatClientResponse()` stream. Enable the opt-in `UiMessageStreamToolAdvisor` (see *Native tool input
  + output*) to emit both natively, or drive the writer yourself if your app manages tools manually.
  The default mapper assumes a provider that
  delivers each tool call as one complete unit (e.g. Google GenAI / Gemini); providers that stream
  partial argument deltas should supply a custom mapper.
- **`file` → `Media`** inbound conversion is handled by `UiMessageRequestAdapter` via a pluggable
  `MediaResolver` (the URL-based default references the URL as a `URI`; swap in your own to fetch
  bytes or resolve a blob store). Files the resolver cannot handle are skipped, never failing the
  request.
