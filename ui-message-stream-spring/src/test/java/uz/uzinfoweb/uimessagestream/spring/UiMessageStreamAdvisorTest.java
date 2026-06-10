package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UiMessageStreamAdvisor")
class UiMessageStreamAdvisorTest {

    @Test
    @DisplayName("injects sink into tool context when options are ToolCallingChatOptions")
    void injectsSinkIntoToolContext() {
        SerializedPartSink sink = new SerializedPartSink();
        UiMessageStreamAdvisor advisor = new UiMessageStreamAdvisor(sink);

        ToolCallingChatOptions options = ToolCallingChatOptions.builder().build();
        Prompt prompt = new Prompt(List.of(), options);
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        AtomicReference<ChatClientRequest> captured = new AtomicReference<>();
        StreamAdvisorChain chain = capturingChain(captured);

        advisor.adviseStream(request, chain);

        ChatClientRequest forwarded = captured.get();
        assertThat(forwarded).isNotNull();
        ToolCallingChatOptions forwardedOptions = (ToolCallingChatOptions) forwarded.prompt().getOptions();
        assertThat(forwardedOptions.getToolContext().get(RecordingToolCallingManager.SINK_KEY))
                .isSameAs(sink);
    }

    @Test
    @DisplayName("passes through request unchanged when options are not ToolCallingChatOptions")
    void passesThroughWhenNotToolCallingChatOptions() {
        SerializedPartSink sink = new SerializedPartSink();
        UiMessageStreamAdvisor advisor = new UiMessageStreamAdvisor(sink);

        // No options set — prompt has null options.
        Prompt prompt = new Prompt(List.of());
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        AtomicReference<ChatClientRequest> captured = new AtomicReference<>();
        StreamAdvisorChain chain = capturingChain(captured);

        advisor.adviseStream(request, chain);

        assertThat(captured.get()).isSameAs(request);
    }

    @Test
    @DisplayName("preserves existing tool context entries when injecting sink")
    void preservesExistingToolContext() {
        SerializedPartSink sink = new SerializedPartSink();
        UiMessageStreamAdvisor advisor = new UiMessageStreamAdvisor(sink);

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext("app.key", "app-value")
                .build();
        Prompt prompt = new Prompt(List.of(), options);
        ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

        AtomicReference<ChatClientRequest> captured = new AtomicReference<>();
        advisor.adviseStream(request, capturingChain(captured));

        ToolCallingChatOptions forwarded = (ToolCallingChatOptions) captured.get().prompt().getOptions();
        assertThat(forwarded.getToolContext()).containsEntry("app.key", "app-value");
        assertThat(forwarded.getToolContext().get(RecordingToolCallingManager.SINK_KEY)).isSameAs(sink);
    }

    @Test
    @DisplayName("getName returns simple class name")
    void getName() {
        assertThat(new UiMessageStreamAdvisor(new SerializedPartSink()).getName())
                .isEqualTo("UiMessageStreamAdvisor");
    }

    @Test
    @DisplayName("default order is DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1")
    void defaultOrder() {
        UiMessageStreamAdvisor advisor = new UiMessageStreamAdvisor(new SerializedPartSink());
        assertThat(advisor.getOrder())
                .isEqualTo(UiMessageStreamAdvisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1);
    }

    @Test
    @DisplayName("custom order is honoured")
    void customOrder() {
        UiMessageStreamAdvisor advisor = new UiMessageStreamAdvisor(new SerializedPartSink(), 42);
        assertThat(advisor.getOrder()).isEqualTo(42);
    }

    // --- helpers ---

    private static StreamAdvisorChain capturingChain(AtomicReference<ChatClientRequest> captured) {
        return new StreamAdvisorChain() {
            @Override
            public Flux<ChatClientResponse> nextStream(ChatClientRequest request) {
                captured.set(request);
                return Flux.empty();
            }

            @Override
            public List<org.springframework.ai.chat.client.advisor.api.StreamAdvisor> getStreamAdvisors() {
                return List.of();
            }

            @Override
            public StreamAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.StreamAdvisor advisor) {
                return this;
            }
        };
    }
}
