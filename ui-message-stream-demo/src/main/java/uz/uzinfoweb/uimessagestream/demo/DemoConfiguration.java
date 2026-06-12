package uz.uzinfoweb.uimessagestream.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.StaticToolCallbackResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uz.uzinfoweb.uimessagestream.spring.ApprovalPolicy;
import uz.uzinfoweb.uimessagestream.spring.ChatClientResponseMapper;
import uz.uzinfoweb.uimessagestream.spring.ResponseMapper;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStream;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStreamEmitter;

import java.util.List;

/**
 * All the wiring a real application would do. Three of these beans are library extension points
 * picked up by the starter's auto-configuration:
 * <ul>
 *   <li>{@link #responseMapper()} overrides the starter's default mapper
 *       ({@code @ConditionalOnMissingBean}) with {@code TEXT_ONLY}, because native tool I/O is on
 *       and the {@code RecordingToolCallingManager} emits tool frames — the mapper must not
 *       duplicate them;</li>
 *   <li>{@link #approvalPolicy()} gates {@code transferFunds} behind human approval;</li>
 *   <li>{@link #toolCallingManager(DemoTools)} is the plain Spring AI manager — with
 *       {@code uimessagestream.tool-io.native=true} the starter's {@code BeanPostProcessor} wraps it
 *       into a {@code RecordingToolCallingManager} automatically; nothing here references the
 *       decorator.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class DemoConfiguration {

    @Bean
    DemoTools demoTools() {
        return new DemoTools();
    }

    /**
     * Plain Spring AI tool machinery. {@code alwaysThrow=true} makes a failing tool propagate its
     * exception (instead of feeding the message back to the model as an ordinary result), which is
     * what lets the library emit {@code tool-output-error}.
     */
    @Bean
    ToolCallingManager toolCallingManager(DemoTools demoTools) {
        return DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(List.of(ToolCallbacks.from(demoTools))))
                .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(true))
                .build();
    }

    /**
     * The injected {@code toolCallingManager} is already the recording decorator here (the starter's
     * post-processor wrapped it), exactly as it would be with a real provider's auto-configured model.
     */
    @Bean
    ChatClient chatClient(ToolCallingManager toolCallingManager, DemoTools demoTools) {
        return ChatClient.builder(new ScriptedChatModel(toolCallingManager))
                .defaultTools(tools -> tools.instances(demoTools))
                .build();
    }

    /** Overrides the starter's default mapper: tool frames come from the recording manager only. */
    @Bean
    ResponseMapper responseMapper() {
        return ChatClientResponseMapper.TEXT_ONLY;
    }

    /** Human-in-the-loop: only {@code transferFunds} needs approval; everything else runs freely. */
    @Bean
    ApprovalPolicy approvalPolicy() {
        return (toolName, input) -> "transferFunds".equals(toolName);
    }

    @Bean
    UiMessageStream uiMessageStream() {
        return new UiMessageStream();
    }

    @Bean
    UiMessageStreamEmitter uiMessageStreamEmitter() {
        return new UiMessageStreamEmitter();
    }
}
