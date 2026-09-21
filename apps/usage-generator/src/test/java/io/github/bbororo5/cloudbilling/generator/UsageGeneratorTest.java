package io.github.bbororo5.cloudbilling.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class UsageGeneratorTest {
    final UsageGenerator generator = new UsageGenerator("vm-1", "vm-1",
            Instant.parse("2026-08-12T00:00:35.123Z"), BigInteger.valueOf(100),
            new BigInteger("18446744073709551615"));

    @Test void outputMeetsTheActualSchemaAndPreservesPrecision() throws Exception {
        String json=generator.event(0).value();
        Schema schema=SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(Files.readString(Path.of(System.getProperty("contracts.dir"),"v1/instance-usage-event.schema.json")));
        assertThat(schema.validate(json,InputFormat.JSON,
            c->c.executionConfig(s->s.formatAssertionsEnabled(true)))).isEmpty();
        var node=new ObjectMapper().readTree(json);
        assertThat(node.path("time").asText()).isEqualTo("2026-08-12T00:01:35.123Z");
        assertThat(node.path("data").get(2).path("ConsumedQuantity").bigIntegerValue())
            .isEqualTo(new BigInteger("18446744073709551615"));
        assertThat(node.path("data").get(1).path("ConsumedQuantity").asLong()).isEqualTo(6000);
    }
    @Test void completedIntervalsExcludeThePartialTail() {
        assertThat(generator.completedIntervals(35)).isZero();
        assertThat(generator.completedIntervals(60)).isEqualTo(1);
        assertThat(generator.completedIntervals(119)).isEqualTo(1);
        assertThat(generator.completedIntervals(120)).isEqualTo(2);
    }
    @Test void replayIsStableAndNewIntervalsAreDifferent() {
        assertThat(generator.event(0)).isEqualTo(generator.event(0));
        assertThat(generator.event(0).value()).isNotEqualTo(generator.event(1).value());
        assertThat(generator.event(0).key()).isEqualTo("urn:cloud-usage:meter:vm-1");
    }
    @Test void invalidSourcePrecisionAndQuantityFailBeforePublishing() {
        assertThatThrownBy(()->new UsageGenerator("bad source","vm",Instant.EPOCH,BigInteger.ZERO,BigInteger.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new UsageGenerator("vm","vm",Instant.EPOCH.plusNanos(1),BigInteger.ZERO,BigInteger.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new UsageGenerator("vm","vm",Instant.EPOCH,BigInteger.valueOf(-1),BigInteger.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new UsageGenerator("vm","vm",Instant.EPOCH,BigInteger.ZERO,BigInteger.ONE.shiftLeft(64)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ackIsRequiredAndAmbiguousFailureRetriesIdenticalBytes() throws Exception {
        var producer=new MockProducer<String,String>(false,null,new StringSerializer(),new StringSerializer());
        try(var executor=Executors.newSingleThreadExecutor()) {
            var result=executor.submit(()-> { new AckPublisher(producer,()->{}).publish(generator.event(0)); return true; });
            awaitHistory(producer,1);
            assertThat(result.isDone()).isFalse();
            producer.errorNext(new TimeoutException("ACK unknown"));
            awaitHistory(producer,2);
            assertThat(producer.history().get(0).value()).isEqualTo(producer.history().get(1).value());
            producer.completeNext();
            assertThat(result.get(5,TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void deniedCredentialsDoNotLoopForeverOrReportSuccess() throws Exception {
        var producer=new MockProducer<String,String>(true,null,new StringSerializer(),new StringSerializer());
        producer.sendException=new AuthenticationException("denied");
        assertThatThrownBy(()->new AckPublisher(producer,()->{}).publish(generator.event(0)))
            .isInstanceOf(AuthenticationException.class);
    }
    private static void awaitHistory(MockProducer<String,String> producer,int count) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(producer.history().size()<count && System.nanoTime()<deadline) Thread.sleep(5);
        assertThat(producer.history()).hasSize(count);
    }
}
