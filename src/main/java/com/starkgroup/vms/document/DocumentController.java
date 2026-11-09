package com.starkgroup.vms.document;

import java.net.URI;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.starkgroup.vms.common.dto.PageResponse;
import com.starkgroup.vms.common.expiry.ExpiryCalculator;
import com.starkgroup.vms.common.util.PageRequests;
import com.starkgroup.vms.document.dto.DocumentFile;
import com.starkgroup.vms.document.dto.DocumentResponse;
import com.starkgroup.vms.document.dto.DocumentUploadRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * HTTP mapping only; every rule lives in {@link DocumentService}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Documents", description = "Vehicle documents and expiry tracking (FR-D01, FR-D02, BR-07, BR-08)")
public class DocumentController {

    static final String DOCUMENTS_PATH = "/api/v1/documents";
    static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/vehicles/{vehicleId}/documents")
    @Operation(summary = "All documents of a vehicle, soonest expiry first (array, not paged)")
    public List<DocumentResponse> list(@PathVariable Long vehicleId) {
        return documentService.findByVehicle(vehicleId);
    }

    @PostMapping(path = "/vehicles/{vehicleId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a document (multipart: file, documentType, documentNumber, issueDate, expiryDate)",
            description = "PDF, JPG or PNG up to 5 MB; extension, content type and file signature must agree. "
                    + "The file is stored under a random key; the original name is kept as metadata only.")
    @ApiResponse(responseCode = "201", description = "Created",
            headers = @Header(name = "Location", description = "URL of the new document"))
    @ApiResponse(responseCode = "400", description = "Validation failed (field file, documentType, expiryDate ...)")
    @ApiResponse(responseCode = "413", description = "file-too-large: the file is larger than 5 MB")
    public ResponseEntity<DocumentResponse> upload(@PathVariable Long vehicleId,
                                                   @Valid @ModelAttribute DocumentUploadRequest request) {
        DocumentResponse created = documentService.upload(vehicleId, request);
        return ResponseEntity.created(URI.create(DOCUMENTS_PATH + "/" + created.id())).body(created);
    }

    @GetMapping("/documents/expiring")
    @Operation(summary = "Expired and expiring documents (expiryDate <= today + withinDays), soonest first")
    public PageResponse<DocumentResponse> expiring(
            @Parameter(description = "0-365, default 30")
            @RequestParam(defaultValue = "" + ExpiryCalculator.EXPIRING_SOON_DAYS)
            @Min(value = 0, message = "withinDays must be between 0 and 365.")
            @Max(value = 365, message = "withinDays must be between 0 and 365.") int withinDays,
            @Parameter(description = "0-based page index")
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "Page must be 0 or greater.") int page,
            @Parameter(description = "Page size, default 20, capped at 100")
            @RequestParam(defaultValue = "" + PageRequests.DEFAULT_PAGE_SIZE)
            @Min(value = 1, message = "Size must be 1 or greater.") int size) {
        return documentService.findExpiring(withinDays, page, size);
    }

    @GetMapping("/documents/{documentId}")
    @Operation(summary = "Get one document's metadata incl. expiry status")
    public DocumentResponse get(@PathVariable Long documentId) {
        return documentService.findById(documentId);
    }

    @GetMapping("/documents/{documentId}/file")
    @Operation(summary = "Download or view the file (Bearer token required)",
            description = "Content-Disposition is inline by default, attachment with download=true.")
    @ApiResponse(responseCode = "200", description = "The file bytes with the stored content type")
    @ApiResponse(responseCode = "503", description = "Storage unavailable (the file storage cannot be reached)")
    public ResponseEntity<byte[]> file(@PathVariable Long documentId,
                                       @Parameter(description = "true = attachment (save), false = inline (view)")
                                       @RequestParam(defaultValue = "false") boolean download) {
        DocumentFile file = documentService.loadFile(documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                // ASCII-safe quoted filename, plus RFC 5987 filename* when the original name needs it.
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.of(download, file.fileName()))
                .header(NOSNIFF_HEADER, "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(file.content());
    }

    @DeleteMapping("/documents/{documentId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a document and its stored file (ADMIN only)")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "403", description = "STAFF is not allowed")
    public ResponseEntity<Void> delete(@PathVariable Long documentId) {
        documentService.delete(documentId);
        return ResponseEntity.noContent().build();
    }
}
