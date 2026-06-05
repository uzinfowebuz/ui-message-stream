package uz.uzinfoweb.uimessagestream.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;

/**
 * Serializes a {@link UiMessagePart} to its compact, single-line JSON form and exposes the
 * protocol's transport constants.
 *
 * <p>Frames are emitted as {@code "type"} first, then the part's {@link UiMessagePart#body()}
 * fields in order, with {@code null} values omitted. The output never contains newlines, so each
 * serialized part fits in a single SSE {@code data:} frame.
 */
public final class PartSerializer {

    /** Sentinel that terminates the stream: the SDK expects a final {@code data: [DONE]} frame. */
    public static final String DONE = "[DONE]";

    /** Required response header name. The SDK is v6 but the stream header value is still {@code v1}. */
    public static final String STREAM_HEADER_NAME = "x-vercel-ai-ui-message-stream";

    /** Required response header value. */
    public static final String STREAM_HEADER_VALUE = "v1";

    /** Required response {@code Content-Type}. */
    public static final String CONTENT_TYPE = "text/event-stream";

    private final ObjectMapper objectMapper;

    /** Uses an internal, null-omitting {@link ObjectMapper}. */
    public PartSerializer() {
        this(defaultObjectMapper());
    }

    /**
     * Uses the supplied {@link ObjectMapper} as-is (so applications can register modules for their
     * own {@code data-*} payload types). Nested {@code null} handling follows that mapper's config;
     * the protocol's own part fields are already null-free by construction.
     */
    public PartSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static ObjectMapper defaultObjectMapper() {
        return JsonMapper.builder()
                .defaultPropertyInclusion(
                        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();
    }

    /** Serializes a part to compact JSON, e.g. {@code {"type":"text-delta","id":"t1","delta":"Hi"}}. */
    public String serialize(UiMessagePart part) {
        LinkedHashMap<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", part.type());
        frame.putAll(part.body());
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize UI message part: " + part.type(), e);
        }
    }
}
