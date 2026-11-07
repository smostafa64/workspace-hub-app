package com.example.workspace.service;

import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer verification for SCN-022#A1: every reservation row belonging to a series
 * persists the same stable series identifier in the relational store.
 *
 * <p>The embedded H2 database name is generated once per class and the schema is derived from the
 * application entity mappings, per the approved {@code embedded-h2-unique-db} fixture contract.
 * The series is created through the real {@link ReservationService} transaction and the committed
 * rows are then read through a brand-new JPA {@link EntityManager}. Each method owns a UUID-coded
 * bookable resource, so no method can observe another method's rows. The identifier is read twice
 * through two independent entity managers to prove the persisted value is stable rather than
 * regenerated per row or per read.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class,
        ReservationSeriesIdentifierPersistenceTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesIdentifierPersistenceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");
    private static final int OCCURRENCES = 3;

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb003_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private ReservationService service;

    @Autowired
    private ResourceRepository resources;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

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
        registry.add("spring.flyway.enabled", () -> "false");
    }

    // ---------------------------------------------------------------------
    // SCN-022#A1
    // ---------------------------------------------------------------------
    @Test
    void scn022EverySeriesReservationRowStoresTheSameStableSeriesIdentifier() {
        BookableResource resource = newResource();
        String title = "scn022-series-" + UUID.randomUUID();
        OffsetDateTime startsAt = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        assertEquals(0, readRows(resource.getId()).size(),
                "precondition: the owned resource must have no reservations before the series call");

        ReservationSeriesView view = createSeries(resource.getId(), title, startsAt, endsAt);
        assertNotNull(view, "control: the conflict-free series request must complete");

        List<PersistedRow> firstRead = readRows(resource.getId());
        assertEquals(OCCURRENCES, firstRead.size(),
                "the series request must persist all requested occurrences");
        assertTrue(firstRead.stream().allMatch(row -> title.equals(row.title())),
                "every observed row must belong to the requested series, but saw "
                        + firstRead.stream().map(PersistedRow::title).toList());

        UUID seriesId = firstRead.get(0).seriesId();
        assertNotNull(seriesId,
                "every persisted reservation row of a series must carry a series identifier");
        for (PersistedRow row : firstRead) {
            assertNotNull(row.seriesId(),
                    "reservation row " + row.id() + " must persist a series identifier");
            assertEquals(seriesId, row.seriesId(),
                    "reservation row " + row.id() + " must persist the same series identifier as "
                            + "the other rows of the series");
        }

        // A second, independent EntityManager re-reads the committed rows. A value regenerated per
        // read (or derived from transient state) would not survive this observation.
        List<PersistedRow> secondRead = readRows(resource.getId());
        assertEquals(firstRead.size(), secondRead.size(),
                "the independent re-read must observe the same number of series rows");
        for (int i = 0; i < firstRead.size(); i++) {
            assertEquals(firstRead.get(i).id(), secondRead.get(i).id(),
                    "the independent re-read must observe the same rows in the same order");
            assertEquals(seriesId, secondRead.get(i).seriesId(),
                    "the independent re-read of row " + secondRead.get(i).id()
                            + " must observe the same persisted series identifier");
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "SCN022 QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true, false);
        return resources.save(resource);
    }

    private ReservationSeriesView createSeries(long resourceId, String title, OffsetDateTime startsAt,
                                              OffsetDateTime endsAt) {
        ReservationSeriesInput input = new ReservationSeriesInput(resourceId, title, startsAt, endsAt,
                SERIES_TIMEZONE, SERIES_FREQUENCY, OCCURRENCES);
        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain its supplied offset");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain its supplied offset");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: the series timezone must be retained");
        return service.createSeries(input, "qa-" + UUID.randomUUID());
    }

    /** Reads the committed rows for one owned resource through a brand-new EntityManager. */
    private List<PersistedRow> readRows(long resourceId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.id, r.seriesId, r.title, r.startsAt from Reservation r "
                                    + "where r.resource.id = :resourceId order by r.id",
                            Object[].class)
                    .setParameter("resourceId", resourceId)
                    .getResultList();
            List<PersistedRow> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                result.add(new PersistedRow(
                        ((Number) row[0]).longValue(),
                        (UUID) row[1],
                        (String) row[2],
                        toInstant(row[3])));
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

    private record PersistedRow(Long id, UUID seriesId, String title, Instant startsAt) {
    }
}
