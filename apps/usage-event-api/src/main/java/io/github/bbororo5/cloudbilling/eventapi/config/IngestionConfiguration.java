package io.github.bbororo5.cloudbilling.eventapi.config;

import io.github.bbororo5.cloudbilling.event.InstanceUsageEventParser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IngestionProperties.class)
public class IngestionConfiguration {

    @Bean
    InstanceUsageEventParser instanceUsageEventParser() {
        return new InstanceUsageEventParser();
    }
}
