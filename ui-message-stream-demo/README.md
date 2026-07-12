# ui-message-stream-demo

A runnable Spring Boot app exercising **every feature of the library** against a scripted offline
`ChatModel` — **no API key needed**. The bundled web page is a miniature `useChat`-style client:
rendered chat on the left, the raw protocol frames on the right, so you see exactly what goes over
the wire.

## Run it

```bash
# from the repository root (Java 25)
./mvnw -pl ui-message-stream-demo -am spring-boot:run
```

Open **http://localhost:8080** and try the suggestion chips, or talk to it:

| You type | What you see |
|---|---|
| anything (e.g. *hello*) | plain streamed text — every word is a `text-delta` frame |
| `weather in Samarkand` | `tool-input-available` → `tool-output-available` (native tool I/O) **plus** a `data-weather-card` part pushed from *inside* the tool, rendered as a card |
| `what time is it` | a plain tool round trip |
| `transfer money` | **human-in-the-loop**: `tool-approval-request` pauses the turn; click *Approve* / *Deny* — the page replays history with your decision and the turn resumes (executes, or streams a denial) |
| `please fail` | the tool throws → `tool-output-error` + an `error` frame with the **masked** message (flip `uimessagestream.errors.include-message` in `application.yaml` to compare) |

The **protocol tour** button calls the imperative `create(writer -> ...)` endpoint — no model at
all — emitting one of each frame kind: reasoning, text, `data-*`, `source-url`, `file`,
`message-metadata`.

The **transport** dropdown switches between the two transports serving identical frames:
`/api/chat` (reactive `Flux<ServerSentEvent>`) and `/api/chat-mvc` (servlet `SseEmitter` on a
virtual thread).

## curl it

```bash
curl -N -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"id":"u1","role":"user","parts":[{"type":"text","text":"weather in Samarkand"}]}]}'

curl -N localhost:8080/api/protocol-tour
```

## Where each feature lives

| Feature | Demo code | Library API |
|---|---|---|
| useChat request body → Spring AI messages | `ChatController.upstream` | `UiMessageRequestAdapter.toSpringAiMessages` |
| Reactive SSE bridge + required headers | `ChatController.chat` | `UiMessageStream.from` + `UiMessageStreamResponse.of` |
| Servlet SSE bridge | `ChatController.chatMvc` | `UiMessageStreamEmitter.from` + `UiMessageStreamHttp.applyHeaders` |
| Sink injection via advisor (no manual `toolContext` map) | `ChatController.upstream` | `UiMessageStreamAdvisor` |
| Native tool input/output frames | `application.yaml` → `uimessagestream.tool-io.native=true` | `UiMessageStreamToolAdvisor` with an advisor-private recording manager |
| Custom `data-*` part from inside a tool | `DemoTools.getWeather` | `SerializedPartSink.data` |
| Human-in-the-loop approval | `DemoConfiguration.approvalPolicy`, decisions in `ChatController.upstream` | `ApprovalPolicy`, `UiMessageRequestAdapter.toolApprovalDecisions` |
| Tool failure → `tool-output-error` | `DemoTools.brokenTool` + throwing `DefaultToolExecutionExceptionProcessor` | `ErrorMessageResolver` (masked by default) |
| Mapper override (text-only, tools come from the advisor) | `DemoConfiguration.responseMapper` | `ResponseMapper` bean replaces the starter default |
| Imperative producer, all frame kinds | `ChatController.protocolTour` | `UiMessageStream.create` |

## Swap in a real model

`ScriptedChatModel` exists only so the demo runs offline. To use a real provider, delete it and the
`chatClient`/`toolCallingManager` beans, add e.g. `spring-ai-starter-model-openai` (or
`google-genai`, ...), configure your key — Spring AI auto-configures `ChatClient.Builder` and
`ToolCallingManager`, and the starter's `uimessagestream.tool-io.native=true` adds its custom
`ToolAdvisor` to the managed builder without replacing the manager. The controller code does not
change.
