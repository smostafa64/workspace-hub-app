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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer verification for SCN-006#A1: the reservation store exposes a nullable series
 * identifier column introduced by the project's database migration, and pre-existing one-off rows
 * stay valid with a null value.
 *
 * <p>The embedded H2 database is owned by this class only (approved
 * {@code embedded-h2-unique-db} fixture). A legacy pre-feature schema is produced the way the
 * project describes its pre-feature databases: from the Hibernate entity mappings, without Flyway
 * history. The series identifier column is then removed to represent the legacy schema, a one-off
 * reservation row is inserted before any migration runs, and the real Flyway migration chain is
 * applied with the project's documented legacy baseline (version 1). The resulting relational store
 * is observed through a brand-new JPA {@link EntityManager}, and the column definition is observed
 * through JDBC {@link DatabaseMetaData}.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesMigrationPersistenceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb001mig_" + UUID.randomUUID().toString().replace("-", "");

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
        // migration explicitly after constructing the pre-migration state.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    // ---------------------------------------------------------------------
    // SCN-006#A1
    // "The reservation store exposes a nullable series identifier column introduced
    // by migration and pre-existing one-off rows stay valid with a null value."
    // ---------------------------------------------------------------------
    @Test
    void scn006MigrationAddsNullableSeriesIdColumnAndPreservesPreExistingOneOffRows() throws Exception {
        BookableResource resource = newResource();
        String title = "scn006-legacy-one-off-" + UUID.randomUUID();
        Instant startsAt = ZonedDateTime.of(2030, 3, 5, 9, 0, 0, 0, BERLIN).toInstant();
        Instant endsAt = startsAt.plusSeconds(60 * 60);

        // A pre-existing one-off reservation, created before the series identifier column exists.
        Reservation oneOff = reservations.save(new Reservation(
                resource, "legacy-owner-" + UUID.randomUUID(), title, startsAt, endsAt,
                ReservationStatus.CONFIRMED, FIXED_INSTANT));
        Long oneOffId = oneOff.getId();
        assertNotNull(oneOffId, "precondition: the pre-existing one-off reservation must be persisted");
        List<PersistedReservation> preMigrationRows = readRows(resource.getId());
        assertEquals(1, preMigrationRows.size(),
                "precondition: the pre-existing one-off row must be readable before the migration");
        assertNull(preMigrationRows.get(0).seriesId(),
                "precondition: a one-off reservation carries no series identifier");

        ColumnRef entityColumn = findSeriesIdColumn();
        assertNotNull(entityColumn,
                "precondition: the reservation entity schema maps a series identifier column");

        // Reconstruct the legacy pre-feature schema: the series identifier column is absent.
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

        ColumnRef migratedColumn = findSeriesIdColumn();
        assertNotNull(migratedColumn,
                "the database migration must introduce the series identifier column on the "
                        + "reservation store");
        assertTrue(migratedColumn.nullable(),
                "the migrated series identifier column must be nullable, but metadata reports it "
                        + "as NOT NULL");

        List<PersistedReservation> rows = readRows(resource.getId());
        assertEquals(1, rows.size(),
                "the pre-existing one-off row must survive the migration without data loss, but "
                        + "saw " + rows.size() + " rows");
        PersistedReservation row = rows.get(0);
        assertEquals(oneOffId, row.id(), "the pre-existing row must keep its primary key");
        assertEquals(title, row.title(), "the pre-existing row must keep its title");
        assertEquals(startsAt, row.startsAt(), "the pre-existing row must keep its start instant");
        assertEquals(endsAt, row.endsAt(), "the pre-existing row must keep its end instant");
        assertEquals(ReservationStatus.CONFIRMED, row.status(),
                "the pre-existing row must keep its status");
        assertNull(row.seriesId(),
                "a pre-migration one-off row must read back with a null series identifier, but saw "
                        + row.seriesId());
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "WB001 MIG QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true, false);
        return resources.save(resource);
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
        return identifier.replace("_", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static void requireSimpleIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("unexpected SQL identifier from metadata: " + identifier);
        }
    }

    /** Reads the committed rows for one owned resource through a brand-new EntityManager. */
    private List<PersistedReservation> readRows(long resourceId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.id, r.startsAt, r.endsAt, r.seriesId, r.title, r.status "
                                    + "from Reservation r where r.resource.id = :resourceId "
                                    + "order by r.id", Object[].class)
                    .setParameter("resourceId", resourceId)
                    .getResultList();
            List<PersistedReservation> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                result.add(new PersistedReservation(
                        ((Number) row[0]).longValue(),
                        toInstant(row[1]),
                        toInstant(row[2]),
                        (UUID) row[3],
                        (String) row[4],
                        (ReservationStatus) row[5]));
            }
            return result;
        } finally {
            entityManager.close();
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

    private record PersistedReservation(Long id, Instant startsAt, Instant endsAt, UUID seriesId,
                                        String title, ReservationStatus status) {
    }
}
