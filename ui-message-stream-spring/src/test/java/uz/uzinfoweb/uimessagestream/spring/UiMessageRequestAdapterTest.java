package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.MimeType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UiMessageRequestAdapter")
class UiMessageRequestAdapterTest {

    @Test
    @DisplayName("attaches Media resolved from a file part to the UserMessage (default resolver)")
    void attachesMediaFromFilePart() {
        UiMessageRequest request = new UiMessageRequest(List.of(new UiMessageRequest.Message(
                "m1", "user",
                List.of(
                        new UiMessageRequest.Part("text", "look at this", null, null),
                        new UiMessageRequest.Part("file", null, "https://cdn.example/img.png", "image/png")))));

        List<Message> messages = UiMessageRequestAdapter.toSpringAiMessages(request);

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
        UserMessage userMessage = (UserMessage) messages.getFirst();
        assertThat(userMessage.getText()).isEqualTo("look at this");
        assertThat(userMessage.getMedia()).hasSize(1);
        assertThat(userMessage.getMedia().getFirst().getMimeType()).isEqualTo(MimeType.valueOf("image/png"));
        assertThat(userMessage.getMedia().getFirst().getData().toString()).isEqualTo("https://cdn.example/img.png");
    }

    @Test
    @DisplayName("skips an unresolvable file part without failing the request")
    void skipsUnresolvableFile() {
        MediaResolver neverResolves = (url, mediaType) -> Optional.empty();
        UiMessageRequest request = new UiMessageRequest(List.of(new UiMessageRequest.Message(
                "m1", "user",
                List.of(
                        new UiMessageRequest.Part("text", "hi", null, null),
                        new UiMessageRequest.Part("file", null, "blob://unknown", "application/octet-stream")))));

        List<Message> messages = UiMessageRequestAdapter.toSpringAiMessages(request, neverResolves);

        assertThat(messages).hasSize(1);
        UserMessage userMessage = (UserMessage) messages.getFirst();
        assertThat(userMessage.getText()).isEqualTo("hi");
        assertThat(userMessage.getMedia()).isEmpty();
    }

    @Test
    @DisplayName("maps roles to the matching Spring AI message types and concatenates text")
    void mapsRoles() {
        UiMessageRequest request = new UiMessageRequest(List.of(
                new UiMessageRequest.Message("s", "system", List.of(new UiMessageRequest.Part("text", "be brief", null, null))),
                new UiMessageRequest.Message("u", "user", List.of(
                        new UiMessageRequest.Part("text", "Hello ", null, null),
                        new UiMessageRequest.Part("text", "world", null, null))),
                new UiMessageRequest.Message("a", "assistant", List.of(new UiMessageRequest.Part("text", "hi", null, null)))));

        List<Message> messages = UiMessageRequestAdapter.toSpringAiMessages(request);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("Hello world");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Test
    @DisplayName("a null request yields an empty message list")
    void nullRequest() {
        assertThat(UiMessageRequestAdapter.toSpringAiMessages(null)).isEmpty();
    }
}
