package uz.uzinfoweb.uimessagestream.spring;

/**
 * Maps a server-side failure to the {@code errorText} streamed to the client in {@code error} /
 * {@code tool-output-error} frames.
 *
 * <p>The default everywhere is {@link #MASKED}: exception messages routinely carry internals
 * (hostnames, file paths, SQL fragments, provider error bodies, even credentials embedded in
 * connection-string URLs), so they are never sent to the browser unless an application opts in.
 * This mirrors the AI SDK itself, which masks errors by default and discloses them only via an
 * explicit {@code onError} callback.
 *
 * <p>Pass a resolver to {@link UiMessageStream}, {@link UiMessageStreamEmitter} or
 * {@link RecordingToolCallingManager} to customize — {@link #MESSAGE} restores raw
 * {@code getMessage()} disclosure, or supply your own (e.g. map known exception types to
 * user-facing strings and mask the rest).
 */
@FunctionalInterface
public interface ErrorMessageResolver {

    /** @return the {@code errorText} to stream for this failure (never {@code null}) */
    String resolve(Throwable error);

    /** The safe default: a generic message, no internals disclosed. */
    ErrorMessageResolver MASKED = error -> "An error occurred.";

    /** Opt-in disclosure: the exception's message (or its class name when the message is null). */
    ErrorMessageResolver MESSAGE = error -> {
        String message = error.getMessage();
        return message != null ? message : error.getClass().getSimpleName();
    };
}
