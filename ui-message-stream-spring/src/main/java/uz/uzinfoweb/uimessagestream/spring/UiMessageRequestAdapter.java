package uz.uzinfoweb.uimessagestream.spring;

import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts an inbound {@link UiMessageRequest} into Spring AI {@link Message}s.
 *
 * <p>Text parts are concatenated and mapped to {@link UserMessage}/{@link AssistantMessage} by role
 * (defaulting to user). {@code file} parts on a user message are turned into {@link Media} via a
 * {@link MediaResolver} (the {@linkplain MediaResolver#DEFAULT URL-based default} unless you pass
 * your own) and attached to the {@link UserMessage}; a part the resolver cannot handle is skipped
 * without failing the request.
 *
 * <p><b>System messages are dropped by default.</b> The request body is attacker-controlled (anyone
 * who can reach the endpoint can POST arbitrary JSON, and {@code useChat} itself never sends a
 * {@code system} role), so honouring an inbound {@code role:"system"} message would let a client
 * inject or override the application's system prompt. Set the system prompt server-side instead. An
 * application that deliberately round-trips its own system message through the client can opt in via
 * {@link #toSpringAiMessages(UiMessageRequest, MediaResolver, boolean)}.
 *
 * <p><b>Tool turns (HITL).</b> An assistant message's tool parts are reconstructed into an
 * {@link AssistantMessage} carrying {@link AssistantMessage.ToolCall}s, followed by a
 * {@link ToolResponseMessage} for any tool part already in a terminal state ({@code output-available} /
 * {@code output-denied} / {@code output-error}). This gives a resumed model call the prior tool context
 * (the <em>stateless-replay</em> model: {@code useChat} resends the full history). Use
 * {@link #toolApprovalDecisions(UiMessageRequest)} to extract the user's approve/deny decisions for the
 * {@link RecordingToolCallingManager} approval gate.
 */
public final class UiMessageRequestAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DENIED_MESSAGE = "Error: The user denied execution of this tool.";

    private UiMessageRequestAdapter() {
    }

    /** Converts using the {@linkplain MediaResolver#DEFAULT default URL-based} media resolver; system messages are dropped. */
    public static List<Message> toSpringAiMessages(UiMessageRequest request) {
        return toSpringAiMessages(request, MediaResolver.DEFAULT);
    }

    /** Converts using the supplied {@code mediaResolver} for inbound {@code file} parts; system messages are dropped. */
    public static List<Message> toSpringAiMessages(UiMessageRequest request, MediaResolver mediaResolver) {
        return toSpringAiMessages(request, mediaResolver, false);
    }

    /**
     * Converts, optionally honouring inbound {@code role:"system"} messages.
     *
     * @param allowSystemMessages {@code true} maps {@code role:"system"} to a {@link SystemMessage};
     *                            {@code false} (the safe default) drops it — a client-supplied system
     *                            message is a prompt-injection vector, see the class docs
     */
    public static List<Message> toSpringAiMessages(UiMessageRequest request, MediaResolver mediaResolver,
                                                   boolean allowSystemMessages) {
        Objects.requireNonNull(mediaResolver, "mediaResolver");
        List<Message> messages = new ArrayList<>();
        if (request == null || request.messages() == null) {
            return messages;
        }
        for (UiMessageRequest.Message uiMessage : request.messages()) {
            switch (normalizedRole(uiMessage)) {
                case "assistant" -> appendAssistant(messages, uiMessage);
                case "system" -> {
                    if (allowSystemMessages) {
                        messages.add(new SystemMessage(concatenateText(uiMessage)));
                    }
                }
                default -> messages.add(toUserMessage(concatenateText(uiMessage), uiMessage, mediaResolver));
            }
        }
        return messages;
    }

    /**
     * The user's approve/deny decisions keyed by {@code toolCallId} — publish this in the prompt's tool
     * context under {@link RecordingToolCallingManager#APPROVALS_KEY} so the approval gate can act on it.
     */
    public static Map<String, Boolean> toolApprovalDecisions(UiMessageRequest request) {
        Map<String, Boolean> decisions = new LinkedHashMap<>();
        if (request == null) {
            return decisions;
        }
        for (UiMessageRequest.ToolApprovalDecision decision : request.approvals()) {
            if (decision.toolCallId() != null) {
                decisions.put(decision.toolCallId(), decision.approved());
            }
        }
        return decisions;
    }

    private static void appendAssistant(List<Message> messages, UiMessageRequest.Message message) {
        String text = concatenateText(message);
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();

        if (message.parts() != null) {
            for (UiMessageRequest.Part part : message.parts()) {
                if (!part.isToolPart() || part.toolCallId() == null) {
                    continue;
                }
                String name = part.resolvedToolName();
                toolCalls.add(new AssistantMessage.ToolCall(part.toolCallId(), "function", name, toJson(part.input())));
                reconstructResponse(responses, part, name);
            }
        }

        if (toolCalls.isEmpty()) {
            messages.add(new AssistantMessage(text));
            return;
        }
        messages.add(AssistantMessage.builder().content(text).toolCalls(toolCalls).build());
        if (!responses.isEmpty()) {
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }
    }

    private static void reconstructResponse(List<ToolResponseMessage.ToolResponse> responses,
                                            UiMessageRequest.Part part, String name) {
        String data = switch (part.state() == null ? "" : part.state()) {
            case "output-available" -> part.output() != null ? toJson(part.output()) : null;
            case "output-denied" -> DENIED_MESSAGE;
            case "output-error" -> part.errorText() != null ? "Error: " + part.errorText() : null;
            default -> null;
        };
        if (data != null) {
            responses.add(new ToolResponseMessage.ToolResponse(part.toolCallId(), name, data));
        }
    }

    private static UserMessage toUserMessage(String text, UiMessageRequest.Message message,
                                             MediaResolver mediaResolver) {
        List<Media> media = resolveMedia(message, mediaResolver);
        if (media.isEmpty()) {
            return new UserMessage(text);
        }
        return UserMessage.builder().text(text).media(media).build();
    }

    private static List<Media> resolveMedia(UiMessageRequest.Message message, MediaResolver mediaResolver) {
        if (message.parts() == null) {
            return List.of();
        }
        List<Media> media = new ArrayList<>();
        for (UiMessageRequest.Part part : message.parts()) {
            if ("file".equals(part.type())) {
                mediaResolver.resolve(part.url(), part.mediaType()).ifPresent(media::add);
            }
        }
        return media;
    }

    private static String normalizedRole(UiMessageRequest.Message message) {
        return message.role() == null ? "user" : message.role().toLowerCase();
    }

    private static String concatenateText(UiMessageRequest.Message message) {
        if (message.parts() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (UiMessageRequest.Part part : message.parts()) {
            if ("text".equals(part.type()) && part.text() != null) {
                text.append(part.text());
            }
        }
        return text.toString();
    }

    /** Serializes a parsed input/output payload back to a JSON string for a Spring AI tool call/response. */
    private static String toJson(Object value) {
        if (value == null) {
            return "{}";
        }
        if (value instanceof String string) {
            return string;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
