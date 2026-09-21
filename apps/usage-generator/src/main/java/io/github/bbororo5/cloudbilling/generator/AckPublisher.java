package io.github.bbororo5.cloudbilling.generator;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import java.util.concurrent.ExecutionException;

/** No ACK, no success. An unknown outcome retries the exact same event. */
final class AckPublisher {
    interface Backoff { void pause() throws InterruptedException; }
    private final Producer<String,String> producer;
    private final Backoff backoff;
    AckPublisher(Producer<String,String> producer, Backoff backoff) {
        this.producer=producer; this.backoff=backoff;
    }
    void publish(UsageGenerator.Event event) throws InterruptedException {
        while (true) {
            try {
                producer.send(new ProducerRecord<>("usage-events.v1",event.key(),event.value())).get();
                return;
            } catch (ExecutionException exception) {
                if (!(exception.getCause() instanceof RetriableException))
                    throw new IllegalStateException("Publish failed without ACK; replay the unchanged plan",exception.getCause());
            } catch (RetriableException exception) {
                // Unknown delivery outcome; keep the original ID and bytes.
            }
            System.err.println("WARN Kafka ACK unavailable; retaining and retrying the same event");
            backoff.pause();
        }
    }
}
