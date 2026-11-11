package dev.ledgerforge;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.config.ProductionSafetyGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LedgerForgeApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(LedgerForgeApplication.class);
        // Fail fast on unsafe production configuration before any bean (datasource, Flyway) is created.
        app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>)
                event -> ProductionSafetyGuard.verify(Binder.get(event.getEnvironment())
                        .bind("app", AppProperties.class)
                        .get()));
        app.run(args);
    }
}
