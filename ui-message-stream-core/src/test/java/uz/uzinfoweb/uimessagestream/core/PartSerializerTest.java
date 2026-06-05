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
}
