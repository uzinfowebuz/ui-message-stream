package uz.uzinfoweb.uimessagestream.demo;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import uz.uzinfoweb.uimessagestream.spring.RecordingToolCallingManager;
import uz.uzinfoweb.uimessagestream.spring.ResponseMapper;
import uz.uzinfoweb.uimessagestream.spring.SerializedPartSink;
import uz.uzinfoweb.uimessagestream.spring.UiMessageRequest;
import uz.uzinfoweb.uimessagestream.spring.UiMessageRequestAdapter;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStream;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStreamAdvisor;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStreamEmitter;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStreamHttp;
import uz.uzinfoweb.uimessagestream.spring.UiMessageStreamResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The three ways to serve a UI Message Stream.
 *
 * <dl>
 *   <dt>{@code POST /api/chat}</dt>
 *   <dd>The main endpoint — reactive bridge ({@link UiMessageStream}), returned as a
 *       {@code Flux<ServerSentEvent>} (Spring MVC handles reactive return values). Full feature
 *       path: inbound {@link UiMessageRequest} adaptation, the {@link UiMessageStreamAdvisor}
 *       injecting the per-request sink, native tool I/O, approval decisions.</dd>
 *   <dt>{@code POST /api/chat-mvc}</dt>
 *   <dd>The same features over the servlet transport ({@link UiMessageStreamEmitter} +
 *       {@code SseEmitter}), blocking on a virtual thread. Byte-for-byte identical frames.</dd>
 *   <dt>{@code GET /api/protocol-tour}</dt>
 *   <dd>No model at all — the imperative {@code create(writer -> ...)} escape hatch emitting one of
 *       each frame kind, to see the raw protocol.</dd>
 * </dl>
 */
@RestController
class ChatController {

    private final ChatClient chatClient;
    private final ResponseMapper responseMapper;
    private final UiMessageStream uiMessageStream;
    private final UiMessageStreamEmitter uiMessageStreamEmitter;
    private final Executor chatExecutor;

    /**
     * {@code chatExecutor} resolves to Boot's {@code applicationTaskExecutor} — virtual threads,
     * because the demo sets {@code spring.threads.virtual.enabled=true}. The servlet transport
     * blocks one (virtual) thread per stream.
     */
    ChatController(ChatClient chatClient, ResponseMapper responseMapper, UiMessageStream uiMessageStream,
                   UiMessageStreamEmitter uiMessageStreamEmitter, Executor chatExecutor) {
        this.chatClient = chatClient;
        this.responseMapper = responseMapper;
        this.uiMessageStream = uiMessageStream;
        this.uiMessageStreamEmitter = uiMessageStreamEmitter;
        this.chatExecutor = chatExecutor;
    }

    @PostMapping("/api/chat")
    ResponseEntity<Flux<ServerSentEvent<String>>> chat(@RequestBody UiMessageRequest body) {
        SerializedPartSink sink = new SerializedPartSink();
        return UiMessageStreamResponse.of(uiMessageStream.from(upstream(body, sink), responseMapper, sink));
    }

    @PostMapping("/api/chat-mvc")
    SseEmitter chatMvc(@RequestBody UiMessageRequest body, HttpServletResponse response) {
        UiMessageStreamHttp.applyHeaders(response);
        SerializedPartSink sink = new SerializedPartSink();
        return uiMessageStreamEmitter.from(upstream(body, sink), responseMapper, sink, chatExecutor);
    }

    /**
     * The shared Spring AI call: adapt the {@code useChat} body to Spring AI messages, extract any
     * approve/deny decisions for the approval gate, and let {@link UiMessageStreamAdvisor} put the
     * sink where the {@code RecordingToolCallingManager} (and the tools) will find it.
     */
    private Flux<ChatClientResponse> upstream(UiMessageRequest body, SerializedPartSink sink) {
        List<org.springframework.ai.chat.messages.Message> messages =
                UiMessageRequestAdapter.toSpringAiMessages(body);
        Map<String, Boolean> decisions = UiMessageRequestAdapter.toolApprovalDecisions(body);
        return chatClient.prompt()
                .messages(messages)
                .advisors(new UiMessageStreamAdvisor(sink))
                .tools(tools -> tools.context(RecordingToolCallingManager.APPROVALS_KEY, decisions))
                .stream()
                .chatClientResponse();
    }

    @GetMapping("/api/protocol-tour")
    ResponseEntity<Flux<ServerSentEvent<String>>> protocolTour() {
        return UiMessageStreamResponse.of(uiMessageStream.create(writer -> {
            writer.start("tour-1");
            writer.reasoning("First a reasoning block — clients usually render this collapsed. ");
            writer.reasoning("Each call is a reasoning-delta frame.");
            writer.text("Now plain text. ");
            writer.text("Consecutive text() calls share one block. ");
            writer.data("chart", Map.of("title", "Sales", "points", List.of(3, 7, 4, 9)));
            writer.text("A data part closed the previous text block; this one is fresh (no merge). ");
            writer.sourceUrl("src-1", "https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol");
            writer.file("https://example.com/report.pdf", "application/pdf");
            writer.messageMetadata(Map.of("model", "scripted-demo", "demo", true));
            writer.text("That is every major frame kind — finish and [DONE] follow automatically.");
        }));
    }
}
