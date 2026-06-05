package uz.uzinfoweb.uimessagestream.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.List;
import java.util.Map;

/**
 * The default {@link ResponseMapper} for a Spring AI {@code ChatClient} response stream.
 *
 * <p>Grounded in the real Spring AI 2.0.0-M8 API:
 * <pre>{@code chatClientResponse.chatResponse().getResults().get(0).getOutput()}</pre>
 * yields an {@link AssistantMessage}, from which we read {@link AssistantMessage#getText()} and
 * {@link AssistantMessage#getToolCalls()} ({@code ToolCall.id()/name()/arguments()}).
 *
 * <p>Mapping:
 * <ul>
 *   <li>Non-empty text deltas &rarr; {@link UiMessageStreamWriter#text(String)}.</li>
 *   <li>Native tool calls &rarr; {@code tool-input-available} (the call's JSON {@code arguments}
 *       are parsed into an object so {@code input} is a JSON object, not a string).</li>
 * </ul>
 *
 * <p><b>Stubbed / intentionally omitted.</b> Tool <em>outputs</em> are executed inside Spring AI's
 * tool-calling machinery and are not surfaced as a distinct element on the
 * {@code chatClientResponse()} stream, so this mapper does not emit {@code tool-output-available};
 * apps that drive tools manually can emit it via the writer. The mapper is stateless, so it assumes
 * a provider (e.g. Google GenAI / Gemini) that delivers each tool call as one complete unit;
 * providers that stream partial argument deltas should supply a custom mapper.
 */
public final class ChatClientResponseMapper {

    private static final ObjectMapper ARGUMENT_PARSER = new ObjectMapper();

    /** The shared, stateless default mapper (text + {@code tool-input-available}). */
    public static final ResponseMapper DEFAULT = (response, writer) -> apply(response, writer, true);

    /**
     * A text-only mapper that emits text deltas but <em>not</em> {@code tool-input-available}. Pair it
     * with {@link RecordingToolCallingManager} (native tool I/O): the manager emits tool input + output
     * natively, so the mapper must not also emit tool input or it would be duplicated.
     */
    public static final ResponseMapper TEXT_ONLY = (response, writer) -> apply(response, writer, false);

    private ChatClientResponseMapper() {
    }

    private static void apply(ChatClientResponse response, UiMessageStreamWriter writer, boolean emitToolInput) {
        if (response == null) {
            return;
        }
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return;
        }
        List<Generation> results = chatResponse.getResults();
        if (results == null || results.isEmpty()) {
            return;
        }
        AssistantMessage message = results.getFirst().getOutput();
        if (message == null) {
            return;
        }

        String text = message.getText();
        if (text != null && !text.isEmpty()) {
            writer.text(text);
        }

        if (emitToolInput && message.hasToolCalls()) {
            for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                if (call.id() == null) {
                    continue;
                }
                writer.toolInputAvailable(call.id(), call.name(), parseArguments(call.arguments()));
            }
        }
    }

    /** Parses a tool call's JSON {@code arguments} string into a JSON-serializable object. */
    private static Object parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return ARGUMENT_PARSER.readValue(arguments, Object.class);
        } catch (Exception e) {
            // Provider sent something that isn't valid JSON; preserve it rather than fail the stream.
            return Map.of("_raw", arguments);
        }
    }
}
