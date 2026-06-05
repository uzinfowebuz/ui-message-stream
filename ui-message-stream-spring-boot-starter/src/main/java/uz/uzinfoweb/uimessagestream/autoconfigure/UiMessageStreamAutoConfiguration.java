package uz.uzinfoweb.uimessagestream.autoconfigure;

import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import uz.uzinfoweb.uimessagestream.spring.ChatClientResponseMapper;
import uz.uzinfoweb.uimessagestream.spring.RecordingToolCallingManager;
import uz.uzinfoweb.uimessagestream.spring.ResponseMapper;

/**
 * Registers the default {@link ResponseMapper} so applications can inject it (and pass it to
 * {@code UiMessageStream.from(upstream, mapper)} / {@code UiMessageStreamEmitter}) or override it by
 * simply declaring their own {@code ResponseMapper} bean.
 *
 * <p>Opt-in native tool I/O: when {@code uimessagestream.tool-io.native=true}, a
 * {@link BeanPostProcessor} wraps the application's {@link ToolCallingManager} (Spring AI's
 * {@code DefaultToolCallingManager}, or a custom one) with a {@link RecordingToolCallingManager} so
 * tool input + output are emitted natively into the per-request
 * {@link uz.uzinfoweb.uimessagestream.spring.SerializedPartSink}. It is <b>off by default</b>, so the
 * simple default path is never altered, and replacing the manager globally requires an explicit flag.
 */
@AutoConfiguration
public class UiMessageStreamAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ResponseMapper uiMessageStreamResponseMapper() {
        return ChatClientResponseMapper.DEFAULT;
    }

    /**
     * Opt-in: wraps the {@link ToolCallingManager} bean with a {@link RecordingToolCallingManager}.
     * Declared {@code static} so it is instantiated early enough to post-process the manager.
     */
    @Bean
    @ConditionalOnProperty(prefix = "uimessagestream.tool-io", name = "native", havingValue = "true")
    static BeanPostProcessor uiMessageStreamRecordingToolCallingManager() {
        return new RecordingToolCallingManagerPostProcessor();
    }

    /** Wraps any {@link ToolCallingManager} bean with a {@link RecordingToolCallingManager} (once). */
    static final class RecordingToolCallingManagerPostProcessor implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof ToolCallingManager manager && !(bean instanceof RecordingToolCallingManager)) {
                return new RecordingToolCallingManager(manager);
            }
            return bean;
        }
    }
}
