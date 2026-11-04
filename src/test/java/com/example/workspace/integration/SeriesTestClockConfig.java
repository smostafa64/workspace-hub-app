package com.example.workspace.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Fixed test clock shared by the integration tests of this package.
 *
 * <p>The bean deliberately does not reuse the production bean name, so no bean-definition
 * override is required; {@code @Primary} selects it for by-type injection into the application
 * services. Using one configuration type keeps the Spring test context cacheable across the
 * sibling integration test classes.</p>
 */
@TestConfiguration
public class SeriesTestClockConfig {

    /** 2030-01-01T00:00:00Z: before every fixture interval used by these tests. */
    public static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Bean
    @Primary
    Clock seriesTestClock() {
        return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    }
}
