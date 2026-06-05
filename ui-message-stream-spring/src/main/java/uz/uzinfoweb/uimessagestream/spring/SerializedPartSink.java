package uz.uzinfoweb.uimessagestream.spring;

import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Thread-safe, per-request facade over a single {@link UiMessageStreamWriter}.
 *
 * <p>It exists to resolve the one real concern of native tool I/O: a transport streams text deltas on
 * one thread while Spring AI executes tools (and pushes {@code tool-input-available} /
 * {@code tool-output-available}, or an application pushes custom {@code data-*} parts) on a different
 * thread. The underlying {@link UiMessageStreamWriter} is stateful and must be invoked serially, so
 * every write here is guarded by a single lock. Because all parts — text and tool — flow through the
 * <em>same</em> writer, the writer's text-block lifecycle (invariant&nbsp;1) holds for free: the open
 * text block is always closed before a tool/data part and a fresh one opens for the model's post-tool
 * answer.
 *
 * <p><b>Lifecycle.</b> An application creates a sink, publishes it in the prompt's tool context under
 * {@link RecordingToolCallingManager#SINK_KEY}, then hands it to a transport. The transport calls
 * {@link #bind(UiMessageStreamWriter)} once at the start of the stream (before any tool can run).
 * Writes that somehow arrive before binding are dropped rather than corrupting ordering — in practice
 * binding always precedes tool execution, so this never happens.
 *
 * <p>This is also the public per-request sink applications use to emit custom {@code data-*} parts
 * from inside their {@code @Tool} methods in correct order relative to text.
 */
public final class SerializedPartSink {

    private final ReentrantLock lock = new ReentrantLock();
    private UiMessageStreamWriter writer;

    /**
     * Binds the writer that every subsequent write drives. Called once by a transport at the start of
     * the stream. Binding again replaces the target writer (e.g. a retried request reusing the sink).
     */
    public void bind(UiMessageStreamWriter writer) {
        Objects.requireNonNull(writer, "writer");
        lock.lock();
        try {
            this.writer = writer;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code action} against the bound writer while holding this sink's exclusive lock. If no
     * writer is bound yet the action is dropped (binding always precedes tool execution in practice).
     * This is the low-level escape hatch; the typed methods below are conveniences over it.
     */
    public void run(Consumer<UiMessageStreamWriter> action) {
        Objects.requireNonNull(action, "action");
        lock.lock();
        try {
            if (writer != null) {
                action.accept(writer);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Appends a text delta (opens a fresh text block first if needed). */
    public void text(String delta) {
        run(w -> w.text(delta));
    }

    /** Appends a reasoning delta. */
    public void reasoning(String delta) {
        run(w -> w.reasoning(delta));
    }

    /** Emits a {@code data-<name>} part, closing any open text/reasoning block first. */
    public void data(String name, Object payload) {
        run(w -> w.data(name, payload));
    }

    /** Emits a {@code data-<name>} part with a reconciliation {@code id}. */
    public void data(String name, String id, Object payload) {
        run(w -> w.data(name, id, payload));
    }

    /** Emits {@code tool-input-available}, closing any open text/reasoning block first. */
    public void toolInputAvailable(String toolCallId, String toolName, Object input) {
        run(w -> w.toolInputAvailable(toolCallId, toolName, input));
    }

    /** Emits {@code tool-output-available}, closing any open text/reasoning block first. */
    public void toolOutputAvailable(String toolCallId, Object output) {
        run(w -> w.toolOutputAvailable(toolCallId, output));
    }

    /** Emits {@code source-url}. */
    public void sourceUrl(String sourceId, String url) {
        run(w -> w.sourceUrl(sourceId, url));
    }

    /** Emits {@code file}. */
    public void file(String url, String mediaType) {
        run(w -> w.file(url, mediaType));
    }

    /** Emits an {@code error} part. */
    public void error(String errorText) {
        run(w -> w.error(errorText));
    }
}
