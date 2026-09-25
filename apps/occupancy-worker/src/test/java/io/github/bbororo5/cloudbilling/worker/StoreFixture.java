package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.domain.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

abstract class StoreFixture {
  final String source = "urn:test:" + UUID.randomUUID();
  final Instant t = Instant.parse("2026-09-01T10:00:00Z");
  JdbcTransactions tx;
  PostgresHistoryStore store;
  ReceiptService receipt;
  ApplyService apply;

  @BeforeEach
  void setup() throws Exception {
    String url =
        Objects.requireNonNull(System.getenv("OCCUPANCY_TEST_URL"), "Use verify-occupancy.sh");
    tx =
        new JdbcTransactions(
            new DriverManagerDataSource(url, "billing_occupancy", "local-dev-only"));
    store = new PostgresHistoryStore(tx);
    receipt = new ReceiptService(tx, store);
    apply = new ApplyService(tx, store);
    try (var c = DriverManager.getConnection(url, "billing_owner", "local-dev-only");
        var s = c.createStatement()) {
      s.execute(
          "insert into billing.billing_account(billing_account_id,billing_account_name)"
              + " values('x','X') on conflict do nothing");
    }
  }

  Event event(String vm, long seq, Event.Kind kind, UUID occupancy, String account) {
    return new Event(
        vm,
        "instances/a",
        UUID.randomUUID(),
        kind,
        seq,
        t.plusSeconds(seq * 60),
        occupancy,
        account,
        kind == Event.Kind.INITIALIZED ? t : null,
        kind == Event.Kind.CONFIRMED ? t.plusSeconds(seq * 60) : null,
        null);
  }

  void receive(Event event, long offset) {
    receipt.receive(
        new ReceiptStore.Input(
            new ReceiptStore.Record(
                source,
                0,
                offset,
                event.source().getBytes(),
                List.of(),
                ("raw-" + offset).getBytes()),
            event,
            null));
  }

  long number(String query) {
    return tx.read(
        () -> {
          try (var s = tx.connection().createStatement();
              var r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }
}
