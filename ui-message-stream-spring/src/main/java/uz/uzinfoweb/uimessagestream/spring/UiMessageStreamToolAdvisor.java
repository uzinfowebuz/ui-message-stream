package uz.uzinfoweb.uimessagestream.spring;

import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

/**
 * A specialized {@link ToolCallingAdvisor} that uses {@link RecordingToolCallingManager}
 * to natively surface tool input/output and implement the HITL approval gate.
 *
 * <p>The recording manager remains private to this advisor's tool loop, allowing applications to
 * opt in per {@code ChatClient} without replacing the global {@link ToolCallingManager} bean.
 */
public class UiMessageStreamToolAdvisor extends ToolCallingAdvisor {

    public UiMessageStreamToolAdvisor(ToolCallingManager delegate,
                                      ObjectMapper jsonParser,
                                      boolean dynamic,
                                      ApprovalPolicy approvalPolicy,
                                      ErrorMessageResolver errorMessages) {
        super(new RecordingToolCallingManager(delegate, jsonParser, dynamic, approvalPolicy, errorMessages),
              DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER,
              DEFAULT_ORDER,
              false);
    }

    public static UiMessageStreamBuilder uiMessageStreamBuilder() {
        return new UiMessageStreamBuilder();
    }

    public static class UiMessageStreamBuilder {
        private ToolCallingManager toolCallingManager;
        private ObjectMapper jsonParser = new ObjectMapper();
        private boolean dynamic = true;
        private ApprovalPolicy approvalPolicy = ApprovalPolicy.NONE;
        private ErrorMessageResolver errorMessages = ErrorMessageResolver.MASKED;

        public UiMessageStreamBuilder toolCallingManager(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
            return this;
        }

        public UiMessageStreamBuilder jsonParser(ObjectMapper jsonParser) {
            this.jsonParser = jsonParser;
            return this;
        }

        public UiMessageStreamBuilder dynamic(boolean dynamic) {
            this.dynamic = dynamic;
            return this;
        }

        public UiMessageStreamBuilder approvalPolicy(ApprovalPolicy approvalPolicy) {
            this.approvalPolicy = approvalPolicy;
            return this;
        }

        public UiMessageStreamBuilder errorMessages(ErrorMessageResolver errorMessages) {
            this.errorMessages = errorMessages;
            return this;
        }

        public UiMessageStreamToolAdvisor build() {
            return new UiMessageStreamToolAdvisor(toolCallingManager, jsonParser, dynamic, approvalPolicy, errorMessages);
        }
    }
}
