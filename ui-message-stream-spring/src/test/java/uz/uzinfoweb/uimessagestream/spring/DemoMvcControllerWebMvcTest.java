package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.uzinfoweb.uimessagestream.core.PartSerializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Servlet-transport parity tests: assert that {@link UiMessageStreamEmitter} (over a Spring MVC
 * {@code SseEmitter}) produces the exact same frames the reactive {@link DemoControllerWebTest}
 * already asserts — required header, {@code start}/{@code start-step}, {@code text-*},
 * {@code finish-step}/{@code finish}, {@code [DONE]} — including the {@code text → data → text}
 * no-merge invariant.
 *
 * <p>Assertions are made directly on the {@link MockHttpServletResponse} via AssertJ (rather than
 * Hamcrest result matchers) to keep the library's test dependencies minimal.
 */
@DisplayName("DemoMvcController over MockMvc (servlet transport)")
class DemoMvcControllerWebMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new DemoMvcController()).build();
    }

    /** Runs the request, dispatches the async result, asserts status + required header, returns the SSE body. */
    private String streamBody(String uri) throws Exception {
        MvcResult mvcResult = mockMvc.perform(get(uri)).andReturn();
        MockHttpServletResponse response = mockMvc.perform(asyncDispatch(mvcResult)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(PartSerializer.STREAM_HEADER_NAME))
                .isEqualTo(PartSerializer.STREAM_HEADER_VALUE);
        assertThat(response.getContentType()).startsWith(PartSerializer.CONTENT_TYPE);
        return response.getContentAsString();
    }

    @Test
    @DisplayName("text case: required header + streamed SSE body")
    void textCase() throws Exception {
        String body = streamBody("/demo-mvc/text");

        assertThat(body)
                .contains("{\"type\":\"start\",\"messageId\":\"demo-1\"}")
                .contains("{\"type\":\"start-step\"}")
                .contains("\"type\":\"text-start\"")
                .contains("\"type\":\"text-delta\"")
                .contains("\"delta\":\"Hello\"")
                .contains("{\"type\":\"finish-step\"}")
                .contains("{\"type\":\"finish\"}")
                .contains("[DONE]");
    }

    @Test
    @DisplayName("text + data-* case: text block is closed around the data part")
    void dataCase() throws Exception {
        String body = streamBody("/demo-mvc/data");

        assertThat(body).contains("{\"type\":\"data-artifact\",\"data\":{\"ok\":true}}");

        int firstTextEnd = body.indexOf("\"type\":\"text-end\"");
        int dataPart = body.indexOf("\"type\":\"data-artifact\"");
        int secondTextStart = body.indexOf("\"type\":\"text-start\"", dataPart);

        assertThat(firstTextEnd).as("text-end before data part").isGreaterThan(0).isLessThan(dataPart);
        assertThat(secondTextStart).as("a fresh text block opens after the data part").isGreaterThan(dataPart);
    }

    @Test
    @DisplayName("mapper case: default mapper streams consecutive text deltas, then finish/[DONE]")
    void mapperCase() throws Exception {
        String body = streamBody("/demo-mvc/mapper");

        assertThat(body)
                .contains("\"type\":\"start\"")
                .contains("\"type\":\"text-start\"")
                .contains("\"delta\":\"Hello \"")
                .contains("\"delta\":\"world\"")
                .contains("{\"type\":\"finish\"}")
                .contains("[DONE]");
    }

    @Test
    @DisplayName("upstream error becomes an error part, then the stream still ends with [DONE]")
    void errorCase() throws Exception {
        String body = streamBody("/demo-mvc/error");

        assertThat(body)
                .contains("\"delta\":\"hi\"")
                .contains("\"type\":\"error\"")
                .contains("boom")
                .contains("[DONE]");

        assertThat(body.indexOf("\"type\":\"error\"")).isLessThan(body.indexOf("[DONE]"));
    }
}
