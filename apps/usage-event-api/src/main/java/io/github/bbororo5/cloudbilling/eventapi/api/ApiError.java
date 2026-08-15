package io.github.bbororo5.cloudbilling.eventapi.api;

record ApiError(String code, String message, String traceId) {
}
