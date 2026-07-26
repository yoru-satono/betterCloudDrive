package com.betterclouddrive;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class TracingSamplingConfigurationTest {

    private static final String SAMPLING_PROPERTY = "management.tracing.sampling.probability";

    @Test
    void developmentProfileDefaultsToFullSampling() throws IOException {
        assertThat(resolveSamplingProbability("application-dev.yml")).isEqualTo("1.0");
    }

    @Test
    void productionProfileDefaultsToTenPercentSampling() throws IOException {
        assertThat(resolveSamplingProbability("application-prod.yml")).isEqualTo("0.1");
    }

    private String resolveSamplingProbability(String profileResource) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        MutablePropertySources sources = new MutablePropertySources();
        loader.load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        loader.load("profile", new ClassPathResource(profileResource))
                .forEach(sources::addFirst);
        return new PropertySourcesPropertyResolver(sources).getProperty(SAMPLING_PROPERTY);
    }
}
