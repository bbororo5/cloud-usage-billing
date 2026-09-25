package io.github.bbororo5.cloudbilling.worker;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.PostgresDiagnostics;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.JdbcTransactions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
@Tag("integration")
class StartupTest extends PersistenceTest {
    @Test void workerReadinessRejectsPrivilegedLogin() {
        assertDoesNotThrow(()->new PostgresDiagnostics(tx).validate());
        var owner=new JdbcTransactions(new DriverManagerDataSource(System.getenv("OCCUPANCY_TEST_URL"),"billing_owner","local-dev-only"));
        assertThrows(IllegalStateException.class,()->new PostgresDiagnostics(owner).validate());
    }
}
