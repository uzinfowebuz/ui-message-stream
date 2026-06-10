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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Seam A — a {@link ToolCallingManager} decorator that natively surfaces tool <b>input</b> and
 * <b>output</b>, and implements the v6 human-in-the-loop tool-approval gate.
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
 * <p>If {@code delegate.executeToolCalls} throws (the application opted into a throwing
 * {@code ToolExecutionExceptionProcessor}), a {@code tool-output-error} is emitted for each in-flight
 * call before the failure propagates. Emitted tool parts carry {@code "dynamic":true} unless disabled
 * via the constructor.
 *
 * <p><b>Approval gate (HITL).</b> An {@link ApprovalPolicy} (default {@link ApprovalPolicy#NONE}) is
 * consulted per call. The decorator treats a turn's tool batch atomically:
 * <ul>
 *   <li>if any call needs approval and has no recorded decision, a {@code tool-approval-request} is
 *       emitted for it and the turn is <em>paused</em> ({@link ToolExecutionResult#returnDirect()
 *       returnDirect=true}, nothing executed) — the stream finishes and waits for the user;</li>
 *   <li>otherwise, if any call was denied, {@code tool-output-denied} is emitted for it and a denial is
 *       fed back to the model (nothing executed) so it can respond;</li>
 *   <li>otherwise every call executes normally.</li>
 * </ul>
 * Decisions arrive on the next request and are published by the application in the tool context under
 * {@link #APPROVALS_KEY} as a {@code Map<toolCallId, Boolean>} (build it from the inbound request with
 * {@link UiMessageRequestAdapter#toolApprovalDecisions(UiMessageRequest)}). Cross-request continuity
 * (resending history, id stability) is the application's responsibility — see the README.
 *
 * <p>Replacing Spring AI's default {@code DefaultToolCallingManager} bean with this decorator wires it
 * globally with no call-site changes (see the opt-in starter auto-configuration). When no sink is
 * present in the tool context (a request that did not opt in) it is a transparent pass-through and the
 * approval gate is bypassed (there is no channel to ask the user on).
 *
 * <p>Pair it with a text-only response mapper (e.g. {@link ChatClientResponseMapper#TEXT_ONLY}) so tool
 * input is emitted by this manager alone and not duplicated by the mapper.
 */
public final class RecordingToolCallingManager implements ToolCallingManager {

    /** Tool-context key under which an application publishes the per-request {@link SerializedPartSink}. */
    public static final String SINK_KEY = "uimessagestream.toolSink";

    /** Tool-context key under which an application publishes inbound approval decisions ({@code Map<toolCallId, Boolean>}). */
    public static final String APPROVALS_KEY = "uimessagestream.toolApprovals";

    private static final String DENIED_MESSAGE = "Error: The user denied execution of this tool.";

    private final ToolCallingManager delegate;
    private final ObjectMapper jsonParser;
    private final boolean dynamic;
    private final ApprovalPolicy approvalPolicy;
    private final ErrorMessageResolver errorMessages;

    /** Decorates {@code delegate} with an internal {@link ObjectMapper}; tool parts are tagged {@code dynamic:true}; no approval gate. */
    public RecordingToolCallingManager(ToolCallingManager delegate) {
        this(delegate, new ObjectMapper(), true, ApprovalPolicy.NONE);
    }

    /** As {@link #RecordingToolCallingManager(ToolCallingManager)} but with a custom JSON parser. */
    public RecordingToolCallingManager(ToolCallingManager delegate, ObjectMapper jsonParser) {
        this(delegate, jsonParser, true, ApprovalPolicy.NONE);
    }

    /**
     * @param dynamic whether emitted tool parts carry {@code "dynamic":true} (rendered via the client's
     *                generic {@code dynamic-tool} path); {@code false} emits statically-typed tool parts
     */
    public RecordingToolCallingManager(ToolCallingManager delegate, ObjectMapper jsonParser, boolean dynamic) {
        this(delegate, jsonParser, dynamic, ApprovalPolicy.NONE);
    }

    /**
     * @param approvalPolicy decides which tool calls must be approved before execution (HITL); use
     *                       {@link ApprovalPolicy#NONE} to disable the gate
     */
    public RecordingToolCallingManager(ToolCallingManager delegate, ObjectMapper jsonParser, boolean dynamic,
                                       ApprovalPolicy approvalPolicy) {
        this(delegate, jsonParser, dynamic, approvalPolicy, ErrorMessageResolver.MASKED);
    }

    /**
     * @param errorMessages maps a thrown tool failure to the {@code errorText} streamed in
     *                      {@code tool-output-error}; the default {@link ErrorMessageResolver#MASKED}
     *                      never discloses exception internals to the client
     */
    public RecordingToolCallingManager(ToolCallingManager delegate, ObjectMapper jsonParser, boolean dynamic,
                                       ApprovalPolicy approvalPolicy, ErrorMessageResolver errorMessages) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.jsonParser = Objects.requireNonNull(jsonParser, "jsonParser");
        this.dynamic = dynamic;
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.errorMessages = Objects.requireNonNull(errorMessages, "errorMessages");
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        SerializedPartSink sink = sinkFrom(prompt);
        if (sink == null) {
            // Not opted in: transparent pass-through. Without a client channel there is nothing to gate on.
            return delegate.executeToolCalls(prompt, chatResponse);
        }

        List<AssistantMessage.ToolCall> calls = toolCalls(chatResponse);

        // Tool input is visible regardless of approval.
        for (AssistantMessage.ToolCall call : calls) {
            sink.toolInputAvailable(call.id(), call.name(), parse(call.arguments()), dynamic);
        }

        // Classify the batch against the policy and any inbound decisions (toolCallId -> approved).
        Map<String, Boolean> decisions = decisionsFrom(prompt);
        List<AssistantMessage.ToolCall> pending = new ArrayList<>();
        List<AssistantMessage.ToolCall> denied = new ArrayList<>();
        for (AssistantMessage.ToolCall call : calls) {
            Boolean decision = decisions.get(call.id());
            if (Boolean.FALSE.equals(decision)) {
                denied.add(call);
            } else if (decision == null && approvalPolicy.needsApproval(call.name(), parse(call.arguments()))) {
                pending.add(call);
            }
        }

        // Pause the turn if any call still awaits approval.
        if (!pending.isEmpty()) {
            for (AssistantMessage.ToolCall call : pending) {
                sink.toolApprovalRequest(newApprovalId(), call.id());
            }
            return ToolExecutionResult.builder()
                    .conversationHistory(historyWith(prompt, chatResponse, List.of()))
                    .returnDirect(true)
                    .build();
        }

        // Deny the turn if any call was denied: no execution, but the model is told so it can respond.
        if (!denied.isEmpty()) {
            List<ToolResponseMessage.ToolResponse> denials = new ArrayList<>();
            for (AssistantMessage.ToolCall call : denied) {
                sink.toolOutputDenied(call.id());
                denials.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), DENIED_MESSAGE));
            }
            Message denial = ToolResponseMessage.builder().responses(denials).build();
            return ToolExecutionResult.builder()
                    .conversationHistory(historyWith(prompt, chatResponse, List.of(denial)))
                    .returnDirect(false)
                    .build();
        }

        // Every call is executable: delegate, surfacing tool-output (or tool-output-error on a throw).
        Set<String> callIds = new LinkedHashSet<>();
        for (AssistantMessage.ToolCall call : calls) {
            callIds.add(call.id());
        }

        ToolExecutionResult result;
        try {
            result = delegate.executeToolCalls(prompt, chatResponse);
        } catch (RuntimeException e) {
            // A tool threw (the app opted into a throwing ToolExecutionExceptionProcessor): surface a
            // per-call tool-output-error for each in-flight call before the failure propagates upstream.
            String errorText = errorMessages.resolve(e);
            for (String id : callIds) {
                sink.toolOutputError(id, errorText, dynamic);
            }
            throw e;
        }

        // conversationHistory() is the FULL history; emit outputs only for this turn's call ids so a
        // multi-turn conversation does not re-emit prior tool responses.
        if (result != null && !callIds.isEmpty()) {
            for (Message message : result.conversationHistory()) {
                if (message instanceof ToolResponseMessage toolResponseMessage) {
                    for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                        if (response.id() != null && callIds.contains(response.id())) {
                            sink.toolOutputAvailable(response.id(), parse(response.responseData()), dynamic);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static List<AssistantMessage.ToolCall> toolCalls(ChatResponse chatResponse) {
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        if (chatResponse == null) {
            return calls;
        }
        for (Generation generation : chatResponse.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output == null || !output.hasToolCalls()) {
                continue;
            }
            for (AssistantMessage.ToolCall call : output.getToolCalls()) {
                if (call.id() != null) {
                    calls.add(call);
                }
            }
        }
        return calls;
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

    private static Map<String, Boolean> decisionsFrom(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            Object candidate = toolOptions.getToolContext().get(APPROVALS_KEY);
            if (candidate instanceof Map<?, ?> map) {
                Map<String, Boolean> decisions = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof Boolean approved) {
                        decisions.put(key, approved);
                    }
                }
                return decisions;
            }
        }
        return Map.of();
    }

    /** The prompt's messages, plus this turn's assistant message(s), plus any extra (e.g. denial) responses. */
    private static List<Message> historyWith(Prompt prompt, ChatResponse chatResponse, List<Message> extra) {
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        if (chatResponse != null) {
            for (Generation generation : chatResponse.getResults()) {
                if (generation.getOutput() != null) {
                    history.add(generation.getOutput());
                }
            }
        }
        history.addAll(extra);
        return history;
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

    private static String newApprovalId() {
        return "appr_" + UUID.randomUUID().toString().replace("-", "");
    }
}
