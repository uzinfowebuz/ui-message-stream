# Changelog

All notable changes to **ui-message-stream** are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-06-06

Completes the AI SDK **v6** wire surface (validated against `ai@6.0.197`) and adds the human-in-the-loop
tool-approval flow.

### Added

- **New protocol parts** (across `UiMessagePart`, `UiMessageStreamWriter`, `SerializedPartSink`):
  `tool-input-error`, `tool-output-error`, `message-metadata`, `tool-approval-request`, and
  `tool-output-denied`.
- **Optional protocol fields** threaded through the existing parts via convenience constructors
  (null fields stay off the wire): `providerMetadata`, `toolMetadata`, `providerExecuted`, `dynamic`,
  `title`, `preliminary`, `filename`, `transient`, `reason` (on `abort`), plus `finishReason` /
  `messageMetadata` on `finish`/`start`. `toolMetadata` (all five tool chunks), `providerMetadata`
  (additionally on `tool-input-start` / `tool-output-available` / `tool-output-error`), and
  `abort.reason` are the optional fields the `ai` package added across the `6.0.x` line after `6.0.0`.
- **`dynamic:true` by default** on tool parts, so a `useChat` client renders them via its generic
  `dynamic-tool` path with no client-side tool typing. Configurable via
  `ChatClientResponseMapper.withDynamicTools(boolean)`, the `RecordingToolCallingManager` constructor,
  and the `uimessagestream.tool-io.dynamic` starter property (default `true`).
- **Tool-call errors**: `RecordingToolCallingManager` emits `tool-output-error` for each in-flight
  call when the delegated tool execution throws.
- **Human-in-the-loop tool approval**:
  - `ApprovalPolicy` SPI decides which calls require approval; the `RecordingToolCallingManager` gate
    emits `tool-approval-request` and pauses the turn (`ToolExecutionResult.returnDirect()`) until the
    user responds, emits `tool-output-denied` on a denial, and executes on approval.
  - Inbound `UiMessageRequest` models tool parts (`state`, `input`/`output`, `approval`) and exposes
    `approvals()`; `UiMessageRequestAdapter` reconstructs prior tool calls/responses for the resumed
    turn (stateless replay) and exposes `toolApprovalDecisions(...)` for the gate's tool context.
  - The starter registers a default no-op `ApprovalPolicy` (`@ConditionalOnMissingBean`) so the gate
    stays opt-in.
- **Generic writer hook** `UiMessageStreamWriter.part(UiMessagePart)` (and `SerializedPartSink.part`)
  for emitting fully-specified parts while preserving the text-block lifecycle; it rejects
  text/reasoning lifecycle parts so block-id ownership stays with the writer.

### Changed

- **`abort` reason is optional**: `UiMessageStreamWriter` offers both `abort()` and `abort(String
  reason)`, and `UiMessagePart.Abort` carries a nullable `reason` (omitted when null) — matching
  `ai@6.0.197`, where the `abort` chunk gained an optional `reason` field after `6.0.0`.
- **Required-field guards**: `Objects.requireNonNull` now rejects a null `title` on `source-document`
  and a null `errorText` on `error` / `tool-input-error` / `tool-output-error`, so a careless null can
  no longer omit a key that the strict (`z.strictObject`) client schema requires.
- `UiMessageRequest.Part` gained tool/approval components; the four-arg
  `Part(type, text, url, mediaType)` convenience constructor is retained for text/file/data parts.

### Notes

- Pin the AI SDK to `ai@^6.0.0` (covers `6.0.197`, the latest stable `6.0.x`); the stream header stays
  `x-vercel-ai-ui-message-stream: v1`. v7-only chunks (`custom`, `reasoning-file`, and
  `tool-approval-response` as a stream chunk) are intentionally not implemented.

## [0.1.0] - 2026-06-05

First public release. Built against Spring Boot 4.0.6 (Spring Framework 7) and Spring AI 2.0.0-M8,
Java 25.

### Added

- **Core** (`ui-message-stream-core`, Jackson-only): the stateful `UiMessageStreamWriter` that owns
  the text-block lifecycle invariant, the sealed `UiMessagePart` records, and `PartSerializer`
  (compact one-line JSON, the `[DONE]` sentinel, and the required protocol header constants).
- **Reactive transport** `UiMessageStream`: a `Flux`-based bridge from a Spring AI `ChatClient`
  response stream to Server-Sent Events; the upstream subscription is disposed on cancel
  (client disconnect cancels the model call).
- **Servlet/MVC transport** `UiMessageStreamEmitter` (+ `UiMessageStreamHttp`): drives the *same*
  core writer over a Spring MVC `SseEmitter`, producing byte-for-byte identical frames to the
  reactive bridge. Includes a generic per-element tap and a terminal callback, and a convenience
  `from(upstream, mapper, executor)`.
- **Native tool I/O (opt-in)**: `RecordingToolCallingManager` decorates Spring AI's
  `ToolCallingManager` to emit `tool-input-available` before each tool runs and
  `tool-output-available` after (paired by `toolCallId`), into a thread-safe per-request
  `SerializedPartSink` shared by both transports. Registered by the Spring Boot starter behind
  `uimessagestream.tool-io.native=true` (off by default).
- **Public per-request sink** `SerializedPartSink`: serializes text and tool/`data-*` writes onto a
  single writer under a lock, so applications can push custom `data-*` parts from inside `@Tool`
  methods in correct order relative to text.
- **Response mappers**: `ChatClientResponseMapper.DEFAULT` (text + `tool-input-available`) and
  `ChatClientResponseMapper.TEXT_ONLY` (text only, to pair with native tool I/O).
- **Inbound `file` → `Media`**: `UiMessageRequestAdapter` converts inbound `file` parts into Spring
  AI `Media` via a pluggable `MediaResolver` (URL-based default); unresolvable files are skipped.
- **OSS readiness**: Apache-2.0 `LICENSE` + `NOTICE`, complete POM metadata, and a `release` profile
  (sources + javadoc jars, GPG signing, `central-publishing-maven-plugin`) for Maven Central.

[0.2.0]: https://github.com/uzinfoweb/ui-message-stream/releases/tag/v0.2.0
[0.1.0]: https://github.com/uzinfoweb/ui-message-stream/releases/tag/v0.1.0
