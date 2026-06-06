package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import uz.uzinfoweb.uimessagestream.core.UiMessagePart;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChatClientResponseMapper")
class ChatClientResponseMapperTest {

    private static List<UiMessagePart> map(ResponseMapper mapper, ChatClientResponse response) {
        List<UiMessagePart> parts = new ArrayList<>();
        mapper.accept(response, new UiMessageStreamWriter(parts::add));
        return parts;
    }

    private static ChatClientResponse withToolCall(String id, String name, String arguments) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
        return new ChatClientResponse(new ChatResponse(List.of(new Generation(assistant))), Map.of());
    }

    private static UiMessagePart.ToolInputAvailable toolInput(List<UiMessagePart> parts) {
        return parts.stream()
                .filter(UiMessagePart.ToolInputAvailable.class::isInstance)
                .map(UiMessagePart.ToolInputAvailable.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("DEFAULT tags tool-input-available dynamic:true with parsed-JSON input")
    void defaultTagsDynamic() {
        UiMessagePart.ToolInputAvailable input = toolInput(
                map(ChatClientResponseMapper.DEFAULT, withToolCall("call_1", "getWeather", "{\"city\":\"NYC\"}")));

        assertThat(input.dynamic()).isTrue();
        assertThat(input.input()).isEqualTo(Map.of("city", "NYC"));
    }

    @Test
    @DisplayName("withDynamicTools(false) emits statically-typed tool parts")
    void staticTools() {
        UiMessagePart.ToolInputAvailable input = toolInput(
                map(ChatClientResponseMapper.withDynamicTools(false), withToolCall("call_1", "getWeather", "{}")));

        assertThat(input.dynamic()).isNull();
    }

    @Test
    @DisplayName("TEXT_ONLY emits text but no tool-input-available")
    void textOnlyOmitsToolInput() {
        List<UiMessagePart> parts = map(ChatClientResponseMapper.TEXT_ONLY,
                withToolCall("call_1", "getWeather", "{}"));

        assertThat(parts).noneMatch(UiMessagePart.ToolInputAvailable.class::isInstance);
    }
}
