package uz.uzinfoweb.uimessagestream.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.uzinfoweb.uimessagestream.spring.RecordingToolCallingManager;
import uz.uzinfoweb.uimessagestream.spring.ResponseMapper;

import java.util.List;

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
    @DisplayName("native tool I/O is off by default: the ToolCallingManager is not wrapped")
    void nativeToolIoOffByDefault() {
        runner.withUserConfiguration(StubManagerConfiguration.class).run(context -> {
            ToolCallingManager manager = context.getBean(ToolCallingManager.class);
            assertThat(manager).isNotInstanceOf(RecordingToolCallingManager.class);
        });
    }

    @Test
    @DisplayName("native tool I/O on: the ToolCallingManager is wrapped with RecordingToolCallingManager")
    void nativeToolIoEnabledByProperty() {
        runner.withUserConfiguration(StubManagerConfiguration.class)
                .withPropertyValues("uimessagestream.tool-io.native=true")
                .run(context -> {
                    ToolCallingManager manager = context.getBean(ToolCallingManager.class);
                    assertThat(manager).isInstanceOf(RecordingToolCallingManager.class);
                });
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
