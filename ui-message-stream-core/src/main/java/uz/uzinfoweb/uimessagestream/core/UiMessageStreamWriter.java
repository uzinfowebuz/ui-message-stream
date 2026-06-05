package uz.uzinfoweb.uimessagestream.core;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Stateful, framework-free producer of {@link UiMessagePart}s.
 *
 * <p>The writer owns <b>invariant&nbsp;1 — the text-block lifecycle</b>. Text and reasoning are
 * streamed as {@code *-start} / {@code *-delta} / {@code *-end} under a single, stable id that the
 * writer mints. Before any non-text/non-reasoning part is emitted (data, tool, source, file,
 * error, finish, ...), the currently open block is closed first; the next {@link #text} or
 * {@link #reasoning} call then opens a <em>fresh</em> block with a new id. A caller therefore
 * cannot accidentally merge text across an interruption, and a single id is never reused for a
 * whole turn.
 *
 * <p>Parts are pushed to the supplied {@code sink}. This class performs no I/O, no buffering and no
 * threading; a transport layer is responsible for delivery and for invoking the writer serially.
 */
public final class UiMessageStreamWriter {

    private enum BlockKind { TEXT, REASONING }

    private final Consumer<UiMessagePart> sink;
    private final Supplier<String> idGenerator;

    private BlockKind openKind;
    private String openId;
    private boolean terminated;

    /** Creates a writer that mints block ids with {@link UUID#randomUUID()}. */
    public UiMessageStreamWriter(Consumer<UiMessagePart> sink) {
        this(sink, () -> UUID.randomUUID().toString());
    }

    /**
     * Creates a writer with a custom block-id generator (useful for deterministic tests).
     *
     * @param sink        receives every emitted part, in order
     * @param idGenerator supplies a fresh id each time a text or reasoning block opens
     */
    public UiMessageStreamWriter(Consumer<UiMessagePart> sink, Supplier<String> idGenerator) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /** Emits {@code start} (with the given message id) followed by {@code start-step}. */
    public void start(String messageId) {
        emit(new UiMessagePart.Start(messageId));
        emit(new UiMessagePart.StartStep());
    }

    /** Appends a text delta, opening a fresh text block first if one is not already open. */
    public void text(String delta) {
        ensureOpen(BlockKind.TEXT);
        emit(new UiMessagePart.TextDelta(openId, delta));
    }

    /** Appends a reasoning delta, opening a fresh reasoning block first if one is not already open. */
    public void reasoning(String delta) {
        ensureOpen(BlockKind.REASONING);
        emit(new UiMessagePart.ReasoningDelta(openId, delta));
    }

    /** Emits a {@code data-<name>} part (no reconciliation id). Closes any open text/reasoning block first. */
    public void data(String name, Object payload) {
        data(name, null, payload);
    }

    /**
     * Emits a {@code data-<name>} part. Closes any open text/reasoning block first.
     *
     * @param id optional reconciliation id; reusing an id updates that part in place client-side
     */
    public void data(String name, String id, Object payload) {
        closeOpenBlock();
        emit(new UiMessagePart.DataPart(name, id, payload));
    }

    /** Emits {@code tool-input-start}. Closes any open text/reasoning block first. */
    public void toolInputStart(String toolCallId, String toolName) {
        closeOpenBlock();
        emit(new UiMessagePart.ToolInputStart(toolCallId, toolName));
    }

    /** Emits {@code tool-input-delta}. Closes any open text/reasoning block first. */
    public void toolInputDelta(String toolCallId, String inputTextDelta) {
        closeOpenBlock();
        emit(new UiMessagePart.ToolInputDelta(toolCallId, inputTextDelta));
    }

    /** Emits {@code tool-input-available}. Closes any open text/reasoning block first. */
    public void toolInputAvailable(String toolCallId, String toolName, Object input) {
        closeOpenBlock();
        emit(new UiMessagePart.ToolInputAvailable(toolCallId, toolName, input));
    }

    /** Emits {@code tool-output-available}. Closes any open text/reasoning block first. */
    public void toolOutputAvailable(String toolCallId, Object output) {
        closeOpenBlock();
        emit(new UiMessagePart.ToolOutputAvailable(toolCallId, output));
    }

    /** Emits {@code source-url}. Closes any open text/reasoning block first. */
    public void sourceUrl(String sourceId, String url) {
        closeOpenBlock();
        emit(new UiMessagePart.SourceUrl(sourceId, url));
    }

    /** Emits {@code source-document}. Closes any open text/reasoning block first. */
    public void sourceDocument(String sourceId, String mediaType, String title) {
        closeOpenBlock();
        emit(new UiMessagePart.SourceDocument(sourceId, mediaType, title));
    }

    /** Emits {@code file}. Closes any open text/reasoning block first. */
    public void file(String url, String mediaType) {
        closeOpenBlock();
        emit(new UiMessagePart.FilePart(url, mediaType));
    }

    /** Emits an {@code error} part. Closes any open text/reasoning block first. */
    public void error(String errorText) {
        closeOpenBlock();
        emit(new UiMessagePart.ErrorPart(errorText));
    }

    /**
     * Closes any open block, then emits {@code finish-step} and {@code finish}. Idempotent: calling
     * it again (or after {@link #abort}) does nothing, so transports can safely finish defensively.
     */
    public void finish() {
        if (terminated) {
            return;
        }
        closeOpenBlock();
        emit(new UiMessagePart.FinishStep());
        emit(new UiMessagePart.Finish());
        terminated = true;
    }

    /**
     * Closes any open block, then emits an {@code abort} part. Marks the stream terminated so a
     * later defensive {@link #finish} is a no-op.
     */
    public void abort(String reason) {
        if (terminated) {
            return;
        }
        closeOpenBlock();
        emit(new UiMessagePart.Abort(reason));
        terminated = true;
    }

    private void ensureOpen(BlockKind kind) {
        if (openKind == kind) {
            return;
        }
        closeOpenBlock();
        openKind = kind;
        openId = idGenerator.get();
        emit(kind == BlockKind.TEXT
                ? new UiMessagePart.TextStart(openId)
                : new UiMessagePart.ReasoningStart(openId));
    }

    private void closeOpenBlock() {
        if (openKind == null) {
            return;
        }
        emit(openKind == BlockKind.TEXT
                ? new UiMessagePart.TextEnd(openId)
                : new UiMessagePart.ReasoningEnd(openId));
        openKind = null;
        openId = null;
    }

    private void emit(UiMessagePart part) {
        sink.accept(part);
    }
}
