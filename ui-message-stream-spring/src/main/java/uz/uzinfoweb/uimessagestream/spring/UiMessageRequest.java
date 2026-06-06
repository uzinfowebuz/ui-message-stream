package uz.uzinfoweb.uimessagestream.spring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The inbound {@code useChat} request body:
 * <pre>{@code
 * { "messages": [
 *     { "id": "...", "role": "user",
 *       "parts": [ { "type": "text", "text": "..." },
 *                  { "type": "file", "url": "...", "mediaType": "..." } ] } ] }
 * }</pre>
 *
 * <p>Parts are modelled by a single lenient record (unknown {@code type}s and fields are ignored)
 * so that {@code data-*} and future part kinds do not break deserialization. Tool parts
 * ({@code type} {@code "tool-<name>"} or {@code "dynamic-tool"}) additionally carry the fields needed
 * for the human-in-the-loop approval flow — notably {@link Part#state()} and {@link Part#approval()}.
 * Convert to Spring AI messages with {@link UiMessageRequestAdapter}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UiMessageRequest(List<Message> messages) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String role, List<Part> parts) {
    }

    /**
     * A single message part. {@code type} is {@code "text"} / {@code "file"} / {@code "data-*"} /
     * {@code "tool-<name>"} / {@code "dynamic-tool"}. Tool fields are {@code null} for non-tool parts.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String type, String text, String url, String mediaType,
                       String toolCallId, String toolName, String state,
                       Object input, Object output, String errorText, Approval approval) {

        /** Convenience for {@code text}/{@code file}/{@code data-*} parts (tool fields default to {@code null}). */
        public Part(String type, String text, String url, String mediaType) {
            this(type, text, url, mediaType, null, null, null, null, null, null, null);
        }

        /** True for a tool invocation part ({@code tool-<name>} or {@code dynamic-tool}). */
        public boolean isToolPart() {
            return type != null && (type.startsWith("tool-") || type.equals("dynamic-tool"));
        }

        /** The tool name, from the {@code toolName} field or, for a {@code tool-<name>} part, from the type. */
        public String resolvedToolName() {
            if (toolName != null) {
                return toolName;
            }
            if (type != null && type.startsWith("tool-")) {
                return type.substring("tool-".length());
            }
            return null;
        }
    }

    /**
     * The approval object carried on a tool part. {@code approved} is {@code null} while the request is
     * outstanding ({@code state == "approval-requested"}) and set once the user responds
     * ({@code state == "approval-responded"}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Approval(String id, Boolean approved, String reason) {
    }

    /** A resolved user decision on a tool call, extracted from the inbound parts via {@link #approvals()}. */
    public record ToolApprovalDecision(String approvalId, String toolCallId, boolean approved, String reason) {
    }

    /** Every tool part whose user has responded (approved or denied), flattened across all messages. */
    public List<ToolApprovalDecision> approvals() {
        List<ToolApprovalDecision> decisions = new ArrayList<>();
        if (messages == null) {
            return decisions;
        }
        for (Message message : messages) {
            if (message == null || message.parts() == null) {
                continue;
            }
            for (Part part : message.parts()) {
                Approval approval = part.approval();
                if (approval != null && approval.approved() != null) {
                    decisions.add(new ToolApprovalDecision(
                            approval.id(), part.toolCallId(), approval.approved(), approval.reason()));
                }
            }
        }
        return decisions;
    }
}
