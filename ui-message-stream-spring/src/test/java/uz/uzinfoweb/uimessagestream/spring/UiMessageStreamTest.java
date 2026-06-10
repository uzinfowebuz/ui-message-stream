package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import uz.uzinfoweb.uimessagestream.core.PartSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UiMessageStream")
class UiMessageStreamTest {

    private final UiMessageStream stream = new UiMessageStream();

    /** A minimal real ChatClientResponse carrying one text delta. No model is involved. */
    private static ChatClientResponse chunk(String text) {
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        return new ChatClientResponse(chatResponse, Map.of());
    }

    private static String data(ServerSentEvent<String> event) {
        return event.data();
    }

    @Test
    @DisplayName("from() emits start/start-step .. finish/[DONE] and never merges text across a data part")
    void fullFrameSequence() {
        // Custom mapper: a sentinel chunk becomes a data part, everything else is text.
        ResponseMapper mapper = (response, writer) -> {
            String text = response.chatResponse().getResults().get(0).getOutput().getText();
            if ("<<ARTIFACT>>".equals(text)) {
                writer.data("artifact", Map.of("ok", true));
            } else if (text != null && !text.isEmpty()) {
                writer.text(text);
            }
        };

        Flux<ChatClientResponse> upstream = Flux.just(chunk("A"), chunk("<<ARTIFACT>>"), chunk("B"));

        StepVerifier.create(stream.from(upstream, mapper).map(UiMessageStreamTest::data))
                .expectNextMatches(s -> s.startsWith("{\"type\":\"start\",\"messageId\":\"msg_"))
                .expectNext("{\"type\":\"start-step\"}")
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-start\""))
                .expectNextMatches(s -> s.contains("\"type\":\"text-delta\"") && s.endsWith("\"delta\":\"A\"}"))
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-end\""))
                .expectNext("{\"type\":\"data-artifact\",\"data\":{\"ok\":true}}")
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-start\""))
                .expectNextMatches(s -> s.contains("\"type\":\"text-delta\"") && s.endsWith("\"delta\":\"B\"}"))
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-end\""))
                .expectNext("{\"type\":\"finish-step\"}")
                .expectNext("{\"type\":\"finish\"}")
                .expectNext("[DONE]")
                .verifyComplete();
    }

    @Test
    @DisplayName("default mapper streams consecutive text deltas under one block")
    void defaultMapperStreamsText() {
        Flux<ChatClientResponse> upstream = Flux.just(chunk("Hello "), chunk("world"));

        StepVerifier.create(stream.from(upstream).map(UiMessageStreamTest::data))
                .expectNextMatches(s -> s.contains("\"type\":\"start\""))
                .expectNext("{\"type\":\"start-step\"}")
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-start\""))
                .expectNextMatches(s -> s.endsWith("\"delta\":\"Hello \"}"))
                .expectNextMatches(s -> s.endsWith("\"delta\":\"world\"}"))
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-end\""))
                .expectNext("{\"type\":\"finish-step\"}")
                .expectNext("{\"type\":\"finish\"}")
                .expectNext("[DONE]")
                .verifyComplete();
    }

    @Test
    @DisplayName("an upstream error becomes a masked error part (no internals), then the stream still ends with [DONE]")
    void upstreamErrorBecomesErrorPart() {
        Flux<ChatClientResponse> upstream =
                Flux.concat(Flux.just(chunk("hi")), Flux.error(new RuntimeException("jdbc://user:secret@db")));

        StepVerifier.create(stream.from(upstream).map(UiMessageStreamTest::data))
                .expectNextMatches(s -> s.contains("\"type\":\"start\""))
                .expectNext("{\"type\":\"start-step\"}")
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-start\""))
                .expectNextMatches(s -> s.endsWith("\"delta\":\"hi\"}"))
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-end\""))
                .expectNext("{\"type\":\"error\",\"errorText\":\"An error occurred.\"}")
                .expectNext("[DONE]")
                .verifyComplete();
    }

    @Test
    @DisplayName("ErrorMessageResolver.MESSAGE opts back into raw exception-message disclosure")
    void errorMessageDisclosureIsOptIn() {
        UiMessageStream disclosing = new UiMessageStream(new PartSerializer(), ErrorMessageResolver.MESSAGE);
        Flux<ChatClientResponse> upstream = Flux.error(new RuntimeException("boom"));

        StepVerifier.create(disclosing.from(upstream).map(UiMessageStreamTest::data))
                .expectNextMatches(s -> s.contains("\"type\":\"start\""))
                .expectNext("{\"type\":\"start-step\"}")
                .expectNext("{\"type\":\"error\",\"errorText\":\"boom\"}")
                .expectNext("[DONE]")
                .verifyComplete();
    }

    @Test
    @DisplayName("cancellation disposes the upstream subscription (invariant 2)")
    void cancellationDisposesUpstream() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Flux<ChatClientResponse> upstream =
                Flux.concat(Flux.just(chunk("Hello")), Flux.<ChatClientResponse>never())
                        .doOnCancel(() -> cancelled.set(true));

        StepVerifier.create(stream.from(upstream).map(UiMessageStreamTest::data))
                .expectNextMatches(s -> s.contains("\"type\":\"start\""))
                .expectNext("{\"type\":\"start-step\"}")
                .expectNextMatches(s -> s.startsWith("{\"type\":\"text-start\""))
                .expectNextMatches(s -> s.endsWith("\"delta\":\"Hello\"}"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(cancelled).isTrue();
    }
}
