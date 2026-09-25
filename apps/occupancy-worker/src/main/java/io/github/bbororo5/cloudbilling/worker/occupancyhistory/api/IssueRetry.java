package io.github.bbororo5.cloudbilling.worker.occupancyhistory.api;

import java.util.UUID;

/**
 * Internal operations port. OperatorContext must be constructed by a trusted adapter, never a
 * request body.
 */
public interface IssueRetry {
  Result retry(Command command, OperatorContext operator);

  record Command(UUID requestId, UUID issueId, String reason) {}

  record OperatorContext(String principal, boolean mayRetry) {}

  sealed interface Result permits Scheduled, AlreadyScheduled, Rejected {}

  record Scheduled() implements Result {}

  record AlreadyScheduled() implements Result {}

  record Rejected(Reason reason) implements Result {}

  enum Reason {
    FORBIDDEN,
    NOT_FOUND,
    ALREADY_RESOLVED,
    INVALID_REQUEST
  }
}
