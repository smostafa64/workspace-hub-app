package com.example.workspace.service;

import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer verification for SCN-006#A3: the migration applies to a populated database
 * without data loss and pre-migration rows read back with a null series identifier.
 *
 * <p>The embedded H2 database is owned by this class only (approved
 * {@code embedded-h2-unique-db} fixture). A populated legacy pre-feature database is constructed
 * the way the project describes its pre-feature state: rows are written through the application
 * persistence layer first, the series identifier column is then removed so the store matches the
 * legacy schema, and only then is the project's real Flyway migration chain applied with the
 * documented legacy baseline (version 1). The migrated rows are observed through a brand-new JPA
 * {@link EntityManager} (complete before/after snapshot equality, so any data loss fails) and
 * through the real Spring Data {@link ReservationRepository} (application read-back after the
 * migration).</p>
 *
 * <p>The database name and every resource identifier are generated at runtime, so no invocation can
 * observe another invocation's rows and no fixed calendar date or identifier is reused.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesMigrationDataPreservationIntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb003mig_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private ResourceRepository resources;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void isolatedDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:" + DATABASE_NAME
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
        // The legacy pre-feature database has no Flyway history; the test applies the real
        // migration explicitly after constructing the populated pre-migration state.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    // ---------------------------------------------------------------------
    // SCN-006#A3
    // "The migration applies to a populated database without data loss and
    // pre-migration rows read back with a null series identifier."
    // ---------------------------------------------------------------------
    @Test
    void scn006MigrationOnPopulatedDatabasePreservesEveryRowAndLeavesSeriesIdNull()
            throws Exception {
        BookableResource firstRoom = newResource();
        BookableResource secondRoom = newResource();
        Instant base = ZonedDateTime.of(2030, 3, 5, 9, 0, 0, 0, BERLIN).toInstant();

        String confirmedTitle = "scn006-legacy-confirmed-" + UUID.randomUUID();
        String pendingTitle = "scn006-legacy-pending-" + UUID.randomUUID();
        String cancelledTitle = "scn006-legacy-cancelled-" + UUID.randomUUID();

        Reservation confirmed = reservations.save(new Reservation(
                firstRoom, "legacy-owner-a-" + UUID.randomUUID(), confirmedTitle,
                base, base.plusSeconds(60 * 60), ReservationStatus.CONFIRMED, FIXED_INSTANT));
        Reservation pending = reservations.save(new Reservation(
                firstRoom, "legacy-owner-b-" + UUID.randomUUID(), pendingTitle,
                base.plusSeconds(24 * 60 * 60), base.plusSeconds(24 * 60 * 60 + 60 * 60),
                ReservationStatus.PENDING, FIXED_INSTANT));
        Reservation cancelled = reservations.save(new Reservation(
                secondRoom, "legacy-owner-c-" + UUID.randomUUID(), cancelledTitle,
                base.plusSeconds(2 * 24 * 60 * 60), base.plusSeconds(2 * 24 * 60 * 60 + 30 * 60),
                ReservationStatus.CANCELLED, FIXED_INSTANT));

        Set<Long> ownedRowIds = new LinkedHashSet<>();
        ownedRowIds.add(confirmed.getId());
        ownedRowIds.add(pending.getId());
        ownedRowIds.add(cancelled.getId());
        Set<Long> ownedResourceIds = Set.of(firstRoom.getId(), secondRoom.getId());

        List<RowSnapshot> beforeMigration = readSnapshots(ownedResourceIds);
        assertEquals(3, beforeMigration.size(),
                "precondition: the legacy database must be populated with the three pre-existing "
                        + "one-off rows, but saw " + beforeMigration.size());
        assertTrue(beforeMigration.stream().allMatch(row -> row.seriesId() == null),
                "precondition: legacy one-off rows must not carry a series identifier");
        assertEquals(ownedRowIds,
                beforeMigration.stream().map(RowSnapshot::id).collect(java.util.stream.Collectors.toSet()),
                "precondition: the populated rows must be exactly the three owned legacy rows");

        ColumnRef entityColumn = findSeriesIdColumn();
        assertNotNull(entityColumn,
                "precondition: the reservation entity schema maps a series identifier column");

        // Reconstruct the legacy pre-feature schema of the already populated database: the series
        // identifier column is absent when the project's migration runs.
        dropColumn(entityColumn);
        assertNull(findSeriesIdColumn(),
                "precondition: the legacy schema must not contain the series identifier column "
                        + "before the migration runs");

        // Apply the real project migration chain to the populated legacy database. The project's
        // documented legacy baseline is version 1, so V2 is the pending migration.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();

        assertNotNull(findSeriesIdColumn(),
                "control: the project migration must have applied to the populated database");

        List<RowSnapshot> afterMigration = readSnapshots(ownedResourceIds);
        assertEquals(beforeMigration.size(), afterMigration.size(),
                "the migration must not lose any pre-existing reservation row, but the row count "
                        + "changed from " + beforeMigration.size() + " to " + afterMigration.size());

        for (RowSnapshot before : beforeMigration) {
            RowSnapshot after = afterMigration.stream()
                    .filter(row -> row.id().equals(before.id()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "pre-existing row " + before.id() + " disappeared during the migration"));
            assertEquals(before, after,
                    "pre-existing row " + before.id() + " must survive the migration without any "
                            + "data loss");
            assertNull(after.seriesId(),
                    "pre-migration row " + before.id() + " must read back with a null series "
                            + "identifier, but saw " + after.seriesId());
        }

        // Application persistence read-back: the real Spring Data repository must expose the
        // migrated pre-existing rows with a null series identifier.
        for (Long rowId : ownedRowIds) {
            Reservation persisted = reservations.findById(rowId)
                    .orElseThrow(() -> new AssertionError(
                            "pre-existing row " + rowId + " must remain readable after the "
                                    + "migration"));
            assertNull(persisted.getSeriesId(),
                    "row " + rowId + " must read back through the application repository with a "
                            + "null series identifier, but saw " + persisted.getSeriesId());
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "WB003 MIG QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true, false);
        return resources.save(resource);
    }

    /** Reads complete pre/post migration snapshots through a brand-new EntityManager. */
    private List<RowSnapshot> readSnapshots(Set<Long> resourceIds) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.id, r.resource.id, r.bookedBy, r.title, r.startsAt, r.endsAt, "
                                    + "r.status, r.createdAt, r.seriesId from Reservation r "
                                    + "where r.resource.id in :resourceIds order by r.id",
                            Object[].class)
                    .setParameter("resourceIds", resourceIds)
                    .getResultList();
            List<RowSnapshot> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                result.add(new RowSnapshot(
                        ((Number) row[0]).longValue(),
                        ((Number) row[1]).longValue(),
                        (String) row[2],
                        (String) row[3],
                        toInstant(row[4]),
                        toInstant(row[5]),
                        (ReservationStatus) row[6],
                        toInstant(row[7]),
                        (UUID) row[8]));
            }
            return result;
        } finally {
            entityManager.close();
        }
    }

    /**
     * Finds the series identifier column through the relational store's own metadata, so the test
     * does not hardcode a table or column identifier.
     */
    private ColumnRef findSeriesIdColumn() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String schema = connection.getSchema();
            List<String> tables = new ArrayList<>();
            try (ResultSet tableRows = metadata.getTables(
                    connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
                while (tableRows.next()) {
                    tables.add(tableRows.getString("TABLE_NAME"));
                }
            }
            for (String table : tables) {
                try (ResultSet columns = metadata.getColumns(
                        connection.getCatalog(), schema, table, "%")) {
                    while (columns.next()) {
                        String column = columns.getString("COLUMN_NAME");
                        if (column != null && normalize(column).equals("SERIESID")) {
                            boolean nullable = columns.getInt("NULLABLE")
                                    == DatabaseMetaData.columnNullable;
                            return new ColumnRef(table, column, nullable);
                        }
                    }
                }
            }
            return null;
        }
    }

    private void dropColumn(ColumnRef column) throws SQLException {
        requireSimpleIdentifier(column.table());
        requireSimpleIdentifier(column.column());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + column.table() + " DROP COLUMN " + column.column());
        }
    }

    private static String normalize(String identifier) {
        return identifier.replace("_", "").toUpperCase(Locale.ROOT);
    }

    private static void requireSimpleIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("unexpected SQL identifier from metadata: " + identifier);
        }
    }

    /** Normalises the JDBC/Hibernate temporal representation of a persisted instant. */
    private static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        assertNotNull(value, "a persisted reservation must have a non-null timestamp");
        throw new IllegalStateException("unexpected temporal type: " + value.getClass().getName());
    }

    private record ColumnRef(String table, String column, boolean nullable) {
    }

    private record RowSnapshot(Long id, Long resourceId, String bookedBy, String title,
                               Instant startsAt, Instant endsAt, ReservationStatus status,
                               Instant createdAt, UUID seriesId) {
    }
}
