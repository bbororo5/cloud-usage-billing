package io.github.bbororo5.cloudbilling.worker.occupancyhistory.api;

import java.util.function.Function;

/**
 * Rereads history under the existing VM row lock and runs the callback in that transaction. The
 * callback receives Confirmed, NotReady, or Conflict; locking alone is not approval. An
 * unregistered VM has no row to lock and returns NotReady.
 *
 * <p>Callback persistence must share this guard's transaction context. Callback failure rolls back
 * its writes. No history mutation is exposed. Approval verifies remote storage before entering;
 * internal reads deliberately retain the lock during their bounded remote read.
 */
public interface HistoryGuard {
  <T> T withLockedSnapshot(HistoryReader.Query query, Function<HistoryReader.Result, T> work);
}
