package uz.uzinfoweb.uimessagestream.spring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MediaResolver.DEFAULT (URL-based)")
class MediaResolverTest {

    @Test
    @DisplayName("resolves a valid url + mediaType into Media referencing the URL as a URI")
    void resolvesValidFile() {
        Optional<Media> media = MediaResolver.DEFAULT.resolve("https://cdn.example/img.png", "image/png");

        assertThat(media).isPresent();
        assertThat(media.get().getMimeType()).isEqualTo(MimeType.valueOf("image/png"));
        assertThat(media.get().getData().toString()).isEqualTo("https://cdn.example/img.png");
    }

    @Test
    @DisplayName("trims surrounding whitespace in url and mediaType")
    void trimsInputs() {
        Optional<Media> media = MediaResolver.DEFAULT.resolve("  https://cdn.example/a.pdf  ", " application/pdf ");

        assertThat(media).isPresent();
        assertThat(media.get().getMimeType()).isEqualTo(MimeType.valueOf("application/pdf"));
        assertThat(media.get().getData().toString()).isEqualTo("https://cdn.example/a.pdf");
    }

    @Test
    @DisplayName("returns empty for null/blank url or mediaType")
    void emptyForMissingInputs() {
        assertThat(MediaResolver.DEFAULT.resolve(null, "image/png")).isEmpty();
        assertThat(MediaResolver.DEFAULT.resolve("   ", "image/png")).isEmpty();
        assertThat(MediaResolver.DEFAULT.resolve("https://cdn.example/x", null)).isEmpty();
        assertThat(MediaResolver.DEFAULT.resolve("https://cdn.example/x", "  ")).isEmpty();
    }

    @Test
    @DisplayName("returns empty for an unparseable mediaType rather than throwing")
    void emptyForBadMediaType() {
        assertThat(MediaResolver.DEFAULT.resolve("https://cdn.example/x", "not a mime type")).isEmpty();
    }

    @Test
    @DisplayName("rejects non-http(s) schemes — the url is client-controlled (SSRF/local-resource guard)")
    void rejectsNonHttpSchemes() {
        assertThat(MediaResolver.DEFAULT.resolve("file:///etc/passwd", "text/plain")).isEmpty();
        assertThat(MediaResolver.DEFAULT.resolve("data:image/png;base64,AAAA", "image/png")).isEmpty();
        assertThat(MediaResolver.DEFAULT.resolve("ftp://internal.host/x", "image/png")).isEmpty();
        assertThat(MediaResolver.DEFAULT.resolve("relative/path.png", "image/png")).isEmpty();

        assertThat(MediaResolver.DEFAULT.resolve("HTTP://cdn.example/x.png", "image/png")).isPresent();
        assertThat(MediaResolver.DEFAULT.resolve("https://cdn.example/x.png", "image/png")).isPresent();
    }
}
