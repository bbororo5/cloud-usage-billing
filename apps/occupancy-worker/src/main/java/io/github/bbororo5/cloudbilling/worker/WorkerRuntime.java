package io.github.bbororo5.cloudbilling.worker;

import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.kafka.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.adapter.postgres.*;
import io.github.bbororo5.cloudbilling.worker.occupancyhistory.application.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Composition/lifecycle boundary only; business rules stay inside the history module. */
@Component
public final class WorkerRuntime implements SmartLifecycle {
  private final ReceiptService receipt;
  private final ApplyService apply;
  private final NotificationService notifications;
  private final EventDecoder decoder;
  private final JdbcTransactions tx;
  private final PostgresIssueStore issues;
  private final PostgresDiagnostics diagnostics;
  private final MeterRegistry meters;
  private final String configFile, topic;
  private final boolean enabled;
  private final int batchSize, waitAlertSeconds;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicInteger storageReady = new AtomicInteger(), kafkaReady = new AtomicInteger();
  private final ConcurrentHashMap<String, Double> measurements = new ConcurrentHashMap<>();
  private ScheduledExecutorService jobs;
  private Thread receiver;

  public WorkerRuntime(
      ReceiptService receipt,
      ApplyService apply,
      NotificationService notifications,
      EventDecoder decoder,
      JdbcTransactions tx,
      PostgresIssueStore issues,
      PostgresDiagnostics diagnostics,
      MeterRegistry meters,
      @Value("${occupancy.kafka-config:}") String configFile,
      @Value("${occupancy.topic:instance-occupancy-events.v1}") String topic,
      @Value("${occupancy.enabled:true}") boolean enabled,
      @Value("${occupancy.batch-size:100}") int batchSize,
      @Value("${occupancy.wait-alert-seconds:300}") int waitAlertSeconds) {
    this.receipt = receipt;
    this.apply = apply;
    this.notifications = notifications;
    this.decoder = decoder;
    this.tx = tx;
    this.issues = issues;
    this.diagnostics = diagnostics;
    this.meters = meters;
    this.configFile = configFile;
    this.topic = topic;
    this.enabled = enabled;
    this.batchSize = batchSize;
    this.waitAlertSeconds = waitAlertSeconds;
    if (batchSize < 1 || batchSize > 1000 || waitAlertSeconds < 1)
      throw new IllegalArgumentException("Invalid worker limits");
    meters.gauge("occupancy.storage.ready", storageReady);
    meters.gauge("occupancy.kafka.ready", kafkaReady);
  }

  @Override
  public void start() {
    if (!enabled || running.get()) return;
    diagnostics.validate();
    Properties properties = new Properties();
    try (var in = Files.newInputStream(Path.of(configFile))) {
      properties.load(in);
    } catch (Exception e) {
      throw new IllegalStateException("Provide occupancy.kafka-config", e);
    }
    if (!properties.containsKey("bootstrap.servers"))
      throw new IllegalStateException("Missing Kafka bootstrap servers");
    properties.setProperty("enable.auto.commit", "false");
    properties.setProperty("allow.auto.create.topics", "false");
    properties.setProperty("auto.offset.reset", "earliest");
    properties.putIfAbsent("group.id", "occupancy-worker-v1");
    properties.setProperty("max.poll.records", "25");
    properties.setProperty("default.api.timeout.ms", "10000");
    properties.setProperty("key.deserializer", ByteArrayDeserializer.class.getName());
    properties.setProperty("value.deserializer", ByteArrayDeserializer.class.getName());
    running.set(true);
    storageReady.set(1);
    jobs = Executors.newScheduledThreadPool(2);
    jobs.scheduleWithFixedDelay(
        () ->
            guarded(
                "apply",
                () -> {
                  apply.runDue(batchSize);
                  storageReady.set(1);
                }),
        0,
        100,
        TimeUnit.MILLISECONDS);
    jobs.scheduleWithFixedDelay(
        () ->
            guarded(
                "operations",
                () -> {
                  tx.write(
                      () -> {
                        issues.flagLongWaits(waitAlertSeconds, batchSize);
                        return null;
                      });
                  notifications.deliverPending(batchSize);
                }),
        0,
        1000,
        TimeUnit.MILLISECONDS);
    jobs.scheduleWithFixedDelay(
        () ->
            guarded(
                "measure",
                () -> {
                  diagnostics
                      .measurements()
                      .forEach(
                          (key, value) -> {
                            if (!measurements.containsKey(key))
                              meters.gauge(
                                  "occupancy." + key, measurements, m -> m.getOrDefault(key, 0.0));
                            measurements.put(key, value);
                          });
                }),
        0,
        5,
        TimeUnit.SECONDS);
    receiver = Thread.ofPlatform().name("occupancy-kafka").start(() -> receive(properties));
  }

  private void receive(Properties properties) {
    while (running.get()) {
      try (var client = new KafkaConsumer<byte[], byte[]>(properties);
          var consumer = new OccupancyConsumer(client, receipt, decoder)) {
        client.subscribe(List.of(topic));
        while (running.get()) {
          int count = consumer.pollOnce(Duration.ofMillis(250));
          kafkaReady.set(client.assignment().isEmpty() ? 0 : 1);
          meters.counter("occupancy.receipts").increment(count);
        }
      } catch (RuntimeException failure) {
        kafkaReady.set(0);
        meters.counter("occupancy.failures", "stage", "receive").increment();
        LoggerFactory.getLogger(getClass())
            .warn("Occupancy receive failed: {}", failure.getClass().getSimpleName());
        try {
          Thread.sleep(1000);
        } catch (InterruptedException stop) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void guarded(String stage, Runnable task) {
    if (!running.get()) return;
    try {
      task.run();
    } catch (RuntimeException failure) {
      storageReady.set(0);
      meters.counter("occupancy.failures", "stage", stage).increment();
      LoggerFactory.getLogger(getClass())
          .warn("Occupancy {} failed: {}", stage, failure.getClass().getSimpleName());
    }
  }

  @Override
  public void stop() {
    if (!running.getAndSet(false)) return;
    jobs.shutdown();
    try {
      if (receiver != null) receiver.join(30000);
      if (!jobs.awaitTermination(30, TimeUnit.SECONDS)) jobs.shutdownNow();
      if (receiver != null && receiver.isAlive()) receiver.interrupt();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    storageReady.set(0);
    kafkaReady.set(0);
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public boolean isAutoStartup() {
    return enabled;
  }
}
