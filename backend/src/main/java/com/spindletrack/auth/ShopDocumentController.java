package com.spindletrack.auth;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shops/documents")
public class ShopDocumentController {
    private final ShopRegistrationService registrations;
    public ShopDocumentController(ShopRegistrationService registrations) { this.registrations = registrations; }

    @GetMapping("/{documentId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'OWNER')")
    public ResponseEntity<InputStreamResource> open(@PathVariable String documentId,
            @AuthenticationPrincipal FirebasePrincipal principal) {
        ShopDocumentDownload download = registrations.openDocument(documentId, principal.userId(), principal.role());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.originalFilename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff").body(new InputStreamResource(download.content()));
    }
}
