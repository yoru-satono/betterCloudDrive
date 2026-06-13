package com.betterclouddrive.start.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.LinkedHashSet;

@Configuration
public class FlywayConfig {

    private static final String ENTITY_MANAGER_FACTORY = "entityManagerFactory";
    private static final String FLYWAY = "flyway";

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

    @Bean
    public static BeanFactoryPostProcessor entityManagerFactoryDependsOnFlyway() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition(ENTITY_MANAGER_FACTORY)) {
                return;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(ENTITY_MANAGER_FACTORY);
            LinkedHashSet<String> dependsOn = new LinkedHashSet<>();
            String[] existingDependencies = definition.getDependsOn();
            if (existingDependencies != null) {
                dependsOn.addAll(java.util.List.of(existingDependencies));
            }
            dependsOn.add(FLYWAY);
            definition.setDependsOn(dependsOn.toArray(String[]::new));
        };
    }
}
