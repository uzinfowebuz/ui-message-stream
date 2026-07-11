package uz.uzinfoweb.uimessagestream.demo;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An offline, deterministic Spring AI {@link ChatModel} so the demo runs with <b>zero API keys</b>.
 *
 * <p>It faithfully mirrors how real provider implementations (OpenAI, Gemini, ...) drive the
 * tool-calling loop in Spring AI 2.x: when a response carries tool calls and internal tool execution
 * is enabled, the model itself invokes {@link ToolCallingManager#executeToolCalls} and then re-calls
 * itself with the augmented conversation. Because the demo wires
 * {@code uimessagestream.tool-io.native=true}, the manager bean injected here is the library's
 * {@code RecordingToolCallingManager} decorator — so tool input/output frames and the
 * human-in-the-loop approval gate behave exactly as they would against a real provider.
 *
 * <p>The script is keyword-driven on the last user message:
 * <ul>
 *   <li><b>weather</b> &rarr; calls {@code getWeather} (which also pushes a {@code data-weather-card}
 *       part from inside the tool);</li>
 *   <li><b>time</b> &rarr; calls {@code getCurrentTime};</li>
 *   <li><b>transfer</b> &rarr; calls {@code transferFunds}, gated by the demo's
 *       {@code ApprovalPolicy} (the stream pauses with {@code tool-approval-request});</li>
 *   <li><b>fail</b> &rarr; calls {@code brokenTool}, which throws (&rarr; {@code tool-output-error}
 *       with the masked message);</li>
 *   <li>anything else &rarr; a plain streamed text reply.</li>
 * </ul>
 *
 * <p>Two non-user turn shapes complete the loop:
 * <ul>
 *   <li>history ending in an {@link AssistantMessage} with tool calls (the client replayed an
 *       approval turn): the same calls are re-emitted with their original ids so the manager can
 *       match the user's approve/deny decisions;</li>
 *   <li>history ending in a {@link ToolResponseMessage} (a tool just ran): a closing text reply
 *       summarising the tool result is streamed.</li>
 * </ul>
 */
final class ScriptedChatModel implements ChatModel {

    private static final Pattern CITY = Pattern.compile("\\b(?:in|for)\\s+([A-Z][\\p{L}-]+)");
    private static final Duration DELTA_INTERVAL = Duration.ofMillis(40);

    private final ToolCallingManager toolCallingManager;

    ScriptedChatModel(ToolCallingManager toolCallingManager) {
        this.toolCallingManager = Objects.requireNonNull(toolCallingManager, "toolCallingManager");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            Message last = lastMessage(prompt);

            // Replayed approval turn: the client resent history ending in our earlier tool call.
            // Re-emit the same calls (same ids) so the manager can match the inbound decisions.
            if (last instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                return executeToolsAndContinue(prompt, response(assistant));
            }

            // A tool just executed (or was denied): stream the closing answer.
            if (last instanceof ToolResponseMessage toolResponse) {
                return streamText(closingReply(toolResponse));
            }

            Turn turn = Turn.forUserText(lastUserText(prompt));
            if (turn.toolCall() == null) {
                return streamText(turn.preamble());
            }
            ChatResponse toolCallResponse = response(
                    AssistantMessage.builder().content("").toolCalls(List.of(turn.toolCall())).build());
            return streamText(turn.preamble())
                    .concatWith(Flux.defer(() -> executeToolsAndContinue(prompt, toolCallResponse)));
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // The demo only streams; a blocking call() just collapses the same script into one response.
        List<ChatResponse> all = stream(prompt).collectList().block();
        StringBuilder text = new StringBuilder();
        if (all != null) {
            for (ChatResponse response : all) {
                String delta = response.getResults().getFirst().getOutput().getText();
                if (delta != null) {
                    text.append(delta);
                }
            }
        }
        return response(new AssistantMessage(text.toString()));
    }

    /**
     * The provider-side half of Spring AI's tool loop: hand the tool-call response to the
     * {@link ToolCallingManager} (here: the recording decorator), then either stop (approval pending,
     * {@code returnDirect}) or recurse with the augmented history. The tool-call response itself is
     * not forwarded downstream — same as the real provider implementations.
     */
    private Flux<ChatResponse> executeToolsAndContinue(Prompt prompt, ChatResponse toolCallResponse) {
        ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, toolCallResponse);
        if (result.returnDirect()) {
            // Approval pending: the manager already emitted tool-approval-request; end this turn.
            return Flux.empty();
        }
        return stream(new Prompt(result.conversationHistory(), prompt.getOptions()));
    }

    /** Streams {@code text} as word-sized {@link ChatResponse} deltas, like a real provider would. */
    private static Flux<ChatResponse> streamText(String text) {
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        String[] words = text.split("(?<= )");
        return Flux.fromArray(words)
                .delayElements(DELTA_INTERVAL)
                .map(word -> response(new AssistantMessage(word)));
    }

    private static String closingReply(ToolResponseMessage toolResponse) {
        ToolResponseMessage.ToolResponse first = toolResponse.getResponses().getFirst();
        String data = first.responseData();
        if (data != null && data.contains("denied execution")) {
            return "Understood — I did not run \"" + first.name() + "\" since you denied it. "
                    + "Anything else I can help with?";
        }
        return "Done! The \"" + first.name() + "\" tool returned: " + data
                + " — and that completes this turn.";
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static Message lastMessage(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        return messages.isEmpty() ? null : messages.getLast();
    }

    private static String lastUserText(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user && user.getText() != null) {
                return user.getText();
            }
        }
        return "";
    }

    /** One scripted model turn: a streamed preamble and (optionally) a tool call to make. */
    private record Turn(String preamble, AssistantMessage.ToolCall toolCall) {

        static Turn forUserText(String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("weather")) {
                return new Turn("Let me look up the current weather for you. ",
                        call("getWeather", "{\"city\":\"" + cityFrom(text) + "\"}"));
            }
            if (lower.contains("time")) {
                return new Turn("Checking the clock. ",
                        call("getCurrentTime", "{\"zoneId\":\"Asia/Tashkent\"}"));
            }
            if (lower.contains("transfer")) {
                return new Turn("This one needs your approval first. ",
                        call("transferFunds", "{\"recipient\":\"acme-corp\",\"amountUsd\":2500}"));
            }
            if (lower.contains("fail") || lower.contains("crash")) {
                return new Turn("Calling a tool that is about to fail, watch the error frame. ",
                        call("brokenTool", "{}"));
            }
            return new Turn(
                    "Hi! I am a scripted offline model demonstrating the UI Message Stream protocol. "
                    + "Each word you see arrived as its own text-delta frame over SSE. Try the magic "
                    + "words: \"weather in Samarkand\" (tool + custom data part), \"what time is it\" "
                    + "(plain tool), \"transfer money\" (human-in-the-loop approval), or \"please fail\" "
                    + "(tool error with masked message).",
                    null);
        }

        private static AssistantMessage.ToolCall call(String name, String jsonArguments) {
            String id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            return new AssistantMessage.ToolCall(id, "function", name, jsonArguments);
        }

        private static String cityFrom(String text) {
            Matcher matcher = CITY.matcher(text);
            return matcher.find() ? matcher.group(1) : "Tashkent";
        }
    }
}
