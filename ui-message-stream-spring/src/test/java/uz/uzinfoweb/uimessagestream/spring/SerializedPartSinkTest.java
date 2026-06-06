package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.uzinfoweb.uimessagestream.core.UiMessagePart;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SerializedPartSink")
class SerializedPartSinkTest {

    @Test
    @DisplayName("drops writes until a writer is bound, then drives the bound writer")
    void dropsUntilBound() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();

        sink.text("before-bind"); // no writer yet -> dropped
        sink.bind(new UiMessageStreamWriter(parts::add));
        sink.text("after-bind");

        assertThat(parts)
                .noneMatch(p -> p instanceof UiMessagePart.TextDelta d && d.delta().equals("before-bind"));
        assertThat(parts)
                .anyMatch(p -> p instanceof UiMessagePart.TextDelta d && d.delta().equals("after-bind"));
    }

    @Test
    @DisplayName("concurrent text + data writes never put a data part inside an open text block (invariant 1)")
    void concurrentWritesPreserveInvariantOne() throws InterruptedException {
        int n = 2_000;
        List<UiMessagePart> parts = Collections.synchronizedList(new ArrayList<>());
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));

        CountDownLatch start = new CountDownLatch(1);
        Thread textThread = new Thread(() -> {
            awaitQuietly(start);
            for (int i = 0; i < n; i++) {
                sink.text("t");
            }
        });
        Thread dataThread = new Thread(() -> {
            awaitQuietly(start);
            for (int i = 0; i < n; i++) {
                sink.data("d", Map.of("i", i));
            }
        });

        textThread.start();
        dataThread.start();
        start.countDown();
        textThread.join(TimeUnit.SECONDS.toMillis(30));
        dataThread.join(TimeUnit.SECONDS.toMillis(30));

        List<UiMessagePart> snapshot = new ArrayList<>(parts);
        boolean textOpen = false;
        int textDeltas = 0;
        int dataParts = 0;
        for (UiMessagePart part : snapshot) {
            String type = part.type();
            switch (type) {
                case "text-start" -> {
                    assertThat(textOpen).as("no nested text-start").isFalse();
                    textOpen = true;
                }
                case "text-delta" -> {
                    assertThat(textOpen).as("text-delta only inside an open block").isTrue();
                    textDeltas++;
                }
                case "text-end" -> {
                    assertThat(textOpen).as("text-end only closes an open block").isTrue();
                    textOpen = false;
                }
                default -> {
                    if (type.startsWith("data-")) {
                        assertThat(textOpen).as("a data part must never appear inside an open text block").isFalse();
                        dataParts++;
                    }
                }
            }
        }

        assertThat(textDeltas).isEqualTo(n);
        assertThat(dataParts).isEqualTo(n);
    }

    @Test
    @DisplayName("the new tool/approval/metadata methods delegate to the bound writer")
    void newMethodsDelegate() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));

        sink.toolOutputError("call_1", "boom");
        sink.toolApprovalRequest("appr_1", "call_1");
        sink.toolOutputDenied("call_1");
        sink.messageMetadata(Map.of("k", "v"));

        assertThat(parts.stream().map(UiMessagePart::type).toList())
                .containsExactly("tool-output-error", "tool-approval-request", "tool-output-denied",
                        "message-metadata");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
