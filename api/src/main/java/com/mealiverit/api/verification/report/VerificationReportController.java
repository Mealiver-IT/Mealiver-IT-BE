package com.mealiverit.api.verification.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.file.Path;

@RestController
@RequestMapping("/reports")
public class VerificationReportController {

    private final Path reportDirectory;

    public VerificationReportController(
            @Value("${verification.report.directory:./reports}")
            String reportDirectory
    ) {

        this.reportDirectory =
                Path.of(reportDirectory)
                        .toAbsolutePath()
                        .normalize();
    }

    @GetMapping(
            value = "/{fileName:.+}",
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<Resource> getReport(
            @PathVariable String fileName
    ) throws MalformedURLException {

        Path file =
                reportDirectory
                        .resolve(fileName)
                        .normalize();

        /*
         * Path Traversal 방지
         */
        if (!file.startsWith(reportDirectory)) {
            return ResponseEntity.badRequest()
                    .build();
        }

        Resource resource =
                new UrlResource(
                        file.toUri()
                );

        if (!resource.exists()
                || !resource.isReadable()) {

            return ResponseEntity
                    .notFound()
                    .build();
        }

        return ResponseEntity.ok()
                .contentType(
                        MediaType.TEXT_HTML
                )
                .body(resource);
    }
}