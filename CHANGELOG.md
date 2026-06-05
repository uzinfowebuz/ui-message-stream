# Changelog

All notable changes to **ui-message-stream** are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.1.0]: https://github.com/uzinfoweb/ui-message-stream/releases/tag/v0.1.0
