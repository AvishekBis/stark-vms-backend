package com.starkgroup.vms.document;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.starkgroup.vms.common.exception.FieldValidationException;
import com.starkgroup.vms.common.exception.FileTooLargeException;
import com.starkgroup.vms.document.storage.StorageException;

/**
 * BR-07 upload checks: the file is present, at most 5 MB, and its extension, declared content type and
 * magic bytes all agree on PDF, JPEG or PNG. A renamed executable ("invoice.pdf" that is not a PDF) or a
 * mismatching content type is rejected. Also sanitizes the original file name, which is kept as metadata only.
 */
@Component
public class FileValidator {

    public static final long MAX_FILE_SIZE_BYTES = DataSize.ofMegabytes(5).toBytes();
    public static final int MAX_FILE_NAME_LENGTH = 255;

    static final String FILE_FIELD = "file";
    static final String FILE_REQUIRED = "A file is required.";
    static final String TYPE_NOT_ALLOWED = "Only PDF, JPG or PNG files are allowed.";

    /**
     * Unicode control characters (C0 and C1) plus the invisible bidirectional formatting marks, which can disguise
     * a name (with U+202E, "invoice[RLO]fdp.exe" is displayed as "invoiceexe.pdf").
     */
    private static final Pattern UNSAFE_CHARACTERS =
            Pattern.compile("[\\p{Cc}\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    /** The accepted file kinds with their extensions, content types and leading magic bytes. */
    enum Kind {
        PDF("application/pdf", Set.of("pdf"), Set.of("application/pdf"), new byte[] {0x25, 0x50, 0x44, 0x46}),
        JPEG("image/jpeg", Set.of("jpg", "jpeg"), Set.of("image/jpeg", "image/jpg", "image/pjpeg"),
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
        PNG("image/png", Set.of("png"), Set.of("image/png"), new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});

        private final String canonicalContentType;
        private final Set<String> extensions;
        private final Set<String> contentTypes;
        private final byte[] magic;

        Kind(String canonicalContentType, Set<String> extensions, Set<String> contentTypes, byte[] magic) {
            this.canonicalContentType = canonicalContentType;
            this.extensions = extensions;
            this.contentTypes = contentTypes;
            this.magic = magic;
        }

        static Optional<Kind> byExtension(String extension) {
            return Arrays.stream(values()).filter(kind -> kind.extensions.contains(extension)).findFirst();
        }

        boolean acceptsContentType(String contentType) {
            return contentType != null && contentTypes.contains(baseType(contentType));
        }

        boolean matchesMagic(byte[] content) {
            return content.length >= magic.length && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
        }

        String canonicalContentType() {
            return canonicalContentType;
        }
    }

    /**
     * @return the validated bytes, canonical content type and sanitized file name
     * @throws FieldValidationException for a missing, empty or disallowed file (400, field {@code file})
     * @throws FileTooLargeException    for a file above 5 MB (413)
     */
    public ValidatedFile validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FieldValidationException(FILE_FIELD, FILE_REQUIRED);
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new FileTooLargeException();
        }
        byte[] content = readBytes(file);
        return validate(file.getOriginalFilename(), file.getContentType(), content);
    }

    ValidatedFile validate(String originalFileName, String declaredContentType, byte[] content) {
        if (content.length == 0) {
            throw new FieldValidationException(FILE_FIELD, FILE_REQUIRED);
        }
        if (content.length > MAX_FILE_SIZE_BYTES) {
            throw new FileTooLargeException();
        }
        String fileName = sanitizeFileName(originalFileName);
        Kind kind = Kind.byExtension(extensionOf(fileName))
                .filter(candidate -> candidate.acceptsContentType(declaredContentType))
                .filter(candidate -> candidate.matchesMagic(content))
                .orElseThrow(() -> new FieldValidationException(FILE_FIELD, TYPE_NOT_ALLOWED));
        return new ValidatedFile(fileName, kind.canonicalContentType(), content);
    }

    /**
     * Keeps only the last path segment, removes control characters, trims, and limits the length to 255
     * characters while keeping the extension. The result is never used as a storage path.
     */
    static String sanitizeFileName(String original) {
        String name = original == null ? "" : original;
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = UNSAFE_CHARACTERS.matcher(name.substring(lastSeparator + 1)).replaceAll("").trim();
        if (List.of("", ".", "..").contains(name)) {
            return "";
        }
        // Counted in code points like the VARCHAR(255) column, and never cut through a surrogate pair.
        if (name.codePointCount(0, name.length()) <= MAX_FILE_NAME_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
        int keep = MAX_FILE_NAME_LENGTH - extension.codePointCount(0, extension.length());
        return name.substring(0, name.offsetByCodePoints(0, keep)) + extension;
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseType(String contentType) {
        int parameters = contentType.indexOf(';');
        String base = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new StorageException("Could not read the uploaded file.", ex);
        }
    }

    /** A file that passed every BR-07 check. */
    public record ValidatedFile(String fileName, String contentType, byte[] content) {

        public long size() {
            return content.length;
        }
    }
}
