package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationConflictException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer verification for the reservation-series persistence obligations of
 * SCN-001#A2, SCN-004#A3 and SCN-007#A3.
 *
 * <p>Every assertion observes the committed relational store through a brand-new JPA
 * {@link EntityManager} created after the real {@link ReservationService} transaction has
 * completed. The service test transaction propagation is {@link Propagation#NOT_SUPPORTED}, so
 * each service call really commits. The embedded H2 database name is generated once per class and
 * the schema is derived from the application entity mappings, per the approved
 * {@code embedded-h2-unique-db} fixture contract. Each method owns a UUID-coded bookable resource,
 * so no method can observe another method's rows.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class, ReservationSeriesDatabaseObligationsTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesDatabaseObligationsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");
    private static final int WEEKLY_OCCURRENCES = 3;

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb001db_" + UUID.randomUUID().toString().replace("-", "");

    @Autowired
    private ReservationService service;

    @Autowired
    private ResourceRepository resources;

    @Autowired
    private ReservationRepository reservations;

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
    // SCN-001#A2
    // "Exactly three reservation rows exist for the returned series identifier
    // and no additional row is created."
    // ---------------------------------------------------------------------
    @Test
    void scn001ExactlyThreeRowsExistForTheReturnedSeriesIdAndNoAdditionalRow() {
        BookableResource resource = newResource(false);
        String title = "scn001-series-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        assertEquals(0, persistedReservations(resource.getId()).size(),
                "precondition: the owned resource must have no reservations before the call");

        ReservationSeriesView view = createSeries(resource.getId(), title, startsAt, endsAt,
                WEEKLY_OCCURRENCES);

        assertNotNull(view, "the conflict-free series request must complete");
        assertNotNull(view.seriesId(),
                "the completed series must return the series identifier used by its rows");

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(WEEKLY_OCCURRENCES, rows.size(),
                "the store must hold exactly the three reservation rows created for the request, "
                        + "but saw " + rows.size());

        // The returned identifier, not a locally invented one, must own exactly three rows.
        assertEquals(WEEKLY_OCCURRENCES, countRowsForSeries(view.seriesId()),
                "exactly three reservation rows must exist for the returned series identifier "
                        + view.seriesId());
        assertTrue(rows.stream().allMatch(row -> view.seriesId().equals(row.seriesId())),
                "every persisted row of the request must carry the returned series identifier, "
                        + "but saw " + rows.stream().map(PersistedReservation::seriesId).toList());

        // "No additional row is created": the row count for the owned resource is unchanged at
        // three, so no fourth row of any kind was persisted for the request.
        assertEquals(WEEKLY_OCCURRENCES, persistedReservations(resource.getId()).size(),
                "no additional reservation row may be created beyond the three occurrences");
        assertTrue(rows.stream().allMatch(row -> title.equals(row.title())),
                "the only rows for the owned resource must belong to the requested series, but saw "
                        + rows.stream().map(PersistedReservation::title).toList());
    }

    // ---------------------------------------------------------------------
    // SCN-004#A3
    // "After the 409 zero rows exist for any occurrence of the series and the
    // pre-existing conflicting reservation is unchanged."
    // ---------------------------------------------------------------------
    @Test
    void scn004ConflictOnSecondOccurrenceCreatesNoRowsAndLeavesBlockerUnchanged() {
        BookableResource resource = newResource(false);
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 15, 9, 0, 0, 0, BERLIN);
        ZonedDateTime secondLocal = firstLocal.plusWeeks(1);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        // Precondition: an existing reservation occupies only the second weekly occurrence.
        String blockerTitle = "scn004-blocker-" + UUID.randomUUID();
        Reservation blocker = reservations.save(new Reservation(
                resource, "fixture-" + UUID.randomUUID(), blockerTitle,
                secondLocal.toInstant(), secondLocal.plusHours(1).toInstant(),
                ReservationStatus.CONFIRMED, FIXED_INSTANT));
        assertNotNull(blocker.getId(), "precondition: the conflicting reservation must be persisted");

        List<PersistedReservation> before = persistedReservations(resource.getId());
        assertEquals(1, before.size(),
                "precondition: only the second weekly occurrence must be occupied");
        assertEquals(blocker.getId(), before.get(0).id(),
                "precondition: the sole row must be the pre-existing conflicting reservation");
        PersistedReservation blockerBefore = before.get(0);

        String title = "scn004-series-" + UUID.randomUUID();
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> createSeries(resource.getId(), title, startsAt, endsAt, WEEKLY_OCCURRENCES));
        assertTrue(isConflict(failure),
                "the series whose second occurrence is occupied must be rejected as a 409 conflict, "
                        + "but got: " + failure);

        List<PersistedReservation> after = persistedReservations(resource.getId());

        // Zero rows exist for any occurrence of the rejected series.
        assertEquals(before.size(), after.size(),
                "after the conflict the store must contain zero new reservation rows for any "
                        + "occurrence of the series");
        assertTrue(after.stream().noneMatch(row -> title.equals(row.title())),
                "no row of the rejected series may exist for any occurrence, but saw "
                        + after.stream().map(PersistedReservation::title).toList());
        assertTrue(after.stream().allMatch(row -> row.seriesId() == null),
                "no occurrence of the rejected series may be persisted with series membership, "
                        + "but saw " + after.stream().map(PersistedReservation::seriesId).toList());

        // The pre-existing conflicting reservation is byte-for-byte unchanged.
        assertEquals(1, after.size(),
                "the pre-existing conflicting reservation must still be the only row");
        assertEquals(blockerBefore, after.get(0),
                "the pre-existing conflicting reservation must be unchanged after the 409");
    }

    // ---------------------------------------------------------------------
    // SCN-007#A3
    // "Only the cancelled occurrence's row changes; the other rows and their
    // shared series identifier are unchanged."
    // ---------------------------------------------------------------------
    @Test
    void scn007CancellingOneOccurrenceChangesOnlyThatRowAndKeepsTheSharedSeriesId() {
        BookableResource resource = newResource(true);
        assertTrue(resource.isApprovalRequired(),
                "precondition: the owned room must require approval before the series call");

        String title = "scn007-pending-" + UUID.randomUUID();
        String actor = "scn007-owner-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 2, 5, 10, 0, 0, 0, BERLIN);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        createSeries(resource.getId(), title, startsAt, endsAt, WEEKLY_OCCURRENCES, actor);

        List<PersistedReservation> before = persistedReservations(resource.getId());
        assertEquals(WEEKLY_OCCURRENCES, before.size(),
                "precondition: all three occurrences must be persisted before cancelling one");
        assertTrue(before.stream().allMatch(row -> row.status() == ReservationStatus.PENDING),
                "precondition: an approval-required series must be PENDING before the cancellation, "
                        + "but saw " + before.stream().map(PersistedReservation::status).toList());

        UUID seriesId = before.get(0).seriesId();
        assertNotNull(seriesId, "precondition: every occurrence must share a non-null seriesId");
        assertTrue(before.stream().allMatch(row -> seriesId.equals(row.seriesId())),
                "precondition: all three occurrences must share the identical seriesId, but saw "
                        + before.stream().map(PersistedReservation::seriesId).toList());

        // The second occurrence (ordered by startsAt) is the one cancelled through the existing
        // reservation endpoint.
        PersistedReservation cancelledBefore = before.get(1);
        ReservationView cancelled = service.cancel(cancelledBefore.id(),
                new UsernamePasswordAuthenticationToken(actor, "", List.of()));
        assertNotNull(cancelled,
                "the existing cancellation endpoint must succeed for one occurrence of the series");

        List<PersistedReservation> after = persistedReservations(resource.getId());
        assertEquals(WEEKLY_OCCURRENCES, after.size(),
                "cancelling one occurrence must not add or remove any row of the series");

        Map<Long, PersistedReservation> beforeById = before.stream()
                .collect(Collectors.toMap(PersistedReservation::id, Function.identity()));
        Map<Long, PersistedReservation> afterById = after.stream()
                .collect(Collectors.toMap(PersistedReservation::id, Function.identity()));
        assertEquals(beforeById.keySet(), afterById.keySet(),
                "the cancellation must not change the identity of any series row");

        for (Map.Entry<Long, PersistedReservation> entry : beforeById.entrySet()) {
            Long id = entry.getKey();
            PersistedReservation oldRow = entry.getValue();
            PersistedReservation newRow = afterById.get(id);
            if (id.equals(cancelledBefore.id())) {
                // The cancelled occurrence changes only its status; every other field is intact.
                assertEquals(cancelledBefore.startsAt(), newRow.startsAt(),
                        "the cancelled occurrence must keep its start instant");
                assertEquals(cancelledBefore.endsAt(), newRow.endsAt(),
                        "the cancelled occurrence must keep its end instant");
                assertEquals(cancelledBefore.title(), newRow.title(),
                        "the cancelled occurrence must keep its title");
                assertEquals(cancelledBefore.bookedBy(), newRow.bookedBy(),
                        "the cancelled occurrence must keep its owner");
                assertEquals(seriesId, newRow.seriesId(),
                        "the cancelled occurrence must keep its shared series identifier");
                assertEquals(ReservationStatus.CANCELLED, newRow.status(),
                        "the cancelled occurrence's row must change to CANCELLED, but saw "
                                + newRow.status());
            } else {
                // Every other row is unchanged in full, including its status and series identifier.
                assertEquals(oldRow, newRow,
                        "only the cancelled occurrence's row may change, but row " + id + " changed");
                assertEquals(seriesId, newRow.seriesId(),
                        "the other occurrences must keep the unchanged shared series identifier");
            }
        }

        assertEquals(1, after.stream()
                        .filter(row -> row.status() == ReservationStatus.CANCELLED).count(),
                "exactly one row may be CANCELLED after cancelling one occurrence");
        assertTrue(after.stream().allMatch(row -> seriesId.equals(row.seriesId())),
                "all remaining rows must keep the identical shared series identifier, but saw "
                        + after.stream().map(PersistedReservation::seriesId).toList());
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource(boolean approvalRequired) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "WB001 QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true,
                approvalRequired);
        return resources.save(resource);
    }

    private ReservationSeriesView createSeries(long resourceId, String title, OffsetDateTime startsAt,
                                               OffsetDateTime endsAt, int occurrences) {
        return createSeries(resourceId, title, startsAt, endsAt, occurrences,
                "qa-" + UUID.randomUUID());
    }

    private ReservationSeriesView createSeries(long resourceId, String title, OffsetDateTime startsAt,
                                               OffsetDateTime endsAt, int occurrences, String actor) {
        ReservationSeriesInput input = new ReservationSeriesInput(resourceId, title, startsAt, endsAt,
                SERIES_TIMEZONE, SERIES_FREQUENCY, occurrences);
        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain its supplied offset");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain its supplied offset");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: timezone must be retained");
        assertEquals(occurrences, input.occurrences(),
                "input control: the requested occurrence count must be retained");
        return service.createSeries(input, actor);
    }

    /** Reads the committed rows for one owned resource through a brand-new EntityManager. */
    private List<PersistedReservation> persistedReservations(long resourceId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.id, r.startsAt, r.endsAt, r.seriesId, r.title, r.status, r.bookedBy "
                                    + "from Reservation r where r.resource.id = :resourceId "
                                    + "order by r.startsAt, r.id", Object[].class)
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
                        (ReservationStatus) row[5],
                        (String) row[6]));
            }
            return result;
        } finally {
            entityManager.close();
        }
    }

    /** Counts every committed row in the whole store that carries the given series identifier. */
    private long countRowsForSeries(UUID seriesId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return entityManager.createQuery(
                            "select count(r) from Reservation r where r.seriesId = :seriesId",
                            Long.class)
                    .setParameter("seriesId", seriesId)
                    .getSingleResult();
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

    private static boolean isConflict(RuntimeException failure) {
        if (failure instanceof ReservationConflictException) {
            return true;
        }
        if (failure instanceof ApiException apiException) {
            return apiException.status() == HttpStatus.CONFLICT;
        }
        return false;
    }

    private record PersistedReservation(Long id, Instant startsAt, Instant endsAt, UUID seriesId,
                                        String title, ReservationStatus status, String bookedBy) {
    }
}
