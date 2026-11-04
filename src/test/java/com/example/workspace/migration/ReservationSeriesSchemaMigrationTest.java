package com.example.workspace.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCN-012#A5 - the schema change that adds the reservation seriesId is delivered as a versioned
 * database migration instead of an uncontrolled schema change.
 *
 * <p>The test inspects the migration artifacts that Flyway resolves from the runtime classpath.
 * Migrations are deliberately not executed here: the frozen test profile of this module builds its
 * schema with Hibernate ({@code spring.flyway.enabled=false} plus {@code ddl-auto=create-drop}) on
 * an embedded H2 database, so executing the production migrations would test the test database
 * dialect rather than the delivery mechanism. The assertion is therefore observed on the migration
 * artifacts themselves: a versioned {@code V<version>__...} artifact (never a repeatable or
 * ad-hoc script) that introduces the series support, plus the concrete {@code series_id} column
 * for SQL migrations.</p>
 */
class ReservationSeriesSchemaMigrationTest {

    /** Flyway versioned-migration naming: V<version>__<description>, not R__ (repeatable). */
    private static final Pattern VERSIONED_MIGRATION =
            Pattern.compile("^V\\d+(?:[._]\\d+)*__.+$", Pattern.CASE_INSENSITIVE);

    @Test
    void seriesIdSchemaChangeIsDeliveredAsAVersionedFlywayMigration() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Set<Resource> discovered = new LinkedHashSet<>();
        for (String location : new String[]{"classpath*:db/migration/**", "classpath*:**/migration/V*__*"}) {
            discovered.addAll(Arrays.asList(resolver.getResources(location)));
        }
        assertTrue(!discovered.isEmpty(),
                "no database migration artifacts were found on the classpath under db/migration or a migration package");

        List<Resource> seriesMigrations = new ArrayList<>();
        for (Resource resource : discovered) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            String baseName = filename.replaceFirst("\\.(sql|class)$", "");
            if (!VERSIONED_MIGRATION.matcher(baseName).matches()) {
                continue;
            }
            if (!baseName.toLowerCase(Locale.ROOT).contains("series")) {
                continue;
            }
            seriesMigrations.add(resource);
        }

        assertFalse(seriesMigrations.isEmpty(),
                "the seriesId schema change must be delivered by a versioned migration "
                        + "(V<version>__...series...), artifacts found: " + names(discovered));

        boolean introducesSeriesSchema = false;
        for (Resource migration : seriesMigrations) {
            String filename = migration.getFilename();
            String lowerName = filename.toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".sql")) {
                String content = read(migration).toLowerCase(Locale.ROOT);
                assertTrue(content.contains("series_id"),
                        "the versioned SQL migration " + filename
                                + " must add the reservation series_id column");
                introducesSeriesSchema = true;
            } else {
                // A versioned Flyway Java migration (V<n>__<description>) cannot be executed here,
                // but its versioned artifact is the delivery mechanism for the schema change.
                introducesSeriesSchema = introducesSeriesSchema || lowerName.endsWith(".class");
            }
        }
        assertTrue(introducesSeriesSchema,
                "the discovered series migration artifacts must carry the seriesId schema change: "
                        + names(seriesMigrations));
    }

    private String read(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<String> names(Iterable<Resource> resources) {
        List<String> result = new ArrayList<>();
        for (Resource resource : resources) {
            result.add(resource.getFilename());
        }
        return result;
    }
}
