package com.example.workspace.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration-layer obligation SCN-012#A5 (job WBATCH-006): the schema change that adds the
 * reservation seriesId is delivered as a database migration rather than an uncontrolled schema
 * change.
 *
 * <p>Boundary: the real Spring Boot application context is loaded and the application's own Flyway
 * configuration (the {@code spring.flyway.locations} property resolved from the running
 * {@link Environment}, i.e. the production configuration plus the test profile) is applied to the
 * live {@link DataSource} through the Flyway library that ships with the application. That proves
 * the versioned series migration is <em>wired into</em> the application's migration configuration,
 * not merely present somewhere on the classpath - the additional fault this layer detects compared
 * with the database-layer classpath inspection.</p>
 *
 * <p>The migrations are not executed: the frozen test profile builds its schema with Hibernate on
 * an embedded H2 database, so executing production migrations would exercise the H2 dialect rather
 * than the delivery mechanism. Flyway's {@code info()} resolves the configured migrations without
 * modifying the schema, so no state is changed by this test.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationSeriesMigrationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    /**
     * SCN-012#A5 - the application's configured Flyway locations resolve a versioned migration
     * whose description identifies the reservation-series schema change.
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-012")
    @Tag("fault_model:schema_delivery")
    void seriesIdSchemaChangeIsDeliveredByTheConfiguredVersionedMigration() {
        String[] locations = configuredMigrationLocations();
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .load();

        MigrationInfo[] resolved = flyway.info().all();
        List<MigrationInfo> resolvedList = Arrays.asList(resolved);
        List<MigrationInfo> versioned = resolvedList.stream()
                .filter(info -> info.getVersion() != null)
                .collect(Collectors.toList());
        assertFalse(versioned.isEmpty(),
                "the application's configured migration locations " + Arrays.toString(locations)
                        + " must resolve at least one versioned migration, resolved: " + describe(resolvedList));

        List<MigrationInfo> seriesMigrations = versioned.stream()
                .filter(info -> info.getDescription() != null
                        && info.getDescription().toLowerCase(Locale.ROOT).contains("series"))
                .collect(Collectors.toList());
        assertFalse(seriesMigrations.isEmpty(),
                "the seriesId schema change must be delivered by a versioned migration resolved from the "
                        + "application's configured locations " + Arrays.toString(locations)
                        + "; versioned migrations resolved: " + describe(versioned));
    }

    /**
     * Reads the application's configured Flyway locations, falling back to Flyway's default of
     * {@code classpath:db/migration} when the property is absent or blank.
     */
    private String[] configuredMigrationLocations() {
        String raw = environment.getProperty("spring.flyway.locations", "classpath:db/migration");
        List<String> locations = new ArrayList<>();
        for (String candidate : raw.split(",")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                locations.add(trimmed);
            }
        }
        if (locations.isEmpty()) {
            locations.add("classpath:db/migration");
        }
        return locations.toArray(new String[0]);
    }

    private String describe(List<MigrationInfo> migrations) {
        return migrations.stream()
                .map(info -> (info.getVersion() == null ? "" : "V" + info.getVersion() + "__")
                        + (info.getDescription() == null ? "?" : info.getDescription())
                        + "[" + info.getType() + "]")
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
