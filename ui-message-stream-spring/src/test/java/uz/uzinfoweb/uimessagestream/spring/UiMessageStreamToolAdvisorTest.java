package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import uz.uzinfoweb.uimessagestream.core.UiMessagePart;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UiMessageStreamToolAdvisor")
class UiMessageStreamToolAdvisorTest {

    @Test
    @DisplayName("owns the streaming tool loop and records input before output")
    void recordsToolLoop() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();

        ToolCallingManager delegate = new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                executions.incrementAndGet();
                AssistantMessage assistant = chatResponse.getResults().getFirst().getOutput();
                AssistantMessage.ToolCall call = assistant.getToolCalls().getFirst();
                ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                call.id(), call.name(), "{\"tempC\":12}")))
                        .build();
                List<Message> history = new ArrayList<>(prompt.getInstructions());
                history.add(assistant);
                history.add(toolResponse);
                return () -> history;
            }
        };

        UiMessageStreamToolAdvisor advisor = UiMessageStreamToolAdvisor.uiMessageStreamBuilder()
                .toolCallingManager(delegate)
                .build();
        ChatClient chatClient = ChatClient.builder(new ScriptedToolChatModel(modelCalls))
                .defaultTools(new WeatherTools())
                .defaultAdvisors(advisor)
                .build();

        List<ChatClientResponse> responses = chatClient.prompt()
                .user("weather")
                .advisors(new UiMessageStreamAdvisor(sink))
                .stream()
                .chatClientResponse()
                .collectList()
                .block();

        assertThat(executions).hasValue(1);
        assertThat(modelCalls).hasValue(2);
        assertThat(responses).isNotNull().isNotEmpty();
        assertThat(parts).extracting(UiMessagePart::type)
                .containsSubsequence("tool-input-available", "tool-output-available");
    }

    private static final class WeatherTools {

        @Tool(description = "Get the current temperature")
        String getWeather(String city) {
            return "12 C in " + city;
        }
    }

    private static final class ScriptedToolChatModel implements ChatModel {

        private final AtomicInteger calls;

        private ScriptedToolChatModel(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return response(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(response(prompt));
        }

        @Override
        public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private ChatResponse response(Prompt prompt) {
            calls.incrementAndGet();
            Message last = prompt.getInstructions().getLast();
            if (last instanceof ToolResponseMessage) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Done"))));
            }
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "call_1", "function", "getWeather", "{\"city\":\"Tashkent\"}");
            AssistantMessage assistant = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(toolCall))
                    .build();
            return new ChatResponse(List.of(new Generation(assistant)));
        }
    }
}