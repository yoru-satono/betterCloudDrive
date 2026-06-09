package com.betterclouddrive.start.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class FlywayConfig {

    @Value("${spring.flyway.locations:classpath:db/migration}")
    private String locations;

    @Value("${spring.flyway.schemas:public}")
    private String schemas;

    @Value("${spring.flyway.baseline-on-migrate:true}")
    private boolean baselineOnMigrate;

    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .schemas(schemas)
                .baselineOnMigrate(baselineOnMigrate)
                .load();
        flyway.migrate();
        return flyway;
    }
}
