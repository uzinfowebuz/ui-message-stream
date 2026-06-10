package uz.uzinfoweb.uimessagestream.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import uz.uzinfoweb.uimessagestream.spring.ApprovalPolicy;
import uz.uzinfoweb.uimessagestream.spring.ChatClientResponseMapper;
import uz.uzinfoweb.uimessagestream.spring.ErrorMessageResolver;
import uz.uzinfoweb.uimessagestream.spring.RecordingToolCallingManager;
import uz.uzinfoweb.uimessagestream.spring.ResponseMapper;

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
 *   <li>{@code native} (default {@code false}) — wrap the application's {@link ToolCallingManager} with
 *       a {@link RecordingToolCallingManager} so tool input + output (and the HITL approval gate) are
 *       emitted natively into the per-request
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
     * Opt-in: wraps the {@link ToolCallingManager} bean with a {@link RecordingToolCallingManager},
     * honouring the {@code dynamic} flag and the application's {@link ApprovalPolicy}. Declared
     * {@code static} so it is instantiated early enough to post-process the manager.
     */
    @Bean
    @ConditionalOnProperty(prefix = "uimessagestream.tool-io", name = "native", havingValue = "true")
    static BeanPostProcessor uiMessageStreamRecordingToolCallingManager(
            Environment environment, ObjectProvider<ApprovalPolicy> approvalPolicy,
            ObjectProvider<ErrorMessageResolver> errorMessages) {
        // Read from Environment, not @Value: a static BeanPostProcessor is instantiated before the
        // property-placeholder configurer, so a ${...} parameter would arrive unresolved.
        boolean dynamicTools = environment.getProperty("uimessagestream.tool-io.dynamic", Boolean.class, true);
        boolean includeMessage = environment.getProperty("uimessagestream.errors.include-message", Boolean.class, false);
        return new RecordingToolCallingManagerPostProcessor(dynamicTools, includeMessage, approvalPolicy, errorMessages);
    }

    /** Wraps any {@link ToolCallingManager} bean with a {@link RecordingToolCallingManager} (once). */
    static final class RecordingToolCallingManagerPostProcessor implements BeanPostProcessor {

        private final boolean dynamicTools;
        private final boolean includeErrorMessage;
        private final ObjectProvider<ApprovalPolicy> approvalPolicy;
        private final ObjectProvider<ErrorMessageResolver> errorMessages;

        RecordingToolCallingManagerPostProcessor(boolean dynamicTools, boolean includeErrorMessage,
                                                 ObjectProvider<ApprovalPolicy> approvalPolicy,
                                                 ObjectProvider<ErrorMessageResolver> errorMessages) {
            this.dynamicTools = dynamicTools;
            this.includeErrorMessage = includeErrorMessage;
            this.approvalPolicy = approvalPolicy;
            this.errorMessages = errorMessages;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof ToolCallingManager manager && !(bean instanceof RecordingToolCallingManager)) {
                ApprovalPolicy policy = approvalPolicy.getIfAvailable(() -> ApprovalPolicy.NONE);
                ErrorMessageResolver resolver = errorMessages.getIfAvailable(() ->
                        includeErrorMessage ? ErrorMessageResolver.MESSAGE : ErrorMessageResolver.MASKED);
                return new RecordingToolCallingManager(manager, new ObjectMapper(), dynamicTools, policy, resolver);
            }
            return bean;
        }
    }
}
