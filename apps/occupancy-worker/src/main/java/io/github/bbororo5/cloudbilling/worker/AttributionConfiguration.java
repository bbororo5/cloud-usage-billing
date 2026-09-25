package io.github.bbororo5.cloudbilling.worker;

import com.zaxxer.hikari.*;
import io.github.bbororo5.cloudbilling.worker.attribution.adapter.clickhouse.ClickHouseLedger;
import io.github.bbororo5.cloudbilling.worker.attribution.adapter.postgres.*;
import io.github.bbororo5.cloudbilling.worker.attribution.api.*;
import io.github.bbororo5.cloudbilling.worker.attribution.application.*;
import io.github.bbororo5.cloudbilling.worker.attribution.port.AlertChannel;
import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.PostgresHistoryGuard;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.HistoryReader;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name = "attribution.enabled", havingValue = "true")
public class AttributionConfiguration {
  public record Services(
      HikariDataSource pool,
      DiscoveryService discovery,
      AttributionService processing,
      OperationsService operations,
      ApprovedReader reader,
      PostgresAttributionDiagnostics diagnostics)
      implements AutoCloseable {
    public void close() {
      pool.close();
    }
  }

  @Bean
  @ConditionalOnMissingBean(AlertChannel.class)
  AlertChannel attributionAlertChannel() {
    return alert -> {
      throw new IllegalStateException("External attribution alert channel not configured");
    };
  }

  @Bean(destroyMethod = "close")
  Services attributionServices(
      HistoryReader history,
      AlertChannel alerts,
      @Value("${attribution.db-url}") String url,
      @Value("${attribution.db-password}") String password,
      @Value("${attribution.clickhouse-url}") URI ch,
      @Value("${attribution.clickhouse-password}") String chPassword) {
    var config = new HikariConfig();
    config.setJdbcUrl(url);
    config.setUsername("billing_attribution");
    config.setPassword(password);
    config.setMaximumPoolSize(3);
    config.setConnectionTimeout(5000);
    config.setPoolName("attribution");
    var pool = new HikariDataSource(config);
    try {
      var tx = new JdbcTransactions(pool);
      var store = new PostgresWorkStore(tx);
      var guard = new PostgresHistoryGuard(tx);
      var ledger = new ClickHouseLedger(ch, "billing_attribution", chPassword);
      var diagnostics = new PostgresAttributionDiagnostics(tx);
      diagnostics.validate();
      return new Services(
          pool,
          new DiscoveryService(tx, store, ledger),
          new AttributionService(tx, store, history, guard, ledger),
          new OperationsService(tx, new PostgresOperations(tx), alerts),
          new ApprovedReader(tx, store, guard, ledger),
          diagnostics);
    } catch (RuntimeException e) {
      pool.close();
      throw e;
    }
  }

  @Bean
  AttributionReader attributionReader(Services services) {
    return services.reader();
  }

  @Bean
  AttributionRetry attributionRetry(Services services) {
    return services.operations();
  }
}
