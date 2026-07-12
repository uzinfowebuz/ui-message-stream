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
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An offline, deterministic Spring AI {@link ChatModel} so the demo runs with <b>zero API keys</b>.
 *
 * <p>It mirrors Spring AI 2.x provider behavior by returning tool-call responses without executing
 * them. The starter's {@code UiMessageStreamToolAdvisor} owns the tool loop, records tool frames,
 * applies the approval policy, and calls this model again with the augmented conversation.
 *
 * <p>The script is keyword-driven on the last user message:
 * <ul>
 *   <li><b>weather</b> &rarr; calls {@code getWeather} (which also pushes a {@code data-weather-card}
 *       part from inside the tool);</li>
 *   <li><b>time</b> &rarr; calls {@code getCurrentTime};</li>
 *   <li><b>transfer</b> &rarr; calls {@code transferFunds}, gated by the demo's
 *       {@code ApprovalPolicy} (the stream pauses with {@code tool-approval-request});</li>
 *   <li><b>fail</b> &rarr; calls {@code brokenTool}, producing {@code tool-output-error};</li>
 *   <li>anything else &rarr; plain multi-delta text.</li>
 * </ul>
 */
final class ScriptedChatModel implements ChatModel {

    private static final Duration DELTA_INTERVAL = Duration.ofMillis(28);
    private static final Pattern CITY = Pattern.compile("(?i)weather(?:\\s+in)?\\s+([A-Za-z]+)");

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            Message last = lastMessage(prompt);

            // Replayed approval turn: the client resent history ending in our earlier tool call.
            // Re-emit the same calls (same ids) so the advisor can match the inbound decisions.
            if (last instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                return Flux.just(response(assistant));
            }

            // Normal second half of a tool turn — summarise the result back to the user.
            if (last instanceof ToolResponseMessage toolResponse) {
                return streamText(closingReply(toolResponse));
            }

            String userText = lastUserText(prompt);
            Turn turn = Turn.forUserText(userText);

            if (turn.toolCall() == null) {
                return streamText(turn.preamble());
            }

            ChatResponse toolCallResponse = response(
                    AssistantMessage.builder().content("").toolCalls(List.of(turn.toolCall())).build());
            return streamText(turn.preamble())
                    .concatWithValues(toolCallResponse);
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // The demo only streams; a blocking call() just collapses the same script into one response.
        List<ChatResponse> all = stream(prompt).collectList().block();
        StringBuilder text = new StringBuilder();
        if (all != null) {
            for (ChatResponse response : all) {
                AssistantMessage output = response.getResults().getFirst().getOutput();
                if (output.hasToolCalls()) {
                    return response;
                }
                String delta = output.getText();
                if (delta != null) {
                    text.append(delta);
                }
            }
        }
        return response(new AssistantMessage(text.toString()));
    }

    @Override
    public ToolCallingChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
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
