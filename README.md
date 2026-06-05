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
  - `tool-input-start`, `tool-input-delta`, `tool-input-available`, `tool-output-available`
  - `source-url`, `source-document`, `file`
  - `data-<name>` — generic application data; optional `id` reconciles/updates the part in place
  - `error` (`errorText`)
  - `finish-step`, `finish`, `abort` (`reason`)

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
| `ui-message-stream-spring` | core + spring-web + spring-webmvc + reactor + spring-ai *(all `provided`)* | Reactive `UiMessageStream` **and** servlet `UiMessageStreamEmitter` transports; `ResponseMapper` / `ChatClientResponseMapper.DEFAULT` + `.TEXT_ONLY`; `UiMessageStreamResponse` / `UiMessageStreamHttp`; the thread-safe per-request `SerializedPartSink`; opt-in native tool I/O (`RecordingToolCallingManager`); inbound `UiMessageRequest` + adapter + pluggable `MediaResolver`. |
| `ui-message-stream-spring-boot-starter` | core + spring | `@AutoConfiguration` exposing the default `ResponseMapper` (`@ConditionalOnMissingBean`). |

Built against Spring Boot 4.0.6 (Spring Framework 7) and Spring AI 2.0.0-M8, Java 25.

## Install

```xml
<dependency>
    <groupId>uz.uzinfoweb</groupId>
    <artifactId>ui-message-stream-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

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

Spring AI runs tools internally, so the default mapper emits `tool-input-available` but never
`tool-output-available`. Opt in to native tool I/O via the starter:

```properties
uimessagestream.tool-io.native=true
```

Then create a per-request `SerializedPartSink`, publish it in the tool context, and pass the same
sink to the transport. A `RecordingToolCallingManager` emits `tool-input-available` +
`tool-output-available` (paired by `toolCallId`) onto that sink, serialized with the model's text:

```java
SerializedPartSink sink = new SerializedPartSink();
var upstream = chatClient.prompt()
        .messages(messages)
        .toolContext(Map.of(RecordingToolCallingManager.SINK_KEY, sink))
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

## Build

```bash
cd ui-message-stream
./mvnw clean install
```

## Native Spring AI integration (design note)

The default path above (`ResponseMapper` over a `ChatClient` response `Flux`) is the simplest seam, but
Spring AI exposes deeper ones. [`docs/native-spring-ai-integration.md`](docs/native-spring-ai-integration.md)
maps **all five** native seams discovered against the real Spring AI 2.0.0-M8 API — the `ToolCallingManager`
decorator (the only seam that natively surfaces tool **input + output**), the Advisors API
(`StreamAdvisor`), `ToolCallback`, `ChatModel`, and Micrometer Observation — with a layered diagram, a
comparison table, the recommended advisor + tool-manager design, and the purity guardrails. Seam A
(the `ToolCallingManager` decorator) is now **shipped** as the opt-in `RecordingToolCallingManager`
(see *Native tool input + output* above); the note remains the reference for the other seams.

## Notes & extension points

- **`tool-output-available`** is not emitted by the *default* mapper (`ChatClientResponseMapper.DEFAULT`
  emits text + `tool-input-available`): Spring AI executes tools internally and does not surface a
  distinct tool-result element on the `chatClientResponse()` stream. Enable the opt-in
  `RecordingToolCallingManager` (see *Native tool input + output*) to emit both natively, or drive the
  writer yourself if your app manages tools manually. The default mapper assumes a provider that
  delivers each tool call as one complete unit (e.g. Google GenAI / Gemini); providers that stream
  partial argument deltas should supply a custom mapper.
- **`file` → `Media`** inbound conversion is handled by `UiMessageRequestAdapter` via a pluggable
  `MediaResolver` (the URL-based default references the URL as a `URI`; swap in your own to fetch
  bytes or resolve a blob store). Files the resolver cannot handle are skipped, never failing the
  request.
