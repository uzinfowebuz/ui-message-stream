package uz.uzinfoweb.uimessagestream.spring;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts an inbound {@link UiMessageRequest} into Spring AI {@link Message}s.
 *
 * <p>Text parts are concatenated and mapped to {@link UserMessage}/{@link AssistantMessage}/
 * {@link SystemMessage} by role (defaulting to user). {@code file} parts on a user message are turned
 * into {@link Media} via a {@link MediaResolver} (the {@linkplain MediaResolver#DEFAULT URL-based
 * default} unless you pass your own) and attached to the {@link UserMessage}; a part the resolver
 * cannot handle is skipped without failing the request.
 */
public final class UiMessageRequestAdapter {

    private UiMessageRequestAdapter() {
    }

    /** Converts using the {@linkplain MediaResolver#DEFAULT default URL-based} media resolver. */
    public static List<Message> toSpringAiMessages(UiMessageRequest request) {
        return toSpringAiMessages(request, MediaResolver.DEFAULT);
    }

    /** Converts using the supplied {@code mediaResolver} for inbound {@code file} parts. */
    public static List<Message> toSpringAiMessages(UiMessageRequest request, MediaResolver mediaResolver) {
        Objects.requireNonNull(mediaResolver, "mediaResolver");
        List<Message> messages = new ArrayList<>();
        if (request == null || request.messages() == null) {
            return messages;
        }
        for (UiMessageRequest.Message uiMessage : request.messages()) {
            String text = concatenateText(uiMessage);
            messages.add(switch (normalizedRole(uiMessage)) {
                case "assistant" -> new AssistantMessage(text);
                case "system" -> new SystemMessage(text);
                default -> toUserMessage(text, uiMessage, mediaResolver);
            });
        }
        return messages;
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
}
