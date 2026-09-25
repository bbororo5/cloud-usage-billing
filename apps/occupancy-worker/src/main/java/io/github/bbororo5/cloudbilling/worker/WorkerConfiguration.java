package io.github.bbororo5.cloudbilling.worker;

import io.github.bbororo5.cloudbilling.worker.common.JdbcTransactions;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.cache.LocalSnapshotCache;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.kafka.EventDecoder;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.api.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.port.AlertSender;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class WorkerConfiguration {
  @Bean
  JdbcTransactions transactions(DataSource ds) {
    return new JdbcTransactions(ds);
  }

  @Bean
  PostgresHistoryStore historyStore(JdbcTransactions tx) {
    return new PostgresHistoryStore(tx);
  }

  @Bean
  PostgresIssueStore issueStore(JdbcTransactions tx) {
    return new PostgresIssueStore(tx);
  }

  @Bean
  ReceiptService receipt(JdbcTransactions tx, PostgresHistoryStore store) {
    return new ReceiptService(tx, store);
  }

  @Bean
  ApplyService apply(JdbcTransactions tx, PostgresHistoryStore store) {
    return new ApplyService(tx, store);
  }

  @Bean
  LocalSnapshotCache cache(
      @Value("${occupancy.cache-capacity:1000}") int size, MeterRegistry metrics) {
    var cache = new LocalSnapshotCache(size);
    metrics.gauge("occupancy.cache.hits", cache, LocalSnapshotCache::hits);
    metrics.gauge("occupancy.cache.misses", cache, LocalSnapshotCache::misses);
    metrics.gauge("occupancy.cache.entries", cache, LocalSnapshotCache::size);
    return cache;
  }

  @Bean
  HistoryReader historyReader(JdbcTransactions tx, LocalSnapshotCache cache) {
    return new QueryService(tx, new PostgresSnapshotStore(tx), cache);
  }

  @Bean
  IssueRetry issueRetry(JdbcTransactions tx, PostgresIssueStore issues) {
    return new RetryService(tx, issues);
  }

  @Bean
  EventDecoder decoder() {
    return new EventDecoder();
  }

  @Bean
  PostgresDiagnostics diagnostics(JdbcTransactions tx) {
    return new PostgresDiagnostics(tx);
  }

  @Bean
  @ConditionalOnMissingBean(AlertSender.class)
  AlertSender unconfiguredChannel() {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .warn(
            "External occupancy alert channel is not configured; alerts remain pending in"
                + " PostgreSQL");
    return alert -> {
      throw new IllegalStateException("No external alert channel configured");
    };
  }

  @Bean
  NotificationService notifications(
      JdbcTransactions tx, PostgresIssueStore issues, AlertSender sender) {
    return new NotificationService(tx, issues, sender);
  }
}
