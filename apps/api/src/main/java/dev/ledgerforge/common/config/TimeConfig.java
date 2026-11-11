package dev.ledgerforge.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** All timestamps are produced from a UTC clock; injectable for tests. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
