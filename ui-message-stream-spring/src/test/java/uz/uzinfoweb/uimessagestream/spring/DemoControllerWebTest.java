package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import uz.uzinfoweb.uimessagestream.core.PartSerializer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DemoController over WebTestClient")
class DemoControllerWebTest {

    private final WebTestClient client = WebTestClient.bindToController(new DemoController()).build();

    @Test
    @DisplayName("text case: required headers + streamed SSE body")
    void textCase() {
        String body = client.get().uri("/demo/text").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(PartSerializer.STREAM_HEADER_NAME, "v1")
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult().getResponseBody();

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
    void dataCase() {
        String body = client.get().uri("/demo/data").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(PartSerializer.STREAM_HEADER_NAME, "v1")
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .returnResult().getResponseBody();

        assertThat(body).contains("{\"type\":\"data-artifact\",\"data\":{\"ok\":true}}");

        int firstTextEnd = body.indexOf("\"type\":\"text-end\"");
        int dataPart = body.indexOf("\"type\":\"data-artifact\"");
        int secondTextStart = body.indexOf("\"type\":\"text-start\"", dataPart);

        assertThat(firstTextEnd).as("text-end before data part").isGreaterThan(0).isLessThan(dataPart);
        assertThat(secondTextStart).as("a fresh text block opens after the data part").isGreaterThan(dataPart);
    }
}
