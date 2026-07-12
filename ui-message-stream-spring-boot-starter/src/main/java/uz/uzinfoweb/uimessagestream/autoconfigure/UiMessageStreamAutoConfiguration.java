package uz.uzinfoweb.uimessagestream.autoconfigure;

import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import uz.uzinfoweb.uimessagestream.spring.ApprovalPolicy;
import uz.uzinfoweb.uimessagestream.spring.ChatClientResponseMapper;
import uz.uzinfoweb.uimessagestream.spring.ErrorMessageResolver;
import uz.uzinfoweb.uimessagestream.spring.ResponseMapper;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStreamToolAdvisor;

/**
 * Registers the default {@link ResponseMapper} so applications can inject it (and pass it to
 * {@code UiMessageStream.from(upstream, mapper)} / {@code UiMessageStreamEmitter}) or override it by
 * simply declaring their own {@code ResponseMapper} bean.
 *
 * <p>Configuration ({@code uimessagestream.tool-io.*}):
 * <ul>
 *   <li>{@code dynamic} (default {@code true}) — tag tool parts {@code "dynamic":true} so the client
 *       renders them via its generic {@code dynamic-tool} path; feeds both the default mapper and the
 *       opt-in recording manager.</li>
 *   <li>{@code native} (default {@code false}) — register a {@link UiMessageStreamToolAdvisor} so
 *       tool input + output (and the HITL approval gate) are emitted natively into the per-request
 *       {@link uz.uzinfoweb.uimessagestream.spring.SerializedPartSink}.</li>
 * </ul>
 *
 * <p>{@code uimessagestream.errors.include-message} (default {@code false}) — when {@code true}, a tool
 * failure's {@code tool-output-error} carries the raw exception message ({@link
 * ErrorMessageResolver#MESSAGE}); by default it is masked ({@link ErrorMessageResolver#MASKED}) so no
 * server internals leak to the client. Declare an {@link ErrorMessageResolver} bean to customize.
 *
 * <p>A default no-op {@link ApprovalPolicy} ({@link ApprovalPolicy#NONE}) is registered
 * ({@code @ConditionalOnMissingBean}) so the approval gate stays opt-in: declare your own
 * {@code ApprovalPolicy} bean to gate specific tools.
 */
@AutoConfiguration
public class UiMessageStreamAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ResponseMapper uiMessageStreamResponseMapper(
            @Value("${uimessagestream.tool-io.dynamic:true}") boolean dynamicTools) {
        return ChatClientResponseMapper.withDynamicTools(dynamicTools);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalPolicy uiMessageStreamApprovalPolicy() {
        return ApprovalPolicy.NONE;
    }

    /**
     * Opt-in: registers a {@link UiMessageStreamToolAdvisor} that natively surfaces tool
     * input/output.
     */
    @Bean
    @ConditionalOnProperty(prefix = "uimessagestream.tool-io", name = "native", havingValue = "true")
    @ConditionalOnBean(ToolCallingManager.class)
    public UiMessageStreamToolAdvisor uiMessageStreamToolAdvisor(
            ToolCallingManager toolCallingManager,
            @Value("${uimessagestream.tool-io.dynamic:true}") boolean dynamicTools,
            @Value("${uimessagestream.errors.include-message:false}") boolean includeMessage,
            ObjectProvider<ApprovalPolicy> approvalPolicy,
            ObjectProvider<ErrorMessageResolver> errorMessages) {

        ApprovalPolicy policy = approvalPolicy.getIfAvailable(() -> ApprovalPolicy.NONE);
        ErrorMessageResolver resolver = errorMessages.getIfAvailable(() ->
                includeMessage ? ErrorMessageResolver.MESSAGE : ErrorMessageResolver.MASKED);

        return UiMessageStreamToolAdvisor.uiMessageStreamBuilder()
                .toolCallingManager(toolCallingManager)
                .jsonParser(new ObjectMapper())
                .dynamic(dynamicTools)
                .approvalPolicy(policy)
                .errorMessages(resolver)
                .build();
    }

    /**
     * Automatically adds the {@link UiMessageStreamToolAdvisor} to all {@code ChatClient} instances.
     */
    @Bean
    @ConditionalOnBean(UiMessageStreamToolAdvisor.class)
    public ChatClientBuilderCustomizer uiMessageStreamChatClientCustomizer(UiMessageStreamToolAdvisor advisor) {
        return builder -> builder.defaultAdvisors(advisor);
    }
}
