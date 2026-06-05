package uz.uzinfoweb.uimessagestream.spring;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import uz.uzinfoweb.uimessagestream.core.PartSerializer;

/**
 * Wraps a frame {@link Flux} in a {@link ResponseEntity} carrying the two — and only two — headers
 * the protocol requires: {@code Content-Type: text/event-stream} and
 * {@code x-vercel-ai-ui-message-stream: v1}.
 */
public final class UiMessageStreamResponse {

    private UiMessageStreamResponse() {
    }

    public static ResponseEntity<Flux<ServerSentEvent<String>>> of(Flux<ServerSentEvent<String>> body) {
        return ResponseEntity.ok()
                .header(PartSerializer.STREAM_HEADER_NAME, PartSerializer.STREAM_HEADER_VALUE)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }
}
