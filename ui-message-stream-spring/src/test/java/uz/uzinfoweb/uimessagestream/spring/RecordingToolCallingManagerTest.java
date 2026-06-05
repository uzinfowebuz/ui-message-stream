package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import uz.uzinfoweb.uimessagestream.core.UiMessagePart;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RecordingToolCallingManager")
class RecordingToolCallingManagerTest {

    @Test
    @DisplayName("emits tool-input before delegating and tool-output (paired by id) after, closing the open text block first")
    void emitsToolInputThenOutput() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));
        sink.text("Let me check"); // an open text block exists when the tool runs

        String callId = "call_1";
        ChatResponse chatResponse = chatResponseWithToolCall(callId, "getWeather", "{\"city\":\"London\"}");
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, "getWeather", "{\"tempC\":12}")))
                .build();

        AtomicBoolean delegated = new AtomicBoolean(false);
        ToolCallingManager delegate = delegate(delegated, toolResponse);

        Prompt prompt = promptWithSink(sink);

        new RecordingToolCallingManager(delegate).executeToolCalls(prompt, chatResponse);

        assertThat(delegated).isTrue();

        int textEnd = indexOfType(parts, "text-end");
        int toolInput = indexOfType(parts, "tool-input-available");
        int toolOutput = indexOfType(parts, "tool-output-available");

        assertThat(textEnd).as("open text block closed").isGreaterThanOrEqualTo(0).isLessThan(toolInput);
        assertThat(toolInput).as("input before output").isLessThan(toolOutput);

        UiMessagePart.ToolInputAvailable input = (UiMessagePart.ToolInputAvailable) parts.get(toolInput);
        assertThat(input.toolCallId()).isEqualTo(callId);
        assertThat(input.toolName()).isEqualTo("getWeather");
        assertThat(input.input()).isEqualTo(Map.of("city", "London")); // parsed JSON object, not a string

        UiMessagePart.ToolOutputAvailable output = (UiMessagePart.ToolOutputAvailable) parts.get(toolOutput);
        assertThat(output.toolCallId()).isEqualTo(callId);
        assertThat(output.output()).isEqualTo(Map.of("tempC", 12));
    }

    @Test
    @DisplayName("with no sink in the tool context it is a transparent pass-through")
    void passThroughWithoutSink() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id", "n", "{}")))
                .build();
        ToolCallingManager delegate = delegate(delegated, toolResponse);

        Prompt prompt = new Prompt(List.of(new UserMessage("hi")), ToolCallingChatOptions.builder().build());
        ChatResponse chatResponse = chatResponseWithToolCall("id", "n", "{}");

        ToolExecutionResult result = new RecordingToolCallingManager(delegate).executeToolCalls(prompt, chatResponse);

        assertThat(delegated).isTrue();
        assertThat(result).isNotNull();
        assertThat(result.conversationHistory()).containsExactly(toolResponse);
    }

    private static ChatResponse chatResponseWithToolCall(String id, String name, String arguments) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static Prompt promptWithSink(SerializedPartSink sink) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of(RecordingToolCallingManager.SINK_KEY, sink))
                .build();
        return new Prompt(List.of(new UserMessage("weather?")), options);
    }

    private static ToolCallingManager delegate(AtomicBoolean delegated, ToolResponseMessage toolResponse) {
        return new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                delegated.set(true);
                return () -> List.<Message>of(toolResponse);
            }
        };
    }

    private static int indexOfType(List<UiMessagePart> parts, String type) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).type().equals(type)) {
                return i;
            }
        }
        return -1;
    }
}
