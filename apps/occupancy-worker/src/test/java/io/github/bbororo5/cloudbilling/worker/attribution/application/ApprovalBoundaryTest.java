package io.github.bbororo5.cloudbilling.worker.attribution.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.*;
import io.github.bbororo5.cloudbilling.worker.common.TransactionRunner;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.*;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import org.junit.jupiter.api.*;

class ApprovalBoundaryTest {

  @Test
  void versionChangeRestartsInsteadOfApprovingOldEvidence() {
    doAnswer(a -> a.<Function<HistoryReader.Result, Object>>getArgument(1).apply(confirmed(8, "A")))
        .when(guard)
        .withLockedSnapshot(any(), any());
    service.runOne();
    verify(store).restart(claim);
    verify(store, never()).recordApproval(any(), any());
  }

  @Test
  void leaseLossAtPromotionIsFenced() {
    when(store.owns(claim)).thenReturn(false);
    service.runOne();
    verify(store, never()).recordApproval(any(), any());
    verify(store, never()).restart(any());
    verify(store, never()).defer(any(), any());
    verify(store, never()).monthClosed(any());
  }

  @Test
  void storedContentMismatchOpensFailure() {
    var expected = new Prepared(UUID.randomUUID(), usage, "A", occupancy, 7);
    saved.set(new Prepared(expected.revision(), usage, "B", occupancy, 7));
    var resumed = new WorkStore.Claim(usage, claim.token(), expected);
    when(store.claim(anyInt())).thenReturn(Optional.of(resumed));
    service.runOne();
    verify(store).defer(resumed, new AttributionRules.Failed("RESULT_CONFLICT"));
    verifyNoInteractions(guard);
    verify(store, never()).recordApproval(any(), any());
  }

  @Test
  void ambiguousWriteCannotApproveOrDiscardPreparation() {
    doThrow(new IllegalStateException("response lost")).when(ledger).append(any());
    assertThrows(IllegalStateException.class, service::runOne);
    verifyNoInteractions(guard);
    verify(store, never()).restart(any());
    verify(store, never()).defer(any(), any());
  }

  @Test
  void invisibleStoredResultCannotApprove() {
    when(ledger.read(any(), any())).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, service::runOne);
    verifyNoInteractions(guard);
    verify(store, never()).recordApproval(any(), any());
  }

  @Test
  void visibleRevisionIsNotAppendedAgain() {
    var prepared = new Prepared(UUID.randomUUID(), usage, "A", occupancy, 7);
    saved.set(prepared);
    when(store.claim(anyInt()))
        .thenReturn(Optional.of(new WorkStore.Claim(usage, claim.token(), prepared)));
    service.runOne();
    verify(ledger, never()).append(any());
    verify(store).recordApproval(any(), eq(prepared));
  }

  @Test
  void resumedPreparationKeepsItsRevision() {
    var prepared = new Prepared(UUID.randomUUID(), usage, "A", occupancy, 7);
    var resumed = new WorkStore.Claim(usage, claim.token(), prepared);
    when(store.claim(anyInt())).thenReturn(Optional.of(resumed));
    service.runOne();
    verifyNoInteractions(history);
    verify(store, never()).prepare(any(), any());
    verify(ledger).append(prepared);
    verify(store).recordApproval(resumed, prepared);
  }

  @Test
  void failedPreparationNeverWritesLedger() {
    when(store.prepare(any(), any())).thenReturn(false);
    assertTrue(service.runOne());
    verifyNoInteractions(ledger, guard);
  }

  @Test
  void emptyQueueStopsAtClaim() {
    when(store.claim(anyInt())).thenReturn(Optional.empty());
    assertFalse(service.runOne());
    verifyNoInteractions(history, guard, ledger);
  }

  @Test
  void incompleteHistoryPreservesWaitingOutcome() {
    when(history.lookup(any()))
        .thenReturn(new HistoryReader.NotReady(HistoryReader.Reason.AWAITING_FACTS));
    service.runOne();
    verify(store).defer(claim, new AttributionRules.Waiting("HISTORY_INCOMPLETE"));
    verifyNoInteractions(ledger, guard);
  }

  @Test
  void conflictingHistoryPreservesFailureOutcome() {
    when(history.lookup(any())).thenReturn(new HistoryReader.Conflict(UUID.randomUUID()));
    service.runOne();
    verify(store).defer(claim, new AttributionRules.Failed("HISTORY_CONFLICT"));
    verifyNoInteractions(ledger, guard);
  }

  final Instant start = Instant.parse("2026-09-01T00:00:00Z");
  final UUID occupancy = UUID.randomUUID();
  final Usage usage =
      new Usage(
          new Usage.Key("urn:vm:a", UUID.randomUUID()),
          "vm-a",
          start,
          start.plusSeconds(60),
          "r",
          "vm-a",
          "VM",
          List.of(
              new Usage.Measurement("cpu", BigInteger.ONE, "Second"),
              new Usage.Measurement("memory", BigInteger.TWO, "GiB-Second"),
              new Usage.Measurement("network", BigInteger.TEN, "Byte")));
  final WorkStore.Claim claim = new WorkStore.Claim(usage, UUID.randomUUID(), null);
  final WorkStore store = mock(WorkStore.class);
  final ResultLedger ledger = mock(ResultLedger.class);
  final HistoryReader history = mock(HistoryReader.class);
  final HistoryGuard guard = mock(HistoryGuard.class);
  final AtomicReference<Prepared> saved = new AtomicReference<>();
  final TransactionRunner tx =
      new TransactionRunner() {
        public <T> T write(Supplier<T> action) {
          return action.get();
        }

        public <T> T read(Supplier<T> action) {
          return action.get();
        }
      };
  AttributionService service;

  HistoryReader.Confirmed confirmed(long version, String account) {
    return new HistoryReader.Confirmed(
        new HistoryReader.Snapshot(
            usage.key().source(),
            usage.subject(),
            start,
            start.plusSeconds(60),
            version,
            List.of(new HistoryReader.Slice(occupancy, account, start, null))));
  }

  @BeforeEach
  void setup() {
    when(store.claim(anyInt())).thenReturn(Optional.of(claim));
    when(store.prepare(any(), any())).thenReturn(true);
    when(store.owns(any())).thenReturn(true);
    when(history.lookup(any())).thenReturn(confirmed(7, "A"));
    when(ledger.read(any(), any())).thenAnswer(a -> Optional.ofNullable(saved.get()));
    doAnswer(
            a -> {
              saved.set(a.getArgument(0));
              return null;
            })
        .when(ledger)
        .append(any());
    doAnswer(
            a ->
                ((Function<HistoryReader.Result, Object>) a.getArgument(1))
                    .apply(confirmed(7, "A")))
        .when(guard)
        .withLockedSnapshot(any(), any());
    service = new AttributionService(tx, store, history, guard, ledger);
  }

  @Test
  void approvalHasOneModulePrivateOwner() throws Exception {
    var type =
        Class.forName(
            "io.github.bbororo5.cloudbilling.worker.attribution.application.ApprovalService");
    assertFalse(java.lang.reflect.Modifier.isPublic(type.getModifiers()));
    assertTrue(java.lang.reflect.Modifier.isFinal(type.getModifiers()));
  }
}
