package uz.uzinfoweb.uimessagestream.spring;

import org.springframework.ai.chat.client.ChatClientResponse;
import uz.uzinfoweb.uimessagestream.core.UiMessageStreamWriter;

import java.util.function.BiConsumer;

/**
 * Maps a single upstream {@link ChatClientResponse} element onto protocol parts by driving the
 * supplied {@link UiMessageStreamWriter}.
 *
 * <p>This is the one and only application extension point on the bridge: an app customizes the
 * stream by providing its own mapper (and/or its own {@code data-*} payloads), never by adding
 * app-specific methods to the library.
 */
@FunctionalInterface
public interface ResponseMapper extends BiConsumer<ChatClientResponse, UiMessageStreamWriter> {
}
