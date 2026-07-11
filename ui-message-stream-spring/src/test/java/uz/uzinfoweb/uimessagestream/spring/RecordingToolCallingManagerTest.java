package uz.uzinfoweb.uimessagestream.spring;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import uz.uzinfoweb.uimessagestream.core.UiMessagePart;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RecordingToolCallingManager")
class RecordingToolCallingManagerTest {

    @Test
    @DisplayName("emits tool-input before delegating and tool-output (paired by id) after, closing the open text block first")
    void emitsToolInputThenOutput() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));
        sink.text("Let me check"); // an open text block exists when the tool runs

        String callId = "call_1";
        ChatResponse chatResponse = chatResponseWithToolCall(callId, "getWeather", "{\"city\":\"London\"}");
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, "getWeather", "{\"tempC\":12}")))
                .build();

        AtomicBoolean delegated = new AtomicBoolean(false);
        ToolCallingManager delegate = delegate(delegated, toolResponse);

        Prompt prompt = promptWithSink(sink);

        new RecordingToolCallingManager(delegate).executeToolCalls(prompt, chatResponse);

        assertThat(delegated).isTrue();

        int textEnd = indexOfType(parts, "text-end");
        int toolInput = indexOfType(parts, "tool-input-available");
        int toolOutput = indexOfType(parts, "tool-output-available");

        assertThat(textEnd).as("open text block closed").isGreaterThanOrEqualTo(0).isLessThan(toolInput);
        assertThat(toolInput).as("input before output").isLessThan(toolOutput);

        UiMessagePart.ToolInputAvailable input = (UiMessagePart.ToolInputAvailable) parts.get(toolInput);
        assertThat(input.toolCallId()).isEqualTo(callId);
        assertThat(input.toolName()).isEqualTo("getWeather");
        assertThat(input.input()).isEqualTo(Map.of("city", "London")); // parsed JSON object, not a string

        UiMessagePart.ToolOutputAvailable output = (UiMessagePart.ToolOutputAvailable) parts.get(toolOutput);
        assertThat(output.toolCallId()).isEqualTo(callId);
        assertThat(output.output()).isEqualTo(Map.of("tempC", 12));
    }

    @Test
    @DisplayName("with no sink in the tool context it is a transparent pass-through")
    void passThroughWithoutSink() {
        AtomicBoolean delegated = new AtomicBoolean(false);
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id", "n", "{}")))
                .build();
        ToolCallingManager delegate = delegate(delegated, toolResponse);

        Prompt prompt = new Prompt(List.of(new UserMessage("hi")), ToolCallingChatOptions.builder().build());
        ChatResponse chatResponse = chatResponseWithToolCall("id", "n", "{}");

        ToolExecutionResult result = new RecordingToolCallingManager(delegate).executeToolCalls(prompt, chatResponse);

        assertThat(delegated).isTrue();
        assertThat(result).isNotNull();
        assertThat(result.conversationHistory()).containsExactly(toolResponse);
    }

    @Test
    @DisplayName("tool parts are tagged dynamic:true by default, and dynamic=false omits the flag")
    void dynamicTagging() {
        List<UiMessagePart> dyn = recordParts(new RecordingToolCallingManager(
                delegate(new AtomicBoolean(), toolResponse("call_1", "getWeather", "{\"tempC\":12}")),
                new ObjectMapper(), true));
        UiMessagePart.ToolInputAvailable dynIn =
                (UiMessagePart.ToolInputAvailable) dyn.get(indexOfType(dyn, "tool-input-available"));
        UiMessagePart.ToolOutputAvailable dynOut =
                (UiMessagePart.ToolOutputAvailable) dyn.get(indexOfType(dyn, "tool-output-available"));
        assertThat(dynIn.dynamic()).isTrue();
        assertThat(dynOut.dynamic()).isTrue();

        List<UiMessagePart> stat = recordParts(new RecordingToolCallingManager(
                delegate(new AtomicBoolean(), toolResponse("call_1", "getWeather", "{}")),
                new ObjectMapper(), false));
        UiMessagePart.ToolInputAvailable statIn =
                (UiMessagePart.ToolInputAvailable) stat.get(indexOfType(stat, "tool-input-available"));
        assertThat(statIn.dynamic()).isNull();
    }

    @Test
    @DisplayName("a thrown tool surfaces a masked tool-output-error for the in-flight call, then rethrows")
    void throwingToolEmitsOutputError() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));

        ChatResponse chatResponse = chatResponseWithToolCall("call_1", "boomTool", "{}");
        ToolCallingManager delegate = throwingDelegate("kaboom");

        assertThatThrownBy(() ->
                new RecordingToolCallingManager(delegate).executeToolCalls(promptWithSink(sink), chatResponse))
                .isInstanceOf(IllegalStateException.class);

        int errIdx = indexOfType(parts, "tool-output-error");
        assertThat(errIdx).as("tool-output-error emitted").isGreaterThanOrEqualTo(0);
        UiMessagePart.ToolOutputError err = (UiMessagePart.ToolOutputError) parts.get(errIdx);
        assertThat(err.toolCallId()).isEqualTo("call_1");
        assertThat(err.errorText()).isEqualTo("An error occurred."); // masked by default
        assertThat(err.dynamic()).isTrue();
    }

    @Test
    @DisplayName("ErrorMessageResolver.MESSAGE opts the tool-output-error back into raw message disclosure")
    void throwingToolDisclosesMessageWhenOptedIn() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));

        RecordingToolCallingManager manager = new RecordingToolCallingManager(
                throwingDelegate("kaboom"), new ObjectMapper(), true, ApprovalPolicy.NONE,
                ErrorMessageResolver.MESSAGE);

        assertThatThrownBy(() -> manager.executeToolCalls(
                promptWithSink(sink), chatResponseWithToolCall("call_1", "boomTool", "{}")))
                .isInstanceOf(IllegalStateException.class);

        UiMessagePart.ToolOutputError err =
                (UiMessagePart.ToolOutputError) parts.get(indexOfType(parts, "tool-output-error"));
        assertThat(err.errorText()).isEqualTo("kaboom");
    }

    private static ToolCallingManager throwingDelegate(String message) {
        return new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                throw new IllegalStateException(message);
            }
        };
    }

    @Test
    @DisplayName("a policy-gated call with no decision emits tool-approval-request and pauses (returnDirect, no execution)")
    void approvalPauses() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));

        AtomicBoolean delegated = new AtomicBoolean(false);
        ToolCallingManager delegate = delegate(delegated, toolResponse("call_1", "getWeather", "{}"));

        ToolExecutionResult result = new RecordingToolCallingManager(delegate, new ObjectMapper(), true,
                (name, input) -> true)
                .executeToolCalls(promptWith(sink, Map.of()),
                        chatResponseWithToolCall("call_1", "getWeather", "{\"city\":\"NYC\"}"));

        assertThat(delegated).as("not executed while pending approval").isFalse();
        assertThat(result.returnDirect()).as("turn paused").isTrue();
        assertThat(parts.stream().map(UiMessagePart::type).toList())
                .containsSubsequence("tool-input-available", "tool-approval-request");
        UiMessagePart.ToolApprovalRequest request =
                (UiMessagePart.ToolApprovalRequest) parts.get(indexOfType(parts, "tool-approval-request"));
        assertThat(request.toolCallId()).isEqualTo("call_1");
        assertThat(request.approvalId()).isNotBlank();
    }

    @Test
    @DisplayName("a denied decision emits tool-output-denied, skips execution, and hands the model a denial")
    void denialSkipsExecution() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));

        AtomicBoolean delegated = new AtomicBoolean(false);
        ToolCallingManager delegate = delegate(delegated, toolResponse("call_1", "getWeather", "{}"));

        ToolExecutionResult result = new RecordingToolCallingManager(delegate, new ObjectMapper(), true,
                (name, input) -> true)
                .executeToolCalls(promptWith(sink, Map.of("call_1", false)),
                        chatResponseWithToolCall("call_1", "getWeather", "{}"));

        assertThat(delegated).as("denied tool not executed").isFalse();
        assertThat(result.returnDirect()).as("model continues to respond to the denial").isFalse();
        assertThat(parts.stream().map(UiMessagePart::type).toList())
                .containsSubsequence("tool-input-available", "tool-output-denied");
        assertThat(result.conversationHistory()).anyMatch(ToolResponseMessage.class::isInstance);
    }

    @Test
    @DisplayName("an approved decision executes the tool and emits tool-output-available")
    void approvalExecutes() {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));

        AtomicBoolean delegated = new AtomicBoolean(false);
        ToolCallingManager delegate = delegate(delegated, toolResponse("call_1", "getWeather", "{\"tempC\":12}"));

        new RecordingToolCallingManager(delegate, new ObjectMapper(), true, (name, input) -> true)
                .executeToolCalls(promptWith(sink, Map.of("call_1", true)),
                        chatResponseWithToolCall("call_1", "getWeather", "{}"));

        assertThat(delegated).as("approved tool executed").isTrue();
        assertThat(parts.stream().map(UiMessagePart::type).toList())
                .containsSubsequence("tool-input-available", "tool-output-available");
    }

    /** Binds a fresh sink, runs the manager over a single tool call, and returns the emitted parts. */
    private static List<UiMessagePart> recordParts(RecordingToolCallingManager manager) {
        List<UiMessagePart> parts = new ArrayList<>();
        SerializedPartSink sink = new SerializedPartSink();
        sink.bind(new UiMessageStreamWriter(parts::add));
        manager.executeToolCalls(promptWithSink(sink), chatResponseWithToolCall("call_1", "getWeather", "{}"));
        return parts;
    }

    private static ToolResponseMessage toolResponse(String id, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                .build();
    }

    private static ChatResponse chatResponseWithToolCall(String id, String name, String arguments) {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    private static Prompt promptWithSink(SerializedPartSink sink) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of(RecordingToolCallingManager.SINK_KEY, sink))
                .build();
        return new Prompt(List.of(new UserMessage("weather?")), options);
    }

    private static Prompt promptWith(SerializedPartSink sink, Map<String, Boolean> approvals) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of(
                        RecordingToolCallingManager.SINK_KEY, sink,
                        RecordingToolCallingManager.APPROVALS_KEY, approvals))
                .build();
        return new Prompt(List.of(new UserMessage("weather?")), options);
    }

    private static ToolCallingManager delegate(AtomicBoolean delegated, ToolResponseMessage toolResponse) {
        return new ToolCallingManager() {
            @Override
            public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
                return List.of();
            }

            @Override
            public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
                delegated.set(true);
                return () -> List.<Message>of(toolResponse);
            }
        };
    }

    private static int indexOfType(List<UiMessagePart> parts, String type) {
        for (int i = 0; i < parts.size(); i++) {
            if (parts.get(i).type().equals(type)) {
                return i;
            }
        }
        return -1;
    }
}
