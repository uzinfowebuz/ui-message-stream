package uz.uzinfoweb.uimessagestream.spring;

import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.Optional;

/**
 * Resolves an inbound {@code {"type":"file","url":...,"mediaType":...}} part into a Spring AI
 * {@link Media}. Pluggable so the library stays app/transport-agnostic: how a URL maps to bytes (a
 * public CDN URL, a signed URL, an internal blob store, base64 data, ...) is an application concern.
 *
 * <p>Used by {@link UiMessageRequestAdapter}. An implementation should return {@link Optional#empty()}
 * for inputs it cannot or should not turn into media; the adapter then skips that part without failing
 * the request.
 */
@FunctionalInterface
public interface MediaResolver {

    /**
     * @param url       the file part's {@code url} (may be {@code null}/blank)
     * @param mediaType the file part's {@code mediaType}, e.g. {@code image/png} (may be {@code null}/blank)
     * @return the resolved media, or empty if it cannot/should not be resolved
     */
    Optional<Media> resolve(String url, String mediaType);

    /**
     * Default resolver: references the {@code url} as a {@link URI} (no bytes are fetched) carrying the
     * parsed {@code mediaType}. Returns empty when the url is blank, not an absolute {@code http}/
     * {@code https} URL, or the mediaType is missing or not a valid MIME type. Suitable when the
     * model/provider can fetch the URL itself (e.g. a public URL).
     *
     * <p><b>Security.</b> The url is client-controlled, so only {@code http} and {@code https} are
     * accepted — {@code file:}, {@code data:} and other schemes are rejected to keep the inbound
     * {@code file} part from referencing local or oversized in-line resources. A custom resolver that
     * actually <em>fetches</em> bytes must additionally guard against SSRF (validate the host/IP, block
     * link-local and private ranges, cap the response size).
     */
    MediaResolver DEFAULT = (url, mediaType) -> {
        if (url == null || url.isBlank() || mediaType == null || mediaType.isBlank()) {
            return Optional.empty();
        }
        MimeType mimeType;
        try {
            mimeType = MimeType.valueOf(mediaType.trim());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return Optional.empty();
        }
        return Optional.of(Media.builder().mimeType(mimeType).data(uri).build());
    };
}
