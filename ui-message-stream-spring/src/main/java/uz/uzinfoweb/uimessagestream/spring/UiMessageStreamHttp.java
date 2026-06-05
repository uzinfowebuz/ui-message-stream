package uz.uzinfoweb.uimessagestream.spring;

import jakarta.servlet.http.HttpServletResponse;
import uz.uzinfoweb.uimessagestream.core.PartSerializer;

import java.nio.charset.StandardCharsets;

/**
 * Servlet counterpart of {@link UiMessageStreamResponse}: applies the two — and only two — headers
 * the protocol requires onto a {@link HttpServletResponse} before an {@code SseEmitter} starts
 * streaming.
 *
 * <p>Call this in the controller method, before returning the emitter:
 * <pre>{@code
 * @GetMapping("/chat")
 * SseEmitter chat(HttpServletResponse response) {
 *     UiMessageStreamHttp.applyHeaders(response);
 *     return new UiMessageStreamEmitter().from(upstream, mapper, executor);
 * }
 * }</pre>
 */
public final class UiMessageStreamHttp {

    private UiMessageStreamHttp() {
    }

    /**
     * Sets {@code Content-Type: text/event-stream} (UTF-8) and
     * {@code x-vercel-ai-ui-message-stream: v1} on the response.
     */
    public static void applyHeaders(HttpServletResponse response) {
        response.setContentType(PartSerializer.CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(PartSerializer.STREAM_HEADER_NAME, PartSerializer.STREAM_HEADER_VALUE);
    }
}
