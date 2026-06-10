package uz.uzinfoweb.uimessagestream.spring;

import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import uz.uzinfoweb.uimessagestream.core.PartSerializer;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Servlet/MVC bridge from a producer to a Spring MVC {@link SseEmitter} of UI Message Stream frames,
 * terminated by the {@code [DONE]} sentinel. It is the servlet-stack counterpart of the reactive
 * {@link UiMessageStream} and drives the very same framework-free {@link UiMessageStreamWriter} +
 * {@link PartSerializer}, so the frames it produces are byte-for-byte identical to the reactive
 * bridge.
 *
 * <p>Unlike the reactive bridge, the iteration is <b>blocking</b> and runs on the caller's thread:
 * {@link #writeTo(SseEmitter, Flux, ResponseMapper)} consumes {@code upstream} via
 * {@link Flux#toIterable()}. Callers are expected to already be on their own worker thread (a
 * dedicated chat executor, a virtual thread, ...); the {@link #from(Flux, ResponseMapper, Executor)}
 * convenience submits the work for you.
 *
 * <p><b>Native tool I/O.</b> Every write goes through a {@link SerializedPartSink}. By default an
 * internal one is used. Pass your own (the {@code writeTo}/{@code from} overloads that take a
 * {@link SerializedPartSink}) and publish it in the prompt's tool context under
 * {@link RecordingToolCallingManager#SINK_KEY}: a {@link RecordingToolCallingManager} (or an
 * application {@code @Tool}) can then push {@code tool-*}/{@code data-*} parts from a different thread,
 * serialized onto the same writer as the text deltas.
 *
 * <p><b>Cancellation (invariant&nbsp;2) is best-effort here.</b> A servlet {@code SseEmitter} cannot
 * dispose an upstream subscription the way the reactive bridge does; instead this transport listens
 * for {@code onError}/{@code onTimeout}/{@code onCompletion} (client disconnect) and stops iterating
 * at the next loop boundary, and a failing {@link SseEmitter#send} also halts the loop. The
 * underlying model call is only interrupted once the blocking iteration notices.
 */
public final class UiMessageStreamEmitter {

    private final PartSerializer serializer;
    private final ErrorMessageResolver errorMessages;

    /** Uses a default {@link PartSerializer} and the masking {@link ErrorMessageResolver#MASKED}. */
    public UiMessageStreamEmitter() {
        this(new PartSerializer());
    }

    /** Uses the masking {@link ErrorMessageResolver#MASKED} — failures stream as a generic message. */
    public UiMessageStreamEmitter(PartSerializer serializer) {
        this(serializer, ErrorMessageResolver.MASKED);
    }

    /**
     * @param errorMessages maps a failure to the {@code errorText} streamed to the client; the
     *                      default {@link ErrorMessageResolver#MASKED} never discloses internals
     */
    public UiMessageStreamEmitter(PartSerializer serializer, ErrorMessageResolver errorMessages) {
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.errorMessages = Objects.requireNonNull(errorMessages, "errorMessages");
    }

    /** Bridges a Spring AI response stream onto {@code emitter} using {@link ChatClientResponseMapper#DEFAULT}. */
    public void writeTo(SseEmitter emitter, Flux<ChatClientResponse> upstream) {
        writeTo(emitter, upstream, ChatClientResponseMapper.DEFAULT);
    }

    /**
     * Bridges a Spring AI response stream onto {@code emitter}: emits {@code start}/{@code start-step},
     * runs {@code mapper} per upstream element, emits {@code finish-step}/{@code finish} on completion
     * (or an {@code error} part on failure), appends {@code [DONE]} and completes the emitter. Blocks
     * the calling thread for the lifetime of the stream.
     */
    public void writeTo(SseEmitter emitter, Flux<ChatClientResponse> upstream, ResponseMapper mapper) {
        writeTo(emitter, upstream, mapper, new SerializedPartSink(), null, null);
    }

    /**
     * Same as {@link #writeTo(SseEmitter, Flux, ResponseMapper)} but driving the supplied
     * {@code sink} (so native tool I/O and custom {@code data-*} parts pushed from tools land on the
     * same writer as text). Publish the same sink in the prompt's tool context under
     * {@link RecordingToolCallingManager#SINK_KEY}.
     */
    public void writeTo(SseEmitter emitter, Flux<ChatClientResponse> upstream, ResponseMapper mapper,
                        SerializedPartSink sink) {
        writeTo(emitter, upstream, mapper, sink, null, null);
    }

    /**
     * Same as {@link #writeTo(SseEmitter, Flux, ResponseMapper)} with two generic hooks (internal sink).
     *
     * @param onElement  invoked for each upstream element before it is mapped (a per-element tap, e.g.
     *                   to capture state carried in the response context); may be {@code null}
     * @param onComplete invoked exactly once after the stream's last protocol frame but before
     *                   {@code [DONE]} is sent, with {@code null} on success or the failure that ended
     *                   the stream; a {@link RuntimeException} thrown here is swallowed. May be {@code null}.
     */
    public void writeTo(SseEmitter emitter, Flux<ChatClientResponse> upstream, ResponseMapper mapper,
                        Consumer<ChatClientResponse> onElement, Consumer<Throwable> onComplete) {
        writeTo(emitter, upstream, mapper, new SerializedPartSink(), onElement, onComplete);
    }

    /**
     * Full form: bridge {@code upstream} onto {@code emitter} driving {@code sink}'s writer, with a
     * per-element tap and a terminal callback. All text mapping runs under the sink's exclusive lock,
     * so tool/data parts pushed concurrently (e.g. by {@link RecordingToolCallingManager}) interleave
     * safely and never corrupt the text-block lifecycle.
     */
    public void writeTo(SseEmitter emitter, Flux<ChatClientResponse> upstream, ResponseMapper mapper,
                        SerializedPartSink sink, Consumer<ChatClientResponse> onElement,
                        Consumer<Throwable> onComplete) {
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(sink, "sink");

        AtomicBoolean cancelled = registerLifecycle(emitter);
        sink.bind(newWriter(emitter));

        String messageId = newMessageId();
        Throwable failure = null;
        try {
            sink.run(w -> w.start(messageId));
            for (ChatClientResponse response : upstream.toIterable()) {
                if (cancelled.get()) {
                    break;
                }
                if (onElement != null) {
                    onElement.accept(response);
                }
                sink.run(w -> mapper.accept(response, w));
            }
            sink.run(UiMessageStreamWriter::finish);
        } catch (EmitterSendException e) {
            // Client disconnected (or the emitter was closed) mid-send: stop quietly, no error frame.
            failure = e.getCause();
        } catch (RuntimeException e) {
            failure = e;
            safelyEmitError(sink, e);
        }

        runTerminalHook(onComplete, failure);
        terminate(emitter);
    }

    /**
     * Imperative escape hatch mirroring {@link UiMessageStream#create(Consumer)}: drive the writer
     * directly. The producer should call {@link UiMessageStreamWriter#start(String)} and emit parts; a
     * defensive {@code finish()} is invoked afterwards (it is idempotent). A thrown
     * {@link RuntimeException} becomes an {@code error} part. {@code [DONE]} is always appended and the
     * emitter completed.
     */
    public void writeTo(SseEmitter emitter, Consumer<UiMessageStreamWriter> producer) {
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(producer, "producer");

        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(newWriter(emitter));
        try {
            sink.run(w -> {
                producer.accept(w);
                w.finish();
            });
        } catch (EmitterSendException ignored) {
            // Client disconnected mid-send.
        } catch (RuntimeException e) {
            safelyEmitError(sink, e);
        }
        terminate(emitter);
    }

    /**
     * Convenience: creates a no-timeout {@link SseEmitter}, submits
     * {@link #writeTo(SseEmitter, Flux, ResponseMapper)} to {@code executor}, and returns the emitter
     * immediately so it can be returned from a controller method. Headers must still be applied via
     * {@link UiMessageStreamHttp#applyHeaders}.
     */
    public SseEmitter from(Flux<ChatClientResponse> upstream, ResponseMapper mapper, Executor executor) {
        return from(upstream, mapper, new SerializedPartSink(), executor);
    }

    /** Same as {@link #from(Flux, ResponseMapper, Executor)} but driving the supplied {@code sink}. */
    public SseEmitter from(Flux<ChatClientResponse> upstream, ResponseMapper mapper, SerializedPartSink sink,
                           Executor executor) {
        Objects.requireNonNull(executor, "executor");
        SseEmitter emitter = new SseEmitter(0L); // 0 == no async timeout (per the Servlet spec)
        executor.execute(() -> writeTo(emitter, upstream, mapper, sink, null, null));
        return emitter;
    }

    private UiMessageStreamWriter newWriter(SseEmitter emitter) {
        return new UiMessageStreamWriter(part -> send(emitter, serializer.serialize(part)));
    }

    private static AtomicBoolean registerLifecycle(SseEmitter emitter) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onError(t -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onCompletion(() -> cancelled.set(true));
        return cancelled;
    }

    private static void runTerminalHook(Consumer<Throwable> onComplete, Throwable failure) {
        if (onComplete == null) {
            return;
        }
        try {
            onComplete.accept(failure);
        } catch (RuntimeException ignored) {
            // A terminal-hook failure must never prevent the stream from terminating.
        }
    }

    private void terminate(SseEmitter emitter) {
        try {
            send(emitter, PartSerializer.DONE);
        } catch (EmitterSendException ignored) {
            // The client is already gone; nothing left to deliver.
        }
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // Already completed or closed.
        }
    }

    private void safelyEmitError(SerializedPartSink sink, RuntimeException cause) {
        try {
            sink.run(w -> w.error(errorMessages.resolve(cause)));
        } catch (EmitterSendException ignored) {
            // Could not deliver the error frame (client gone) — nothing else to do.
        }
    }

    private static void send(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data));
        } catch (IOException | IllegalStateException e) {
            throw new EmitterSendException(e);
        }
    }

    private static String newMessageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Signals that an {@link SseEmitter#send} failed (client disconnect or an already-closed emitter). */
    private static final class EmitterSendException extends RuntimeException {
        EmitterSendException(Throwable cause) {
            super(cause);
        }
    }
}
