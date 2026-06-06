package uz.uzinfoweb.uimessagestream.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PartSerializer")
class PartSerializerTest {

    private final PartSerializer serializer = new PartSerializer();

    @Test
    @DisplayName("protocol constants match the spec")
    void protocolConstants() {
        assertThat(PartSerializer.DONE).isEqualTo("[DONE]");
        assertThat(PartSerializer.STREAM_HEADER_NAME).isEqualTo("x-vercel-ai-ui-message-stream");
        assertThat(PartSerializer.STREAM_HEADER_VALUE).isEqualTo("v1");
        assertThat(PartSerializer.CONTENT_TYPE).isEqualTo("text/event-stream");
    }

    @Test
    @DisplayName("type is rendered first and empty-body parts are just a type")
    void typeFirstAndEmptyBodies() {
        assertThat(serializer.serialize(new UiMessagePart.StartStep())).isEqualTo("{\"type\":\"start-step\"}");
        assertThat(serializer.serialize(new UiMessagePart.FinishStep())).isEqualTo("{\"type\":\"finish-step\"}");
        assertThat(serializer.serialize(new UiMessagePart.Finish())).isEqualTo("{\"type\":\"finish\"}");
        assertThat(serializer.serialize(new UiMessagePart.Start("m1")))
                .isEqualTo("{\"type\":\"start\",\"messageId\":\"m1\"}");
    }

    @Test
    @DisplayName("null fields are omitted entirely")
    void nullFieldsAreOmitted() {
        // title is null -> dropped
        assertThat(serializer.serialize(new UiMessagePart.SourceDocument("s1", "text/plain", null)))
                .isEqualTo("{\"type\":\"source-document\",\"sourceId\":\"s1\",\"mediaType\":\"text/plain\"}");

        // optional reconciliation id absent -> dropped
        assertThat(serializer.serialize(new UiMessagePart.DataPart("weather", null, Map.of("city", "NYC"))))
                .isEqualTo("{\"type\":\"data-weather\",\"data\":{\"city\":\"NYC\"}}");
    }

    @Test
    @DisplayName("data parts carry the optional reconciliation id when present")
    void dataPartWithReconciliationId() {
        assertThat(serializer.serialize(new UiMessagePart.DataPart("weather", "w1", Map.of("city", "NYC"))))
                .isEqualTo("{\"type\":\"data-weather\",\"id\":\"w1\",\"data\":{\"city\":\"NYC\"}}");
    }

    @Test
    @DisplayName("tool input is serialized as a nested JSON object, not a string")
    void toolInputAvailableNestsObject() {
        assertThat(serializer.serialize(
                new UiMessagePart.ToolInputAvailable("call-1", "getWeather", Map.of("city", "NYC"))))
                .isEqualTo("{\"type\":\"tool-input-available\",\"toolCallId\":\"call-1\","
                        + "\"toolName\":\"getWeather\",\"input\":{\"city\":\"NYC\"}}");
    }

    @Test
    @DisplayName("error and file parts render exactly")
    void errorAndFile() {
        assertThat(serializer.serialize(new UiMessagePart.ErrorPart("boom")))
                .isEqualTo("{\"type\":\"error\",\"errorText\":\"boom\"}");
        assertThat(serializer.serialize(new UiMessagePart.FilePart("https://x/y.png", "image/png")))
                .isEqualTo("{\"type\":\"file\",\"url\":\"https://x/y.png\",\"mediaType\":\"image/png\"}");
    }

    @Test
    @DisplayName("new v6 part types serialize exactly")
    void newV6Parts() {
        assertThat(serializer.serialize(
                new UiMessagePart.ToolInputError("call-1", "getWeather", Map.of("city", "NYC"), "bad input")))
                .isEqualTo("{\"type\":\"tool-input-error\",\"toolCallId\":\"call-1\","
                        + "\"toolName\":\"getWeather\",\"input\":{\"city\":\"NYC\"},\"errorText\":\"bad input\"}");

        assertThat(serializer.serialize(new UiMessagePart.ToolOutputError("call-1", "boom")))
                .isEqualTo("{\"type\":\"tool-output-error\",\"toolCallId\":\"call-1\",\"errorText\":\"boom\"}");

        assertThat(serializer.serialize(new UiMessagePart.MessageMetadata(Map.of("model", "gemini"))))
                .isEqualTo("{\"type\":\"message-metadata\",\"messageMetadata\":{\"model\":\"gemini\"}}");

        assertThat(serializer.serialize(new UiMessagePart.ToolApprovalRequest("appr-1", "call-1")))
                .isEqualTo("{\"type\":\"tool-approval-request\",\"approvalId\":\"appr-1\",\"toolCallId\":\"call-1\"}");

        assertThat(serializer.serialize(new UiMessagePart.ToolOutputDenied("call-1")))
                .isEqualTo("{\"type\":\"tool-output-denied\",\"toolCallId\":\"call-1\"}");

        assertThat(serializer.serialize(new UiMessagePart.Abort()))
                .isEqualTo("{\"type\":\"abort\"}");
    }

    @Test
    @DisplayName("optional protocol fields are included when present, in wire order")
    void optionalFieldsPresent() {
        assertThat(serializer.serialize(new UiMessagePart.ToolInputAvailable(
                "call-1", "getWeather", Map.of("city", "NYC"), true, null, true, null)))
                .isEqualTo("{\"type\":\"tool-input-available\",\"toolCallId\":\"call-1\","
                        + "\"toolName\":\"getWeather\",\"input\":{\"city\":\"NYC\"},"
                        + "\"providerExecuted\":true,\"dynamic\":true}");

        assertThat(serializer.serialize(new UiMessagePart.ToolOutputAvailable(
                "call-1", Map.of("tempC", 21), null, true, true)))
                .isEqualTo("{\"type\":\"tool-output-available\",\"toolCallId\":\"call-1\","
                        + "\"output\":{\"tempC\":21},\"dynamic\":true,\"preliminary\":true}");

        assertThat(serializer.serialize(new UiMessagePart.DataPart("status", null, Map.of("s", "thinking"), true)))
                .isEqualTo("{\"type\":\"data-status\",\"data\":{\"s\":\"thinking\"},\"transient\":true}");

        assertThat(serializer.serialize(new UiMessagePart.Finish("stop", Map.of("tokens", 42))))
                .isEqualTo("{\"type\":\"finish\",\"finishReason\":\"stop\",\"messageMetadata\":{\"tokens\":42}}");

        assertThat(serializer.serialize(
                new UiMessagePart.SourceDocument("s1", "application/pdf", "Doc", "d.pdf", null)))
                .isEqualTo("{\"type\":\"source-document\",\"sourceId\":\"s1\",\"mediaType\":\"application/pdf\","
                        + "\"title\":\"Doc\",\"filename\":\"d.pdf\"}");
    }
}
