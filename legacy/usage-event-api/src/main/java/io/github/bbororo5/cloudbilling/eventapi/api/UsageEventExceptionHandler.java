package io.github.bbororo5.cloudbilling.eventapi.api;

import com.github.f4b6a3.uuid.UuidCreator;
import io.github.bbororo5.cloudbilling.event.UsageEventValidationException;
import io.github.bbororo5.cloudbilling.event.ValidationStage;
import io.github.bbororo5.cloudbilling.eventapi.auth.ProducerAuthenticationException;
import io.github.bbororo5.cloudbilling.eventapi.ingestion.ProducerScopeException;
import io.github.bbororo5.cloudbilling.eventapi.ingestion.UsageEventPublishException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class UsageEventExceptionHandler {

    @ExceptionHandler(ProducerAuthenticationException.class)
    ResponseEntity<ApiError> authenticationFailure() {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_PRODUCER_CREDENTIAL", "Authentication failed");
    }

    @ExceptionHandler(ProducerScopeException.class)
    ResponseEntity<ApiError> scopeFailure() {
        return error(HttpStatus.FORBIDDEN, "PRODUCER_SCOPE_MISMATCH", "Access denied");
    }

    @ExceptionHandler(UsageEventValidationException.class)
    ResponseEntity<ApiError> contractFailure(UsageEventValidationException exception) {
        if (exception.stage() == ValidationStage.SEMANTIC) {
            return error(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_USAGE_EVENT", "Usage event is invalid");
        }
        return error(HttpStatus.BAD_REQUEST, "INVALID_EVENT_FORMAT", "Event format is invalid");
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ApiError> payloadTooLarge() {
        return error(HttpStatus.CONTENT_TOO_LARGE, "EVENT_PAYLOAD_TOO_LARGE", "Event payload is too large");
    }

    @ExceptionHandler(UsageEventPublishException.class)
    ResponseEntity<ApiError> publishFailure() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "EVENT_LOG_UNAVAILABLE", "Event log is unavailable");
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        ApiError body = new ApiError(code, message, UuidCreator.getTimeOrderedEpoch().toString());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
