package uz.uzinfoweb.uimessagestream.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One frame of the AI SDK v6 <em>UI Message Stream</em> protocol.
 *
 * <p>This is a closed (sealed) set of records — exactly one per wire {@code type} described at
 * <a href="https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol">ai-sdk.dev/docs/ai-sdk-ui/stream-protocol</a>.
 * Each record knows its wire {@link #type()} and renders its remaining fields, in order, via
 * {@link #body()}. {@link PartSerializer} turns a part into compact JSON with {@code "type"} first
 * and {@code null} fields omitted.
 *
 * <p>The type carries no application concepts: custom application data travels exclusively through
 * {@link DataPart} ({@code data-<name>}) with an arbitrary JSON-serializable payload.
 */
public sealed interface UiMessagePart
        permits UiMessagePart.Start, UiMessagePart.StartStep,
                UiMessagePart.TextStart, UiMessagePart.TextDelta, UiMessagePart.TextEnd,
                UiMessagePart.ReasoningStart, UiMessagePart.ReasoningDelta, UiMessagePart.ReasoningEnd,
                UiMessagePart.ToolInputStart, UiMessagePart.ToolInputDelta,
                UiMessagePart.ToolInputAvailable, UiMessagePart.ToolOutputAvailable,
                UiMessagePart.SourceUrl, UiMessagePart.SourceDocument, UiMessagePart.FilePart,
                UiMessagePart.DataPart, UiMessagePart.ErrorPart,
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

    // --- Stream / step lifecycle ------------------------------------------------------------

    /** {@code {"type":"start","messageId":"..."}} — opens the assistant message. */
    record Start(String messageId) implements UiMessagePart {
        public String type() { return "start"; }
        public Map<String, Object> body() { return fields("messageId", messageId); }
    }

    /** {@code {"type":"start-step"}} — opens a step within the message. */
    record StartStep() implements UiMessagePart {
        public String type() { return "start-step"; }
        public Map<String, Object> body() { return fields(); }
    }

    // --- Text block -------------------------------------------------------------------------

    /** {@code {"type":"text-start","id":"..."}} */
    record TextStart(String id) implements UiMessagePart {
        public String type() { return "text-start"; }
        public Map<String, Object> body() { return fields("id", id); }
    }

    /** {@code {"type":"text-delta","id":"...","delta":"..."}} */
    record TextDelta(String id, String delta) implements UiMessagePart {
        public String type() { return "text-delta"; }
        public Map<String, Object> body() { return fields("id", id, "delta", delta); }
    }

    /** {@code {"type":"text-end","id":"..."}} */
    record TextEnd(String id) implements UiMessagePart {
        public String type() { return "text-end"; }
        public Map<String, Object> body() { return fields("id", id); }
    }

    // --- Reasoning block --------------------------------------------------------------------

    /** {@code {"type":"reasoning-start","id":"..."}} */
    record ReasoningStart(String id) implements UiMessagePart {
        public String type() { return "reasoning-start"; }
        public Map<String, Object> body() { return fields("id", id); }
    }

    /** {@code {"type":"reasoning-delta","id":"...","delta":"..."}} */
    record ReasoningDelta(String id, String delta) implements UiMessagePart {
        public String type() { return "reasoning-delta"; }
        public Map<String, Object> body() { return fields("id", id, "delta", delta); }
    }

    /** {@code {"type":"reasoning-end","id":"..."}} */
    record ReasoningEnd(String id) implements UiMessagePart {
        public String type() { return "reasoning-end"; }
        public Map<String, Object> body() { return fields("id", id); }
    }

    // --- Tool input / output ----------------------------------------------------------------

    /** {@code {"type":"tool-input-start","toolCallId":"...","toolName":"..."}} */
    record ToolInputStart(String toolCallId, String toolName) implements UiMessagePart {
        public String type() { return "tool-input-start"; }
        public Map<String, Object> body() { return fields("toolCallId", toolCallId, "toolName", toolName); }
    }

    /** {@code {"type":"tool-input-delta","toolCallId":"...","inputTextDelta":"..."}} */
    record ToolInputDelta(String toolCallId, String inputTextDelta) implements UiMessagePart {
        public String type() { return "tool-input-delta"; }
        public Map<String, Object> body() { return fields("toolCallId", toolCallId, "inputTextDelta", inputTextDelta); }
    }

    /** {@code {"type":"tool-input-available","toolCallId":"...","toolName":"...","input":{...}}} */
    record ToolInputAvailable(String toolCallId, String toolName, Object input) implements UiMessagePart {
        public String type() { return "tool-input-available"; }
        public Map<String, Object> body() { return fields("toolCallId", toolCallId, "toolName", toolName, "input", input); }
    }

    /** {@code {"type":"tool-output-available","toolCallId":"...","output":{...}}} */
    record ToolOutputAvailable(String toolCallId, Object output) implements UiMessagePart {
        public String type() { return "tool-output-available"; }
        public Map<String, Object> body() { return fields("toolCallId", toolCallId, "output", output); }
    }

    // --- Sources / files --------------------------------------------------------------------

    /** {@code {"type":"source-url","sourceId":"...","url":"..."}} */
    record SourceUrl(String sourceId, String url) implements UiMessagePart {
        public String type() { return "source-url"; }
        public Map<String, Object> body() { return fields("sourceId", sourceId, "url", url); }
    }

    /** {@code {"type":"source-document","sourceId":"...","mediaType":"...","title":"..."}} */
    record SourceDocument(String sourceId, String mediaType, String title) implements UiMessagePart {
        public String type() { return "source-document"; }
        public Map<String, Object> body() { return fields("sourceId", sourceId, "mediaType", mediaType, "title", title); }
    }

    /** {@code {"type":"file","url":"...","mediaType":"..."}} */
    record FilePart(String url, String mediaType) implements UiMessagePart {
        public String type() { return "file"; }
        public Map<String, Object> body() { return fields("url", url, "mediaType", mediaType); }
    }

    // --- Generic application data -----------------------------------------------------------

    /**
     * {@code {"type":"data-<name>","id":"...","data":{...}}} — the single, generic extension point.
     *
     * <p>{@code id} is optional; reusing the same {@code id} lets a client reconcile/replace the
     * part in place. {@code data} is any JSON-serializable payload supplied by the application.
     */
    record DataPart(String name, String id, Object data) implements UiMessagePart {
        public DataPart {
            Objects.requireNonNull(name, "data part name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("data part name must not be blank");
            }
        }
        public String type() { return "data-" + name; }
        public Map<String, Object> body() { return fields("id", id, "data", data); }
    }

    // --- Error / termination ----------------------------------------------------------------

    /** {@code {"type":"error","errorText":"..."}} */
    record ErrorPart(String errorText) implements UiMessagePart {
        public String type() { return "error"; }
        public Map<String, Object> body() { return fields("errorText", errorText); }
    }

    /** {@code {"type":"finish-step"}} */
    record FinishStep() implements UiMessagePart {
        public String type() { return "finish-step"; }
        public Map<String, Object> body() { return fields(); }
    }

    /** {@code {"type":"finish"}} */
    record Finish() implements UiMessagePart {
        public String type() { return "finish"; }
        public Map<String, Object> body() { return fields(); }
    }

    /** {@code {"type":"abort","reason":"..."}} */
    record Abort(String reason) implements UiMessagePart {
        public String type() { return "abort"; }
        public Map<String, Object> body() { return fields("reason", reason); }
    }
}
