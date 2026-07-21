package uz.uzinfoweb.uimessagestream.spring;

import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

/**
 * A specialized {@link ToolCallingAdvisor} that uses {@link RecordingToolCallingManager}
 * to natively surface tool input/output and implement the HITL approval gate.
 *
 * <p>The recording manager remains private to this advisor's tool loop, allowing applications to
 * opt in per {@code ChatClient} without replacing the global {@link ToolCallingManager} bean.
 *
 * <p><strong>Conversation history is enabled by default.</strong> Providers require the tool turn
 * sequence to stay contiguous in the follow-up request after a tool executes: {@code user →
 * assistant(functionCall) → tool(functionResponse)}. With history disabled, the parent
 * {@link ToolCallingAdvisor} rebuilds that follow-up prompt as {@code [system, toolResponse]} —
 * dropping the user and function-call turns — which Gemini rejects with {@code 400 "Please ensure
 * that function response turn comes immediately after a function call turn"} (OpenAI-style APIs
 * impose the same adjacency). Opting out via {@link UiMessageStreamBuilder#conversationHistory(boolean)}
 * is legitimate only when a chat-memory advisor sits <em>inside</em> the tool loop and re-injects
 * the history on every iteration itself.
 */
public class UiMessageStreamToolAdvisor extends ToolCallingAdvisor {

    public UiMessageStreamToolAdvisor(ToolCallingManager delegate,
                                      JsonMapper jsonParser,
                                      boolean dynamic,
                                      ApprovalPolicy approvalPolicy,
                                      ErrorMessageResolver errorMessages) {
        this(delegate, jsonParser, dynamic, approvalPolicy, errorMessages, true);
    }

    public UiMessageStreamToolAdvisor(ToolCallingManager delegate,
                                      JsonMapper jsonParser,
                                      boolean dynamic,
                                      ApprovalPolicy approvalPolicy,
                                      ErrorMessageResolver errorMessages,
                                      boolean conversationHistory) {
        super(new RecordingToolCallingManager(delegate, jsonParser, dynamic, approvalPolicy, errorMessages),
              DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER,
              DEFAULT_ORDER,
              conversationHistory);
    }

    public static UiMessageStreamBuilder uiMessageStreamBuilder() {
        return new UiMessageStreamBuilder();
    }

    public static class UiMessageStreamBuilder {
        private ToolCallingManager toolCallingManager;
        private JsonMapper jsonParser = new JsonMapper();
        private boolean dynamic = true;
        private ApprovalPolicy approvalPolicy = ApprovalPolicy.NONE;
        private ErrorMessageResolver errorMessages = ErrorMessageResolver.MASKED;
        private boolean conversationHistory = true;

        public UiMessageStreamBuilder toolCallingManager(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
            return this;
        }

        public UiMessageStreamBuilder jsonParser(JsonMapper jsonParser) {
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

        public UiMessageStreamBuilder conversationHistory(boolean conversationHistory) {
            this.conversationHistory = conversationHistory;
            return this;
        }

        public UiMessageStreamToolAdvisor build() {
            return new UiMessageStreamToolAdvisor(toolCallingManager, jsonParser, dynamic, approvalPolicy, errorMessages,
                    conversationHistory);
        }
    }
}
