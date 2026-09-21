package io.github.bbororo5.cloudbilling.eventapi.api;

import io.github.bbororo5.cloudbilling.eventapi.config.IngestionProperties;
import io.github.bbororo5.cloudbilling.eventapi.ingestion.IngestUsageEventService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/usage-events")
class UsageEventController {

    private final IngestUsageEventService service;
    private final IngestionProperties properties;

    UsageEventController(IngestUsageEventService service, IngestionProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping(consumes = "application/cloudevents+json")
    ResponseEntity<Void> ingest(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request
    ) throws IOException {
        if (request.getContentLengthLong() > properties.maxPayloadBytes()) {
            throw new PayloadTooLargeException();
        }
        byte[] payload = request.getInputStream().readNBytes(properties.maxPayloadBytes() + 1);
        if (payload.length > properties.maxPayloadBytes()) {
            throw new PayloadTooLargeException();
        }

        service.ingest(authorization, payload);
        return ResponseEntity.accepted().build();
    }
}
