package dev.ledgerforge.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    /** All timestamps are produced from a UTC clock; see {@link ShiftableClock} for the seeder hook. */
    @Bean
    public ShiftableClock clock() {
        return new ShiftableClock(Clock.systemUTC());
    }
}
