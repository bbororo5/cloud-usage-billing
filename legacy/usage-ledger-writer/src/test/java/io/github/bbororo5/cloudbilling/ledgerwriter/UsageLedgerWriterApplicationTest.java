package io.github.bbororo5.cloudbilling.ledgerwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class UsageLedgerWriterApplicationTest {

    @Test
    void contextLoads() {
    }
}
