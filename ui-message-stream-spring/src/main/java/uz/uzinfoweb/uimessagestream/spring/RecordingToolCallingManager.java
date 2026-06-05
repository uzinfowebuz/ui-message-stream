package uz.uzinfoweb.uimessagestream.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Seam A — a {@link ToolCallingManager} decorator that natively surfaces tool <b>input</b> and
 * <b>output</b>.
 *
 * <p>By default Spring AI runs the tool-execution loop internally, beneath the
 * {@code chatClientResponse()} stream, so a response mapper never sees a tool's return value. The
 * {@code ToolCallingManager} is the one seam that sees a call's input (its {@code chatResponse}
 * argument) and its output (its {@link ToolExecutionResult} return value) together. This decorator
 * emits, into the per-request {@link SerializedPartSink} found in the prompt's tool context under
 * {@link #SINK_KEY}:
 * <ol>
 *   <li>{@code tool-input-available} for each tool call <em>before</em> delegating, then</li>
 *   <li>{@code tool-output-available} for each matching {@code ToolResponse} <em>after</em> delegating,
 *       paired to the call by {@code toolCallId}.</li>
 * </ol>
 *
 * <p>Replacing Spring AI's default {@code DefaultToolCallingManager} bean with this decorator wires it
 * globally with no call-site changes (see the opt-in starter auto-configuration). When no sink is
 * present in the tool context (a request that did not opt in) it is a transparent pass-through.
 *
 * <p>Pair it with a text-only response mapper (e.g. {@link ChatClientResponseMapper#TEXT_ONLY}) so tool
 * input is emitted by this manager alone and not duplicated by the mapper.
 */
public final class RecordingToolCallingManager implements ToolCallingManager {

    /** Tool-context key under which an application publishes the per-request {@link SerializedPartSink}. */
    public static final String SINK_KEY = "uimessagestream.toolSink";

    private final ToolCallingManager delegate;
    private final ObjectMapper jsonParser;

    /** Decorates {@code delegate} using an internal {@link ObjectMapper} to parse JSON arguments/results. */
    public RecordingToolCallingManager(ToolCallingManager delegate) {
        this(delegate, new ObjectMapper());
    }

    public RecordingToolCallingManager(ToolCallingManager delegate, ObjectMapper jsonParser) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.jsonParser = Objects.requireNonNull(jsonParser, "jsonParser");
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        SerializedPartSink sink = sinkFrom(prompt);

        Set<String> callIds = new LinkedHashSet<>();
        if (sink != null && chatResponse != null) {
            for (Generation generation : chatResponse.getResults()) {
                AssistantMessage output = generation.getOutput();
                if (output == null || !output.hasToolCalls()) {
                    continue;
                }
                for (AssistantMessage.ToolCall call : output.getToolCalls()) {
                    if (call.id() == null) {
                        continue;
                    }
                    callIds.add(call.id());
                    sink.toolInputAvailable(call.id(), call.name(), parse(call.arguments()));
                }
            }
        }

        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);

        // conversationHistory() is the FULL history; emit outputs only for this turn's call ids so a
        // multi-turn conversation does not re-emit prior tool responses.
        if (sink != null && result != null && !callIds.isEmpty()) {
            for (Message message : result.conversationHistory()) {
                if (message instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        if (response.id() != null && callIds.contains(response.id())) {
                            sink.toolOutputAvailable(response.id(), parse(response.responseData()));
                        }
                    }
                }
            }
        }
        return result;
    }

    private SerializedPartSink sinkFrom(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            Object candidate = toolOptions.getToolContext().get(SINK_KEY);
            if (candidate instanceof SerializedPartSink sink) {
                return sink;
            }
        }
        return null;
    }

    /** Parses a JSON string (tool arguments or a tool result) into a JSON-serializable object. */
    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return jsonParser.readValue(json, Object.class);
        } catch (Exception e) {
            // Not valid JSON (e.g. a plain-string tool result): preserve it rather than fail the stream.
            return json;
        }
    }
}
