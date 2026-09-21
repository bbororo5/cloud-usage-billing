package io.github.bbororo5.cloudbilling.ledgerwriter.config;

import io.github.bbororo5.cloudbilling.event.InstanceUsageEventParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableConfigurationProperties(LedgerWriterProperties.class)
public class LedgerWriterConfiguration {

    @Bean
    InstanceUsageEventParser instanceUsageEventParser() {
        return new InstanceUsageEventParser();
    }

    @Bean
    CommonErrorHandler ledgerWriterErrorHandler() {
        return new DefaultErrorHandler(
                new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS)
        );
    }
}
