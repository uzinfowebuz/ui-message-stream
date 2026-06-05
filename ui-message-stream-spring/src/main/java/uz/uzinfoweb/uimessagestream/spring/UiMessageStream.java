package uz.uzinfoweb.uimessagestream.spring;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import uz.uzinfoweb.uimessagestream.core.PartSerializer;
import uz.uzinfoweb.uimessagestream.core.UiMessagePart;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Reactive bridge from a producer to a {@code Flux<ServerSentEvent<String>>} of UI Message Stream
 * frames, terminated by the {@code [DONE]} sentinel.
 *
 * <p>Owns <b>invariant&nbsp;2 — reactive cancellation</b>: the bridge subscribes to the upstream
 * inside a {@link Flux#create} sink and disposes that subscription whenever the downstream
 * subscriber cancels (client disconnect) or the output terminates. There is no blocking iteration,
 * so a disconnect actually cancels the underlying model call.
 */
public final class UiMessageStream {

    private final PartSerializer serializer;

    /** Uses a default {@link PartSerializer}. */
    public UiMessageStream() {
        this(new PartSerializer());
    }

    public UiMessageStream(PartSerializer serializer) {
        this.serializer = serializer;
    }

    /** Bridges a Spring AI response stream using {@link ChatClientResponseMapper#DEFAULT}. */
    public Flux<ServerSentEvent<String>> from(Flux<ChatClientResponse> upstream) {
        return from(upstream, ChatClientResponseMapper.DEFAULT);
    }

    /**
     * Bridges a Spring AI response stream:
     * emits {@code start}/{@code start-step}, runs {@code mapper} per upstream element, emits
     * {@code finish-step}/{@code finish} on completion (or an {@code error} part on failure), and
     * appends {@code [DONE]}. Cancellation disposes the upstream subscription.
     */
    public Flux<ServerSentEvent<String>> from(Flux<ChatClientResponse> upstream, ResponseMapper mapper) {
        return from(upstream, mapper, new SerializedPartSink());
    }

    /**
     * Same as {@link #from(Flux, ResponseMapper)} but driving the supplied {@code partSink}, so native
     * tool I/O and custom {@code data-*} parts pushed from tools (on a different Reactor thread) are
     * serialized onto the same writer as the text deltas. Publish the same sink in the prompt's tool
     * context under {@link RecordingToolCallingManager#SINK_KEY}.
     */
    public Flux<ServerSentEvent<String>> from(Flux<ChatClientResponse> upstream, ResponseMapper mapper,
                                              SerializedPartSink partSink) {
        String messageId = newMessageId();
        Flux<UiMessagePart> parts = Flux.create(sink -> {
            UiMessageStreamWriter writer = new UiMessageStreamWriter(sink::next);
            partSink.bind(writer);
            partSink.run(w -> w.start(messageId));
            Disposable subscription = upstream.subscribe(
                    response -> {
                        try {
                            partSink.run(w -> mapper.accept(response, w));
                        } catch (RuntimeException e) {
                            partSink.run(w -> w.error(messageOf(e)));
                            sink.complete();
                        }
                    },
                    error -> {
                        partSink.run(w -> w.error(messageOf(error)));
                        sink.complete();
                    },
                    () -> {
                        partSink.run(UiMessageStreamWriter::finish);
                        sink.complete();
                    });
            // Dispose the model call on cancel/terminate (invariant 2).
            sink.onDispose(subscription);
        }, FluxSink.OverflowStrategy.BUFFER);

        return toServerSentEvents(parts);
    }

    /**
     * Imperative escape hatch: drive the writer directly. The producer should call
     * {@link UiMessageStreamWriter#start(String)} and emit parts; a defensive {@code finish()} is
     * invoked afterwards (it is idempotent) so any open text/reasoning block is always closed.
     * A thrown {@link RuntimeException} is converted into an {@code error} part. {@code [DONE]} is
     * always appended.
     */
    public Flux<ServerSentEvent<String>> create(Consumer<UiMessageStreamWriter> producer) {
        Flux<UiMessagePart> parts = Flux.create(sink -> {
            UiMessageStreamWriter writer = new UiMessageStreamWriter(sink::next);
            try {
                producer.accept(writer);
                writer.finish();
            } catch (RuntimeException e) {
                writer.error(messageOf(e));
            }
            sink.complete();
        }, FluxSink.OverflowStrategy.BUFFER);

        return toServerSentEvents(parts);
    }

    private Flux<ServerSentEvent<String>> toServerSentEvents(Flux<UiMessagePart> parts) {
        return parts.map(part -> event(serializer.serialize(part)))
                .concatWith(Mono.fromSupplier(() -> event(PartSerializer.DONE)));
    }

    private static ServerSentEvent<String> event(String data) {
        return ServerSentEvent.<String>builder(data).build();
    }

    private static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message != null ? message : error.getClass().getSimpleName();
    }
}
