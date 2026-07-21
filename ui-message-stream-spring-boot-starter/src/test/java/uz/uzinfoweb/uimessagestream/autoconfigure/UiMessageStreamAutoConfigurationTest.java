package uz.uzinfoweb.uimessagestream.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.uzinfoweb.uimessagestream.core.UiMessagePart;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;
import uz.uzinfoweb.uimessagestream.spring.ApprovalPolicy;
import uz.uzinfoweb.uimessagestream.spring.ResponseMapper;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStreamToolAdvisor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UiMessageStreamAutoConfiguration")
class UiMessageStreamAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UiMessageStreamAutoConfiguration.class));

    @Test
    @DisplayName("registers the default ResponseMapper bean")
    void registersDefaultResponseMapper() {
        runner.run(context -> assertThat(context).hasSingleBean(ResponseMapper.class));
    }

    @Test
    @DisplayName("native tool I/O is off by default: the advisor is not registered")
    void nativeToolIoOffByDefault() {
        runner.withUserConfiguration(StubManagerConfiguration.class).run(context -> {
            assertThat(context).doesNotHaveBean(UiMessageStreamToolAdvisor.class);
            assertThat(context).doesNotHaveBean(ChatClientBuilderCustomizer.class);
        });
    }

    @Test
    @DisplayName("native tool I/O on: the advisor and customizer are registered")
    void nativeToolIoEnabledByProperty() {
        runner.withUserConfiguration(StubManagerConfiguration.class)
                .withPropertyValues("uimessagestream.tool-io.native=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(UiMessageStreamToolAdvisor.class);
                    assertThat(context).hasSingleBean(ChatClientBuilderCustomizer.class);
                });
    }

    @Test
    @DisplayName("conversation history is enabled on the advisor by default")
    void conversationHistoryEnabledByDefault() {
        runner.withUserConfiguration(StubManagerConfiguration.class)
                .withPropertyValues("uimessagestream.tool-io.native=true")
                .run(context -> assertThat(
                        conversationHistoryEnabled(context.getBean(UiMessageStreamToolAdvisor.class))).isTrue());
    }

    @Test
    @DisplayName("the conversation-history=false property is wired into the advisor")
    void conversationHistoryPropertyWiresIntoAdvisor() {
        runner.withUserConfiguration(StubManagerConfiguration.class)
                .withPropertyValues("uimessagestream.tool-io.native=true",
                        "uimessagestream.tool-io.conversation-history=false")
                .run(context -> assertThat(
                        conversationHistoryEnabled(context.getBean(UiMessageStreamToolAdvisor.class))).isFalse());
    }

    @Test
    @DisplayName("registers a default no-op ApprovalPolicy")
    void registersDefaultApprovalPolicy() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ApprovalPolicy.class);
            assertThat(context.getBean(ApprovalPolicy.class).needsApproval("any", null)).isFalse();
        });
    }

    @Test
    @DisplayName("a user ApprovalPolicy bean overrides the default")
    void userApprovalPolicyOverrides() {
        runner.withUserConfiguration(CustomApprovalPolicyConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(ApprovalPolicy.class);
            assertThat(context.getBean(ApprovalPolicy.class).needsApproval("x", null)).isTrue();
        });
    }

    @Test
    @DisplayName("the dynamic=false property is wired into the default ResponseMapper")
    void dynamicPropertyWiresIntoMapper() {
        runner.withPropertyValues("uimessagestream.tool-io.dynamic=false").run(context -> {
            ResponseMapper mapper = context.getBean(ResponseMapper.class);
            List<UiMessagePart> parts = new ArrayList<>();
            mapper.accept(toolCallResponse(), new UiMessageStreamWriter(parts::add));

            UiMessagePart.ToolInputAvailable input = parts.stream()
                    .filter(UiMessagePart.ToolInputAvailable.class::isInstance)
                    .map(UiMessagePart.ToolInputAvailable.class::cast)
                    .findFirst().orElseThrow();
            assertThat(input.dynamic()).isNull();
        });
    }

    /**
     * {@code conversationHistoryEnabled} is a private field of the parent {@link ToolCallingAdvisor}
     * with no accessor, so the wiring is asserted reflectively.
     */
    private static boolean conversationHistoryEnabled(UiMessageStreamToolAdvisor advisor) throws Exception {
        Field field = ToolCallingAdvisor.class.getDeclaredField("conversationHistoryEnabled");
        field.setAccessible(true);
        return (boolean) field.get(advisor);
    }

    private static ChatClientResponse toolCallResponse() {
        AssistantMessage assistant = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "getWeather", "{}")))
                .build();
        return new ChatClientResponse(new ChatResponse(List.of(new Generation(assistant))), Map.of());
    }

    @Configuration
    static class CustomApprovalPolicyConfiguration {

        @Bean
        ApprovalPolicy approvalPolicy() {
            return (name, input) -> true;
        }
    }

    @Configuration
    static class StubManagerConfiguration {

        @Bean
        ToolCallingManager toolCallingManager() {
            return new ToolCallingManager() {
                @Override
                public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                    return List.of();
                }

                @Override
                public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                    return () -> List.of();
                }
            };
        }
    }
}
