package uz.uzinfoweb.uimessagestream.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UiMessageStreamWriter")
class UiMessageStreamWriterTest {

    private final List<UiMessagePart> parts = new ArrayList<>();
    private final PartSerializer serializer = new PartSerializer();

    /** Block ids are deterministic ("t1", "t2", ...) so we can assert exact JSON. */
    private UiMessageStreamWriter newWriter() {
        AtomicInteger counter = new AtomicInteger();
        return new UiMessageStreamWriter(parts::add, () -> "t" + counter.incrementAndGet());
    }

    private List<String> types() {
        return parts.stream().map(UiMessagePart::type).toList();
    }

    @Test
    @DisplayName("text -> data -> text never merges into one text block (the core invariant)")
    void textDataTextDoesNotMerge() {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("id", "a1");
        artifact.put("kind", "quiz");

        UiMessageStreamWriter writer = newWriter();
        writer.start("msg-1");
        writer.text("A");
        writer.data("artifact", artifact);
        writer.text("B");
        writer.finish();

        assertThat(types()).containsExactly(
                "start", "start-step",
                "text-start", "text-delta", "text-end",
                "data-artifact",
                "text-start", "text-delta", "text-end",
                "finish-step", "finish");

        // The mandated sub-sequence, asserted as exact JSON strings (note the two DISTINCT ids).
        List<String> mandated = parts.subList(2, 9).stream().map(serializer::serialize).toList();
        assertThat(mandated).containsExactly(
                "{\"type\":\"text-start\",\"id\":\"t1\"}",
                "{\"type\":\"text-delta\",\"id\":\"t1\",\"delta\":\"A\"}",
                "{\"type\":\"text-end\",\"id\":\"t1\"}",
                "{\"type\":\"data-artifact\",\"data\":{\"id\":\"a1\",\"kind\":\"quiz\"}}",
                "{\"type\":\"text-start\",\"id\":\"t2\"}",
                "{\"type\":\"text-delta\",\"id\":\"t2\",\"delta\":\"B\"}",
                "{\"type\":\"text-end\",\"id\":\"t2\"}");
    }

    @Test
    @DisplayName("consecutive text deltas share one open block / one id")
    void consecutiveTextDeltasMergeUnderOneId() {
        UiMessageStreamWriter writer = newWriter();
        writer.text("a");
        writer.text("b");
        writer.finish();

        assertThat(types()).containsExactly(
                "text-start", "text-delta", "text-delta", "text-end", "finish-step", "finish");

        assertThat(parts.get(1)).isInstanceOf(UiMessagePart.TextDelta.class);
        assertThat(parts.get(2)).isInstanceOf(UiMessagePart.TextDelta.class);
        String firstId = ((UiMessagePart.TextDelta) parts.get(1)).id();
        String secondId = ((UiMessagePart.TextDelta) parts.get(2)).id();
        assertThat(firstId).isEqualTo("t1").isEqualTo(secondId);
    }

    @Test
    @DisplayName("switching from reasoning to text closes the reasoning block first")
    void reasoningThenTextClosesReasoning() {
        UiMessageStreamWriter writer = newWriter();
        writer.reasoning("thinking");
        writer.text("answer");
        writer.finish();

        assertThat(types()).containsExactly(
                "reasoning-start", "reasoning-delta", "reasoning-end",
                "text-start", "text-delta", "text-end",
                "finish-step", "finish");
    }

    @Test
    @DisplayName("native tool parts close the open text block before the tool part")
    void toolPartsCloseOpenTextBlock() {
        UiMessageStreamWriter writer = newWriter();
        writer.text("calling a tool");
        writer.toolInputAvailable("call-1", "getWeather", Map.of("city", "NYC"));
        writer.toolOutputAvailable("call-1", Map.of("tempC", 21));
        writer.finish();

        assertThat(types()).containsExactly(
                "text-start", "text-delta", "text-end",
                "tool-input-available", "tool-output-available",
                "finish-step", "finish");
    }

    @Test
    @DisplayName("finish is idempotent and abort suppresses a later finish")
    void terminationIsIdempotent() {
        UiMessageStreamWriter writer = newWriter();
        writer.text("hi");
        writer.finish();
        writer.finish(); // no-op

        assertThat(types()).containsExactly(
                "text-start", "text-delta", "text-end", "finish-step", "finish");

        parts.clear();
        UiMessageStreamWriter aborting = newWriter();
        aborting.text("hi");
        aborting.abort(); // v6 abort carries no fields
        aborting.finish(); // suppressed after abort

        assertThat(types()).containsExactly("text-start", "text-delta", "text-end", "abort");
        assertThat(serializer.serialize(parts.get(3))).isEqualTo("{\"type\":\"abort\"}");
    }

    @Test
    @DisplayName("abort(reason) emits the optional reason and still suppresses a later finish")
    void abortWithReason() {
        UiMessageStreamWriter writer = newWriter();
        writer.text("hi");
        writer.abort("user cancelled");
        writer.finish(); // suppressed after abort

        assertThat(types()).containsExactly("text-start", "text-delta", "text-end", "abort");
        assertThat(serializer.serialize(parts.get(3)))
                .isEqualTo("{\"type\":\"abort\",\"reason\":\"user cancelled\"}");
    }

    @Test
    @DisplayName("new tool/approval parts close the open text block; message-metadata does not")
    void newPartsLifecycle() {
        UiMessageStreamWriter writer = newWriter();
        writer.text("working");
        writer.messageMetadata(Map.of("model", "gemini")); // must NOT close the text block
        writer.text("more");
        writer.toolOutputError("call-1", "boom");          // must close the text block
        writer.toolApprovalRequest("appr-1", "call-1");
        writer.toolOutputDenied("call-1");
        writer.finish();

        assertThat(types()).containsExactly(
                "text-start", "text-delta", "message-metadata", "text-delta", "text-end",
                "tool-output-error", "tool-approval-request", "tool-output-denied",
                "finish-step", "finish");

        // The two deltas straddling message-metadata share one id (the block never closed).
        assertThat(((UiMessagePart.TextDelta) parts.get(1)).id())
                .isEqualTo(((UiMessagePart.TextDelta) parts.get(3)).id());
    }

    @Test
    @DisplayName("part() rejects text/reasoning lifecycle parts so the writer keeps id ownership")
    void partRejectsTextLifecycle() {
        UiMessageStreamWriter writer = newWriter();
        assertThatThrownBy(() -> writer.part(new UiMessagePart.TextDelta("x", "y")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.part(new UiMessagePart.ReasoningStart("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("finish(reason, metadata) carries the optional fields")
    void finishWithReasonAndMetadata() {
        UiMessageStreamWriter writer = newWriter();
        writer.text("done");
        writer.finish("stop", Map.of("tokens", 7));

        assertThat(serializer.serialize(parts.getLast()))
                .isEqualTo("{\"type\":\"finish\",\"finishReason\":\"stop\",\"messageMetadata\":{\"tokens\":7}}");
    }
}
