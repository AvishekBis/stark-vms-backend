package com.starkgroup.vms.document;

import java.nio.charset.StandardCharsets;

/**
 * Builds a safe {@code Content-Disposition} header for a stored file name (guideline 13, RFC 6266).
 * <ul>
 *   <li>{@code filename="..."} always carries a plain-ASCII fallback: control characters, non-ASCII
 *       characters, {@code "} and {@code \} are replaced by {@code _}, so the quoted value can never break the
 *       header (no header injection) and simple clients that only read {@code filename="..."} get a usable name.</li>
 *   <li>{@code filename*=UTF-8''...} (RFC 5987 percent-encoding) is added only when the real name differs from
 *       that fallback, so browsers still save the exact original name, e.g. with Bangla characters.</li>
 * </ul>
 */
final class ContentDispositions {

    static final String INLINE = "inline";
    static final String ATTACHMENT = "attachment";
    private static final String FALLBACK_NAME = "download";
    private static final char REPLACEMENT = '_';
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private ContentDispositions() {
    }

    static String of(boolean download, String fileName) {
        String type = download ? ATTACHMENT : INLINE;
        String name = fileName == null || fileName.isBlank() ? FALLBACK_NAME : fileName;
        String ascii = asciiFallback(name);
        StringBuilder header = new StringBuilder(type).append("; filename=\"").append(ascii).append('"');
        if (!ascii.equals(name)) {
            header.append("; filename*=UTF-8''").append(rfc5987(name));
        }
        return header.toString();
    }

    /** Printable ASCII only, without {@code "} and {@code \}. */
    static String asciiFallback(String name) {
        StringBuilder out = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> out.append(cp >= 0x20 && cp < 0x7F && cp != '"' && cp != '\\'
                ? (char) cp : REPLACEMENT));
        return out.toString();
    }

    /** RFC 5987 {@code value-chars}: attr-chars stay, every other UTF-8 byte becomes {@code %XX}. */
    static String rfc5987(String name) {
        StringBuilder out = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isAttrChar(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return out.toString();
    }

    private static boolean isAttrChar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$&+-.^_`|~".indexOf(c) >= 0;
    }
}
