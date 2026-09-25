package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres.PostgresWorkStore;
import io.github.bbororo5.cloudbilling.worker.attribution.domain.*;
import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("attribution")
class AttributionStoreTest {
  JdbcTransactions tx; PostgresWorkStore store;
  @BeforeEach void setup() {
    tx=new JdbcTransactions(new DriverManagerDataSource(System.getenv("OCCUPANCY_TEST_URL"),"billing_attribution","local-dev-only"));
    store=new PostgresWorkStore(tx);
  }
  @Test void registrationAndCursorAreAtomicAndIdempotent() {
    var u=new AttributionRulesTest().usage();
    var sweep=tx.read(store::sweep);
    assertTrue(tx.write(() -> store.beginSweep(sweep,u.key())));
    var started=tx.read(store::sweep);
    assertThrows(IllegalStateException.class,() -> tx.write(() -> {
      store.register(started,List.of(u)); throw new IllegalStateException("crash before commit");
    }));
    assertEquals(started,tx.read(store::sweep));
    assertEquals(1,tx.write(() -> store.register(started,List.of(u))));
    assertEquals(-1,tx.write(() -> store.register(started,List.of(u))));
    var next=tx.read(store::sweep);
    assertEquals(u.key(),next.after());
    assertEquals(0,tx.write(() -> store.register(next,List.of())));
    assertNull(tx.read(store::sweep).upper());
  }
  @Test void attributionAccountCannotModifyHistoryOrAttempts() {
    assertThrows(RuntimeException.class,() -> execute("update billing.occupancy_stream set version=version"));
    assertThrows(RuntimeException.class,() -> execute("delete from billing.attribution_attempt"));
  }
  void execute(String sql) { tx.write(() -> { try(var s=tx.connection().createStatement()) { s.execute(sql); return null; } catch(SQLException e) { throw new RuntimeException(e); } }); }
}
