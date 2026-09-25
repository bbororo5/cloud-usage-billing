package io.github.bbororo5.cloudbilling.worker;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** One independently guarded loop inside the existing worker, not another server or consumer. */
@Component
@ConditionalOnProperty(name = "attribution.enabled", havingValue = "true")
public final class AttributionRuntime implements SmartLifecycle {
  private final AttributionConfiguration.Services services;
  private final MeterRegistry meters;
  private final AtomicBoolean running = new AtomicBoolean();
  private final ConcurrentHashMap<String, Double> measurements = new ConcurrentHashMap<>();
  private ScheduledExecutorService loop;

  public AttributionRuntime(AttributionConfiguration.Services services, MeterRegistry meters) {
    this.services = services;
    this.meters = meters;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) return;
    loop = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "usage-attribution"));
    loop.scheduleWithFixedDelay(
        () -> {
          guarded(
              "discover",
              () -> {
                var p = services.discovery().runPage(500);
                meters.counter("attribution.scanned").increment(p.read());
                meters.counter("attribution.discovered").increment(p.registered());
              });
          guarded(
              "process",
              () -> {
                for (int i = 0; i < 50 && running.get(); i++)
                  if (!services.processing().runOne()) break;
              });
          guarded("alerts", () -> services.operations().notifyPending(50));
          guarded(
              "metrics",
              () ->
                  services
                      .diagnostics()
                      .measurements()
                      .forEach(
                          (k, v) -> {
                            if (!measurements.containsKey(k))
                              meters.gauge(
                                  "attribution." + k, measurements, m -> m.getOrDefault(k, 0.0));
                            measurements.put(k, v);
                          }));
        },
        0,
        1,
        TimeUnit.SECONDS);
  }

  private void guarded(String stage, Runnable action) {
    if (!running.get()) return;
    try {
      action.run();
    } catch (RuntimeException e) {
      meters.counter("attribution.failures", "stage", stage).increment();
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Attribution {} failed: {}", stage, e.getClass().getSimpleName());
    }
  }

  public void stop() {
    if (!running.getAndSet(false)) return;
    loop.shutdown();
    try {
      if (!loop.awaitTermination(30, TimeUnit.SECONDS)) loop.shutdownNow();
    } catch (InterruptedException e) {
      loop.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  public boolean isRunning() {
    return running.get();
  }
}
