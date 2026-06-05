package uz.uzinfoweb.uimessagestream.spring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
 * so that {@code data-*} and future part kinds do not break deserialization. Convert to Spring AI
 * messages with {@link UiMessageRequestAdapter}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UiMessageRequest(List<Message> messages) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String role, List<Part> parts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Part(String type, String text, String url, String mediaType) {
    }
}
