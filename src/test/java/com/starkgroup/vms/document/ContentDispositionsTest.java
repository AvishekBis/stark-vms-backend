package com.starkgroup.vms.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guideline 13: files are served with a safe Content-Disposition, whatever the uploaded file name was.
 */
class ContentDispositionsTest {

    @Test
    void plainAsciiNameIsAQuotedFilenameOnly() {
        assertThat(ContentDispositions.of(false, "tax token.pdf")).isEqualTo("inline; filename=\"tax token.pdf\"");
        assertThat(ContentDispositions.of(true, "a.png")).isEqualTo("attachment; filename=\"a.png\"");
    }

    @Test
    void quotesAndBackslashesNeverBreakTheQuotedValue() {
        String header = ContentDispositions.of(false, "we\"ird\\name.pdf");

        assertThat(header).isEqualTo("inline; filename=\"we_ird_name.pdf\"; filename*=UTF-8''we%22ird%5Cname.pdf");
    }

    @Test
    void controlCharactersCannotInjectHeaders() {
        String header = ContentDispositions.of(true, "a\r\nSet-Cookie: x=1.pdf");

        assertThat(header).doesNotContain("\r").doesNotContain("\n")
                .startsWith("attachment; filename=\"a__Set-Cookie: x=1.pdf\"; filename*=UTF-8''a%0D%0ASet-Cookie%3A%20x%3D1.pdf");
    }

    @Test
    void unicodeNameGetsAsciiFallbackAndRfc5987Value() {
        String header = ContentDispositions.of(false, "নাম café.pdf");

        assertThat(header).isEqualTo("inline; filename=\"___ caf_.pdf\"; "
                + "filename*=UTF-8''%E0%A6%A8%E0%A6%BE%E0%A6%AE%20caf%C3%A9.pdf");
    }

    @Test
    void blankNameFallsBackToDownload() {
        assertThat(ContentDispositions.of(true, "")).isEqualTo("attachment; filename=\"download\"");
        assertThat(ContentDispositions.of(true, null)).isEqualTo("attachment; filename=\"download\"");
    }
}
