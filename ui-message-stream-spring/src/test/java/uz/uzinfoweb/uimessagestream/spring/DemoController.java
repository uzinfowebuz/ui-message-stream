package uz.uzinfoweb.uimessagestream.spring;

import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Sample controller used only by {@link DemoControllerWebTest}. It lives in the test source set, so
 * it is never part of the published jars. It shows the imperative {@code create(...)} path.
 */
@RestController
public class DemoController {

    private final UiMessageStream stream = new UiMessageStream();

    /** Text-only stream. */
    @GetMapping("/demo/text")
    public ResponseEntity<Flux<ServerSentEvent<String>>> text() {
        return UiMessageStreamResponse.of(stream.create(writer -> {
            writer.start("demo-1");
            writer.text("Hello");
        }));
    }

    /** Text, then a {@code data-artifact} part, then more text — exercises the no-merge invariant. */
    @GetMapping("/demo/data")
    public ResponseEntity<Flux<ServerSentEvent<String>>> data() {
        return UiMessageStreamResponse.of(stream.create(writer -> {
            writer.start("demo-2");
            writer.text("A");
            writer.data("artifact", Map.of("ok", true));
            writer.text("B");
        }));
    }
}
