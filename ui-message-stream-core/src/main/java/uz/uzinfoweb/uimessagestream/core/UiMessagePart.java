package uz.uzinfoweb.uimessagestream.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One frame of the AI SDK v6 <em>UI Message Stream</em> protocol.
 *
 * <p>This is a closed (sealed) set of records — exactly one per wire {@code type} described at
 * <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol">ai-sdk.dev/docs/ai-sdk-ui/stream-protocol</a>
 * and matching the chunk union in {@code vercel/ai} at tag {@code ai@6.0.0}
 * ({@code packages/ai/src/ui-message-stream/ui-message-chunks.ts}). Each record knows its wire
 * {@link #type()} and renders its remaining fields, in order, via {@link #body()}.
 * {@link PartSerializer} turns a part into compact JSON with {@code "type"} first and {@code null}
 * fields omitted.
 *
 * <p>Optional protocol fields (e.g. {@code providerMetadata}, {@code providerExecuted},
 * {@code dynamic}, {@code title}) are modelled as nullable record components: every part offers a
 * convenience constructor taking only the required fields (so simple call sites are unchanged), and
 * a canonical constructor taking the optional fields too. A {@code null} optional is dropped from
 * the wire by {@link #body()}.
 *
 * <p>The type carries no application concepts: custom application data travels exclusively through
 * {@link DataPart} ({@code data-<name>}) with an arbitrary JSON-serializable payload.
 */
public sealed interface UiMessagePart
        permits UiMessagePart.Start, UiMessagePart.StartStep,
                UiMessagePart.TextStart, UiMessagePart.TextDelta, UiMessagePart.TextEnd,
                UiMessagePart.ReasoningStart, UiMessagePart.ReasoningDelta, UiMessagePart.ReasoningEnd,
                UiMessagePart.ToolInputStart, UiMessagePart.ToolInputDelta,
                UiMessagePart.ToolInputAvailable, UiMessagePart.ToolInputError,
                UiMessagePart.ToolOutputAvailable, UiMessagePart.ToolOutputError,
                UiMessagePart.ToolApprovalRequest, UiMessagePart.ToolOutputDenied,
                UiMessagePart.SourceUrl, UiMessagePart.SourceDocument, UiMessagePart.FilePart,
                UiMessagePart.DataPart, UiMessagePart.ErrorPart, UiMessagePart.MessageMetadata,
                UiMessagePart.FinishStep, UiMessagePart.Finish, UiMessagePart.Abort {

    /** The wire {@code "type"} value of this part (e.g. {@code "text-delta"}, {@code "data-foo"}). */
    String type();

    /**
     * The part's fields <em>excluding</em> {@code type}, in wire order. Implementations omit any
     * {@code null}-valued field, so the serialized frame never carries explicit nulls.
     */
    Map<String, Object> body();

    /** Builds an ordered field map from {@code (key, value, key, value, ...)}, dropping null values. */
    private static Map<String, Object> fields(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                map.put((String) pairs[i], value);
            }
        }
        return map;
    }

    /** True for the six text/reasoning lifecycle parts, whose ordering is owned by the writer. */
    static boolean isTextLifecycle(UiMessagePart part) {
        return part instanceof TextStart || part instanceof TextDelta || part instanceof TextEnd
                || part instanceof ReasoningStart || part instanceof ReasoningDelta
                || part instanceof ReasoningEnd;
    }

    // --- Stream / step lifecycle ------------------------------------------------------------

    /** {@code {"type":"start","messageId":"...","messageMetadata":...}} — opens the assistant message. */
    record Start(String messageId, Object messageMetadata) implements UiMessagePart {
        public Start(String messageId) { this(messageId, null); }
        public String type() { return "start"; }
        public Map<String, Object> body() {
            return fields("messageId", messageId, "messageMetadata", messageMetadata);
        }
    }

    /** {@code {"type":"start-step"}} — opens a step within the message. */
    record StartStep() implements UiMessagePart {
        public String type() { return "start-step"; }
        public Map<String, Object> body() { return fields(); }
    }

    // --- Text block -------------------------------------------------------------------------

    /** {@code {"type":"text-start","id":"...","providerMetadata":...}} */
    record TextStart(String id, Object providerMetadata) implements UiMessagePart {
        public TextStart(String id) { this(id, null); }
        public String type() { return "text-start"; }
        public Map<String, Object> body() { return fields("id", id, "providerMetadata", providerMetadata); }
    }

    /** {@code {"type":"text-delta","id":"...","delta":"...","providerMetadata":...}} */
    record TextDelta(String id, String delta, Object providerMetadata) implements UiMessagePart {
        public TextDelta(String id, String delta) { this(id, delta, null); }
        public String type() { return "text-delta"; }
        public Map<String, Object> body() {
            return fields("id", id, "delta", delta, "providerMetadata", providerMetadata);
        }
    }

    /** {@code {"type":"text-end","id":"...","providerMetadata":...}} */
    record TextEnd(String id, Object providerMetadata) implements UiMessagePart {
        public TextEnd(String id) { this(id, null); }
        public String type() { return "text-end"; }
        public Map<String, Object> body() { return fields("id", id, "providerMetadata", providerMetadata); }
    }

    // --- Reasoning block --------------------------------------------------------------------

    /** {@code {"type":"reasoning-start","id":"...","providerMetadata":...}} */
    record ReasoningStart(String id, Object providerMetadata) implements UiMessagePart {
        public ReasoningStart(String id) { this(id, null); }
        public String type() { return "reasoning-start"; }
        public Map<String, Object> body() { return fields("id", id, "providerMetadata", providerMetadata); }
    }

    /** {@code {"type":"reasoning-delta","id":"...","delta":"...","providerMetadata":...}} */
    record ReasoningDelta(String id, String delta, Object providerMetadata) implements UiMessagePart {
        public ReasoningDelta(String id, String delta) { this(id, delta, null); }
        public String type() { return "reasoning-delta"; }
        public Map<String, Object> body() {
            return fields("id", id, "delta", delta, "providerMetadata", providerMetadata);
        }
    }

    /** {@code {"type":"reasoning-end","id":"...","providerMetadata":...}} */
    record ReasoningEnd(String id, Object providerMetadata) implements UiMessagePart {
        public ReasoningEnd(String id) { this(id, null); }
        public String type() { return "reasoning-end"; }
        public Map<String, Object> body() { return fields("id", id, "providerMetadata", providerMetadata); }
    }

    // --- Tool input / output ----------------------------------------------------------------

    /** {@code {"type":"tool-input-start","toolCallId":"...","toolName":"...","providerExecuted":...,"dynamic":...,"title":"..."}} */
    record ToolInputStart(String toolCallId, String toolName, Boolean providerExecuted, Boolean dynamic,
                          String title) implements UiMessagePart {
        public ToolInputStart(String toolCallId, String toolName) { this(toolCallId, toolName, null, null, null); }
        public String type() { return "tool-input-start"; }
        public Map<String, Object> body() {
            return fields("toolCallId", toolCallId, "toolName", toolName,
                    "providerExecuted", providerExecuted, "dynamic", dynamic, "title", title);
        }
    }

    /** {@code {"type":"tool-input-delta","toolCallId":"...","inputTextDelta":"..."}} */
    record ToolInputDelta(String toolCallId, String inputTextDelta) implements UiMessagePart {
        public String type() { return "tool-input-delta"; }
        public Map<String, Object> body() { return fields("toolCallId", toolCallId, "inputTextDelta", inputTextDelta); }
    }

    /** {@code {"type":"tool-input-available","toolCallId":"...","toolName":"...","input":{...},...}} */
    record ToolInputAvailable(String toolCallId, String toolName, Object input, Boolean providerExecuted,
                              Object providerMetadata, Boolean dynamic, String title) implements UiMessagePart {
        public ToolInputAvailable(String toolCallId, String toolName, Object input) {
            this(toolCallId, toolName, input, null, null, null, null);
        }
        public String type() { return "tool-input-available"; }
        public Map<String, Object> body() {
            return fields("toolCallId", toolCallId, "toolName", toolName, "input", input,
                    "providerExecuted", providerExecuted, "providerMetadata", providerMetadata,
                    "dynamic", dynamic, "title", title);
        }
    }

    /** {@code {"type":"tool-input-error","toolCallId":"...","toolName":"...","input":{...},"errorText":"...",...}} */
    record ToolInputError(String toolCallId, String toolName, Object input, String errorText,
                          Boolean providerExecuted, Object providerMetadata, Boolean dynamic, String title)
            implements UiMessagePart {
        public ToolInputError(String toolCallId, String toolName, Object input, String errorText) {
            this(toolCallId, toolName, input, errorText, null, null, null, null);
        }
        public String type() { return "tool-input-error"; }
        public Map<String, Object> body() {
            return fields("toolCallId", toolCallId, "toolName", toolName, "input", input, "errorText", errorText,
                    "providerExecuted", providerExecuted, "providerMetadata", providerMetadata,
                    "dynamic", dynamic, "title", title);
        }
    }

    /** {@code {"type":"tool-output-available","toolCallId":"...","output":{...},"providerExecuted":...,"dynamic":...,"preliminary":...}} */
    record ToolOutputAvailable(String toolCallId, Object output, Boolean providerExecuted, Boolean dynamic,
                               Boolean preliminary) implements UiMessagePart {
        public ToolOutputAvailable(String toolCallId, Object output) { this(toolCallId, output, null, null, null); }
        public String type() { return "tool-output-available"; }
        public Map<String, Object> body() {
            return fields("toolCallId", toolCallId, "output", output,
                    "providerExecuted", providerExecuted, "dynamic", dynamic, "preliminary", preliminary);
        }
    }

    /** {@code {"type":"tool-output-error","toolCallId":"...","errorText":"...","providerExecuted":...,"dynamic":...}} */
    record ToolOutputError(String toolCallId, String errorText, Boolean providerExecuted, Boolean dynamic)
            implements UiMessagePart {
        public ToolOutputError(String toolCallId, String errorText) { this(toolCallId, errorText, null, null); }
        public String type() { return "tool-output-error"; }
        public Map<String, Object> body() {
            return fields("toolCallId", toolCallId, "errorText", errorText,
                    "providerExecuted", providerExecuted, "dynamic", dynamic);
        }
    }

    // --- Tool approval (human-in-the-loop) --------------------------------------------------

    /** {@code {"type":"tool-approval-request","approvalId":"...","toolCallId":"..."}} — pauses for user approval. */
    record ToolApprovalRequest(String approvalId, String toolCallId) implements UiMessagePart {
        public String type() { return "tool-approval-request"; }
        public Map<String, Object> body() { return fields("approvalId", approvalId, "toolCallId", toolCallId); }
    }

    /** {@code {"type":"tool-output-denied","toolCallId":"..."}} — the user denied this tool call. */
    record ToolOutputDenied(String toolCallId) implements UiMessagePart {
        public String type() { return "tool-output-denied"; }
        public Map<String, Object> body() { return fields("toolCallId", toolCallId); }
    }

    // --- Sources / files --------------------------------------------------------------------

    /** {@code {"type":"source-url","sourceId":"...","url":"...","title":"...","providerMetadata":...}} */
    record SourceUrl(String sourceId, String url, String title, Object providerMetadata) implements UiMessagePart {
        public SourceUrl(String sourceId, String url) { this(sourceId, url, null, null); }
        public String type() { return "source-url"; }
        public Map<String, Object> body() {
            return fields("sourceId", sourceId, "url", url, "title", title, "providerMetadata", providerMetadata);
        }
    }

    /** {@code {"type":"source-document","sourceId":"...","mediaType":"...","title":"...","filename":"...","providerMetadata":...}} */
    record SourceDocument(String sourceId, String mediaType, String title, String filename,
                          Object providerMetadata) implements UiMessagePart {
        public SourceDocument(String sourceId, String mediaType, String title) {
            this(sourceId, mediaType, title, null, null);
        }
        public String type() { return "source-document"; }
        public Map<String, Object> body() {
            return fields("sourceId", sourceId, "mediaType", mediaType, "title", title,
                    "filename", filename, "providerMetadata", providerMetadata);
        }
    }

    /** {@code {"type":"file","url":"...","mediaType":"...","providerMetadata":...}} */
    record FilePart(String url, String mediaType, Object providerMetadata) implements UiMessagePart {
        public FilePart(String url, String mediaType) { this(url, mediaType, null); }
        public String type() { return "file"; }
        public Map<String, Object> body() {
            return fields("url", url, "mediaType", mediaType, "providerMetadata", providerMetadata);
        }
    }

    // --- Generic application data -----------------------------------------------------------

    /**
     * {@code {"type":"data-<name>","id":"...","data":{...},"transient":...}} — the single, generic
     * extension point.
     *
     * <p>{@code id} is optional; reusing the same {@code id} lets a client reconcile/replace the
     * part in place. {@code data} is any JSON-serializable payload supplied by the application.
     * {@code transient} (mapped from the {@code transientPart} component, since {@code transient} is a
     * Java keyword) marks a fire-and-forget data event the client does <em>not</em> persist into the
     * message history.
     */
    record DataPart(String name, String id, Object data, Boolean transientPart) implements UiMessagePart {
        public DataPart {
            Objects.requireNonNull(name, "data part name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("data part name must not be blank");
            }
        }
        public DataPart(String name, String id, Object data) { this(name, id, data, null); }
        public String type() { return "data-" + name; }
        public Map<String, Object> body() {
            return fields("id", id, "data", data, "transient", transientPart);
        }
    }

    // --- Error / metadata / termination -----------------------------------------------------

    /** {@code {"type":"error","errorText":"..."}} */
    record ErrorPart(String errorText) implements UiMessagePart {
        public String type() { return "error"; }
        public Map<String, Object> body() { return fields("errorText", errorText); }
    }

    /** {@code {"type":"message-metadata","messageMetadata":...}} — updates the message's metadata in place. */
    record MessageMetadata(Object messageMetadata) implements UiMessagePart {
        public String type() { return "message-metadata"; }
        public Map<String, Object> body() { return fields("messageMetadata", messageMetadata); }
    }

    /** {@code {"type":"finish-step"}} */
    record FinishStep() implements UiMessagePart {
        public String type() { return "finish-step"; }
        public Map<String, Object> body() { return fields(); }
    }

    /** {@code {"type":"finish","finishReason":"...","messageMetadata":...}} */
    record Finish(String finishReason, Object messageMetadata) implements UiMessagePart {
        public Finish() { this(null, null); }
        public String type() { return "finish"; }
        public Map<String, Object> body() {
            return fields("finishReason", finishReason, "messageMetadata", messageMetadata);
        }
    }

    /** {@code {"type":"abort"}} — v6 carries no fields. */
    record Abort() implements UiMessagePart {
        public String type() { return "abort"; }
        public Map<String, Object> body() { return fields(); }
    }
}
