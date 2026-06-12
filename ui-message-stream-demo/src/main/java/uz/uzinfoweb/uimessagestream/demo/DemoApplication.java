package uz.uzinfoweb.uimessagestream.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runnable showcase for the ui-message-stream library. Start it and open
 * <a href="http://localhost:8080">http://localhost:8080</a> — the bundled page is a miniature
 * {@code useChat}-style client that renders the protocol frames live (chat on the left, the raw
 * SSE frames on the right).
 *
 * <p>No API key is needed: {@link ScriptedChatModel} is an offline Spring AI {@code ChatModel}
 * that streams scripted replies and triggers real tool execution through Spring AI's
 * {@code ToolCallingManager}, so every library feature behaves exactly as it would against a real
 * provider.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
