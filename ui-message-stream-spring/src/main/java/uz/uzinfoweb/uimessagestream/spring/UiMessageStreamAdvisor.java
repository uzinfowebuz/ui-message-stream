package uz.uzinfoweb.uimessagestream.spring;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * Spring AI {@link StreamAdvisor} that injects the per-request {@link SerializedPartSink} into the
 * {@link ToolCallingChatOptions} tool context so a {@link RecordingToolCallingManager} (or any
 * application {@code @Tool} that needs the sink) can find it without the controller needing to call
 * {@code .toolContext(Map.of(RecordingToolCallingManager.SINK_KEY, sink))} explicitly.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SerializedPartSink sink = new SerializedPartSink();
 * Flux<ChatClientResponse> upstream = chatClient.prompt()
 *         .messages(messages)
 *         .advisors(new UiMessageStreamAdvisor(sink))   // ← injects sink into tool context
 *         .stream().chatClientResponse();
 * return UiMessageStreamResponse.of(uiMessageStream.from(upstream, ChatClientResponseMapper.TEXT_ONLY, sink));
 * }</pre>
 *
 * <p>The advisor is order-aware: it runs before {@link Advisor#DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}
 * by default so that any memory advisor that prepends messages does so after the tool context is
 * already enriched. Pass an explicit {@code order} to the two-argument constructor when a different
 * position in the chain is needed.
 *
 * <p>When the prompt's {@link ChatOptions} is not a {@link ToolCallingChatOptions} (rare in practice
 * — every {@code ChatClient} call uses {@code ToolCallingChatOptions} internally) or when no options
 * are set at all, the advisor is a no-op: no sink is injected and the request is forwarded unchanged.
 * Without a {@link ToolCallingChatOptions}, {@link RecordingToolCallingManager} already performs a
 * transparent pass-through, so nothing breaks.
 */
public final class UiMessageStreamAdvisor implements StreamAdvisor {

    private final SerializedPartSink sink;
    private final int order;

    /**
     * Creates an advisor that injects {@code sink} into every streaming request's tool context.
     * The order is {@link Advisor#DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER} − 1, placing this advisor
     * just ahead of memory advisors in the chain.
     */
    public UiMessageStreamAdvisor(SerializedPartSink sink) {
        this(sink, DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER - 1);
    }

    /**
     * @param sink  the per-request sink to inject; use the same instance when constructing the
     *              transport ({@code uiMessageStream.from(upstream, mapper, sink)})
     * @param order position in the advisor chain; lower values execute first
     */
    public UiMessageStreamAdvisor(SerializedPartSink sink, int order) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.order = order;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain chain) {
        return chain.nextStream(withSink(chatClientRequest));
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    private ChatClientRequest withSink(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        ChatOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions toolOptions)) {
            return request;
        }
        ChatOptions enriched = toolOptions.mutate()
                .toolContext(RecordingToolCallingManager.SINK_KEY, sink)
                .build();
        return request.mutate()
                .prompt(new Prompt(prompt.getInstructions(), enriched))
                .build();
    }
}
