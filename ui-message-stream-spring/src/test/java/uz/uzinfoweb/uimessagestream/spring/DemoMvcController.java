package uz.uzinfoweb.uimessagestream.spring;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Servlet/MVC sample controller used only by {@link DemoMvcControllerWebMvcTest}. It lives in the
 * test source set, so it is never part of the published jars. It mirrors the reactive
 * {@link DemoController} to prove the servlet transport produces identical frames.
 *
 * <p>All four endpoints drive the emitter on the request thread (via a same-thread executor or a
 * direct call), so every {@code send} is buffered before the controller returns — exactly what
 * MockMvc's async dispatch needs to assert the full body deterministically.
 */
@RestController
class DemoMvcController {

    private final UiMessageStreamEmitter stream = new UiMessageStreamEmitter();

    /** Text-only stream (imperative path). */
    @GetMapping("/demo-mvc/text")
    SseEmitter text(HttpServletResponse response) {
        UiMessageStreamHttp.applyHeaders(response);
        SseEmitter emitter = new SseEmitter(0L);
        stream.writeTo(emitter, writer -> {
            writer.start("demo-1");
            writer.text("Hello");
        });
        return emitter;
    }

    /** Text, then a {@code data-artifact} part, then more text — exercises the no-merge invariant. */
    @GetMapping("/demo-mvc/data")
    SseEmitter data(HttpServletResponse response) {
        UiMessageStreamHttp.applyHeaders(response);
        SseEmitter emitter = new SseEmitter(0L);
        stream.writeTo(emitter, writer -> {
            writer.start("demo-2");
            writer.text("A");
            writer.data("artifact", Map.of("ok", true));
            writer.text("B");
        });
        return emitter;
    }

    /** Default {@link ChatClientResponseMapper} over a synthetic Spring AI response stream. */
    @GetMapping("/demo-mvc/mapper")
    SseEmitter mapper(HttpServletResponse response) {
        UiMessageStreamHttp.applyHeaders(response);
        Flux<ChatClientResponse> upstream = Flux.just(chunk("Hello "), chunk("world"));
        return stream.from(upstream, ChatClientResponseMapper.DEFAULT, Runnable::run);
    }

    /**
     * Upstream error after a text delta: the open text block is closed, an {@code error} part is
     * emitted, then {@code [DONE]}. The error is delayed so the blocking iteration consumes the
     * preceding {@code "hi"} delta first (a zero-gap {@code onNext}/{@code onError} would be coalesced
     * by {@link Flux#toIterable()} and the buffered item dropped — the realistic case has a time gap).
     */
    @GetMapping("/demo-mvc/error")
    SseEmitter error(HttpServletResponse response) {
        UiMessageStreamHttp.applyHeaders(response);
        SseEmitter emitter = new SseEmitter(0L);
        Flux<ChatClientResponse> upstream = Flux.concat(
                Flux.just(chunk("hi")),
                Flux.<ChatClientResponse>error(new RuntimeException("boom"))
                        .delaySubscription(Duration.ofMillis(50)));
        stream.writeTo(emitter, upstream, ChatClientResponseMapper.DEFAULT);
        return emitter;
    }

    /** A minimal real ChatClientResponse carrying one text delta. No model is involved. */
    private static ChatClientResponse chunk(String text) {
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return new ChatClientResponse(chatResponse, Map.of());
    }
}
