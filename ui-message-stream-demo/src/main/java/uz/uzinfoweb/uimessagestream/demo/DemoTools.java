package uz.uzinfoweb.uimessagestream.demo;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import uz.uzinfoweb.uimessagestream.spring.RecordingToolCallingManager;
import uz.uzinfoweb.uimessagestream.spring.SerializedPartSink;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The demo's Spring AI {@code @Tool} methods. With {@code uimessagestream.tool-io.native=true} every
 * call's input and output is streamed natively as {@code tool-input-available} /
 * {@code tool-output-available} frames by the library's {@code RecordingToolCallingManager} — none of
 * these methods writes those frames itself.
 *
 * <p>{@link #getWeather} additionally shows the one thing a tool <em>can</em> write directly: a custom
 * {@code data-*} part, pushed through the per-request {@link SerializedPartSink} found in the
 * {@link ToolContext}. The sink serializes it safely between the text deltas streaming on another
 * thread — that is the library's "custom application data from inside a tool" feature.
 *
 * <p>{@link #transferFunds} is gated by the demo's {@code ApprovalPolicy} (human-in-the-loop) and
 * {@link #brokenTool} always throws to demonstrate {@code tool-output-error} with the masked
 * error message.
 */
class DemoTools {

    @Tool(description = "Get the current weather for a city")
    public Map<String, Object> getWeather(@ToolParam(description = "The city name") String city,
                                          ToolContext toolContext) {
        int temperature = 18 + Math.floorMod(city == null ? 0 : city.hashCode(), 14);
        String condition = temperature > 25 ? "sunny" : "partly cloudy";

        // Custom data-* part pushed from INSIDE a tool: the bundled page renders this as a card.
        sinkFrom(toolContext).data("weather-card", Map.of(
                "city", city == null ? "?" : city,
                "temperatureC", temperature,
                "condition", condition,
                "source", "scripted-demo"));

        return Map.of("city", city == null ? "?" : city,
                "temperatureC", temperature,
                "condition", condition);
    }

    @Tool(description = "Get the current date and time in a given time zone")
    public String getCurrentTime(@ToolParam(description = "An IANA zone id, e.g. Asia/Tashkent") String zoneId) {
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (RuntimeException e) {
            zone = ZoneId.of("UTC");
        }
        return ZonedDateTime.now(zone).format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    @Tool(description = "Transfer money to a recipient. Sensitive: requires human approval.")
    public String transferFunds(@ToolParam(description = "Recipient account") String recipient,
                                @ToolParam(description = "Amount in USD") double amountUsd) {
        return "Transferred $" + amountUsd + " to " + recipient + " (demo — nothing real happened)";
    }

    @Tool(description = "A tool that always fails, to demonstrate tool-output-error")
    public String brokenTool() {
        // The raw message never reaches the browser: ErrorMessageResolver.MASKED is the default.
        // Flip uimessagestream.errors.include-message=true in application.yaml to see it leak.
        throw new IllegalStateException(
                "connection refused: db-internal-host:5432 (secret internals the client must not see)");
    }

    /**
     * The per-request sink the transport bound and the {@code UiMessageStreamAdvisor} injected into
     * the tool context. Falls back to a no-op (unbound) sink so the tool also works when a caller
     * didn't opt in.
     */
    private static SerializedPartSink sinkFrom(ToolContext toolContext) {
        if (toolContext != null
                && toolContext.getContext().get(RecordingToolCallingManager.SINK_KEY)
                        instanceof SerializedPartSink sink) {
            return sink;
        }
        return new SerializedPartSink(); // unbound: writes are dropped, the tool still works
    }
}
