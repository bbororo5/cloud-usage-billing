package io.github.bbororo5.cloudbilling.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.PostgresDiagnostics;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class StartupTest extends StoreFixture {
  @Test
  void workerReadinessRejectsPrivilegedLogin() {
    assertDoesNotThrow(() -> new PostgresDiagnostics(tx).validate());
    var owner =
        new JdbcTransactions(
            new DriverManagerDataSource(
                System.getenv("OCCUPANCY_TEST_URL"), "billing_owner", "local-dev-only"));
    assertThrows(IllegalStateException.class, () -> new PostgresDiagnostics(owner).validate());
  }
}
