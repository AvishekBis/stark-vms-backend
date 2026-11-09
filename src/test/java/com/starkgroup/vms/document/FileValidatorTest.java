package com.starkgroup.vms.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

import com.starkgroup.vms.common.exception.FieldValidationException;
import com.starkgroup.vms.common.exception.FileTooLargeException;

/**
 * BR-07 upload checks: extension, declared content type and magic bytes must agree; 5 MB limit; file names
 * are sanitized.
 */
class FileValidatorTest {

    static final byte[] PDF = "%PDF-1.4\n%demo\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};

    private final FileValidator validator = new FileValidator();

    @Test
    void br07_validPdfIsAccepted() {
        FileValidator.ValidatedFile file = validator.validate(
                new MockMultipartFile("file", "tax-token.pdf", "application/pdf", PDF));

        assertThat(file.contentType()).isEqualTo("application/pdf");
        assertThat(file.fileName()).isEqualTo("tax-token.pdf");
        assertThat(file.size()).isEqualTo(PDF.length);
    }

    @ParameterizedTest
    @CsvSource({"photo.png, image/png", "PHOTO.PNG, image/png"})
    void br07_validPngIsAccepted(String name, String contentType) {
        assertThat(validator.validate(new MockMultipartFile("file", name, contentType, PNG)).contentType())
                .isEqualTo("image/png");
    }

    @ParameterizedTest
    @CsvSource({"scan.jpg, image/jpeg", "scan.jpeg, image/jpeg", "scan.JPG, image/jpg"})
    void br07_validJpegIsAcceptedAndNormalizedToImageJpeg(String name, String contentType) {
        assertThat(validator.validate(new MockMultipartFile("file", name, contentType, JPEG)).contentType())
                .isEqualTo("image/jpeg");
    }

    @Test
    void br07_magicBytesThatDoNotMatchTheExtensionAreRejected() {
        byte[] executable = {0x4D, 0x5A, (byte) 0x90, 0x00};

        assertTypeNotAllowed(new MockMultipartFile("file", "invoice.pdf", "application/pdf", executable));
        assertTypeNotAllowed(new MockMultipartFile("file", "photo.png", "image/png", JPEG));
    }

    @Test
    void br07_declaredContentTypeMustMatch() {
        assertTypeNotAllowed(new MockMultipartFile("file", "tax-token.pdf", "image/png", PDF));
        assertTypeNotAllowed(new MockMultipartFile("file", "tax-token.pdf", "application/octet-stream", PDF));
        assertTypeNotAllowed(new MockMultipartFile("file", "tax-token.pdf", null, PDF));
    }

    @Test
    void br07_otherExtensionsAreRejected() {
        assertTypeNotAllowed(new MockMultipartFile("file", "report.docx", "application/pdf", PDF));
        assertTypeNotAllowed(new MockMultipartFile("file", "tax-token", "application/pdf", PDF));
        assertTypeNotAllowed(new MockMultipartFile("file", "image.gif", "image/gif", "GIF89a".getBytes()));
    }

    @Test
    void br07_missingOrEmptyFileIsRejected() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("file");
                    assertThat(ex.getMessage()).isEqualTo("A file is required.");
                });
        assertThatThrownBy(() -> validator.validate(
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void br07_fileOver5MbIsTooLarge() {
        byte[] big = new byte[(int) FileValidator.MAX_FILE_SIZE_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);

        assertThatThrownBy(() -> validator.validate(new MockMultipartFile("file", "big.pdf", "application/pdf", big)))
                .isInstanceOf(FileTooLargeException.class)
                .hasMessage("The file is larger than 5 MB.");
    }

    @Test
    void br07_fileOfExactly5MbIsAccepted() {
        byte[] max = new byte[(int) FileValidator.MAX_FILE_SIZE_BYTES];
        System.arraycopy(PDF, 0, max, 0, PDF.length);

        assertThat(validator.validate(new MockMultipartFile("file", "max.pdf", "application/pdf", max)).size())
                .isEqualTo(5 * 1024 * 1024);
    }

    @Test
    void fileNameIsSanitized() {
        assertThat(FileValidator.sanitizeFileName("C:\\Users\\me\\Desktop\\tax token.pdf")).isEqualTo("tax token.pdf");
        assertThat(FileValidator.sanitizeFileName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(FileValidator.sanitizeFileName("bad\r\nname\u0000.pdf")).isEqualTo("badname.pdf");
        assertThat(FileValidator.sanitizeFileName(null)).isEmpty();
        assertThat(FileValidator.sanitizeFileName("..")).isEmpty();

        String longName = FileValidator.sanitizeFileName("a".repeat(300) + ".pdf");
        assertThat(longName).hasSize(255).endsWith(".pdf");
    }

    @Test
    void br07_fileNameLosesC1ControlsAndBidiMarksAndIsCutOnCodePoints() {
        assertThat(FileValidator.sanitizeFileName("invoice‮fdp.exe")).isEqualTo("invoicefdp.exe");
        assertThat(FileValidator.sanitizeFileName("a\u0085b⁦c‏.pdf")).isEqualTo("abc.pdf");
        // Bangla letters and the zero-width joiner they need stay.
        assertThat(FileValidator.sanitizeFileName("ক্‍ষ.pdf")).isEqualTo("ক্‍ষ.pdf");

        String emoji = "😀";
        String longName = FileValidator.sanitizeFileName(emoji.repeat(300) + ".pdf");
        assertThat(longName.codePointCount(0, longName.length())).isEqualTo(255);
        assertThat(longName).endsWith(emoji + ".pdf");
    }

    private void assertTypeNotAllowed(MockMultipartFile file) {
        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.getField()).isEqualTo("file");
                    assertThat(ex.getMessage()).isEqualTo("Only PDF, JPG or PNG files are allowed.");
                });
    }
}
