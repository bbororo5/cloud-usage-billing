package io.github.bbororo5.cloudbilling.worker.occupancyhistory.api;

import java.util.function.Function;

/** Runs callback under the VM lock and one database transaction. No history mutation is exposed. */
public interface HistoryGuard {
  <T> T locked(HistoryReader.Query query, Function<HistoryReader.Result, T> work);
}
