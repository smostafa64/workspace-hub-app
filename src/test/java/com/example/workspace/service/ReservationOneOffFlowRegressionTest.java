package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationConflictException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression verification for the pre-existing one-time reservation flow (SCN-010,
 * assertions SCN-010#A1, SCN-010#A2 and SCN-010#A3).
 *
 * <p>These tests exercise the <em>unchanged</em> one-off flow through the real application
 * wiring: the real {@link ReservationService} bean and the real Spring Data repositories backed
 * by an isolated embedded H2 store. The test transaction propagation is
 * {@link Propagation#NOT_SUPPORTED}, so every service call really commits. Persisted effects are
 * read back through a brand-new JPA {@link EntityManager} after the service transaction, which is
 * the approved "Existing behavior regression tests observed in the embedded relational store"
 * boundary. The database name is generated once per class and each method owns a UUID-coded
 * bookable resource, so no method can observe another method's rows.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class, ReservationOneOffFlowRegressionTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationOneOffFlowRegressionTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");
    private static final long ONE_HOUR_SECONDS = 3600L;

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb004db_" + UUID.randomUUID().toString().replace("-", "");

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
    // SCN-010#A1
    // "The existing one-time reservation flow still succeeds for a
    //  non-conflicting booking and needs no series fields."
    // ---------------------------------------------------------------------
    @Test
    void scn010ExistingOneOffReservationFlowSucceedsWithoutSeriesFields() {
        BookableResource resource = newResource(false);
        assertFalse(resource.isApprovalRequired(),
                "precondition: the owned room must not require approval");

        Instant start = Instant.parse("2030-01-05T09:00:00Z");
        Instant end = start.plusSeconds(ONE_HOUR_SECONDS);
        String title = "scn010-one-off-" + UUID.randomUUID();
        String actor = "scn010-owner-" + UUID.randomUUID();

        assertEquals(0, persistedReservations(resource.getId()).size(),
                "precondition: the owned room must have no reservations before the call");

        // Control: the fixture itself is a valid, genuinely future one-hour interval. The
        // pre-existing one-time input type carries no series fields at all.
        assertTrue(start.isAfter(FIXED_INSTANT),
                "control: the requested interval must be in the future relative to the fixed clock");
        assertEquals(ONE_HOUR_SECONDS, end.getEpochSecond() - start.getEpochSecond(),
                "control: the requested one-off must last one hour");

        ReservationView view = service.create(
                new BookingInput(resource.getId(), title, start, end), actor);
        assertNotNull(view, "the existing one-time reservation flow must still succeed");
        assertEquals(ReservationStatus.CONFIRMED, view.status(),
                "a non-conflicting one-off on a room without approval must still be CONFIRMED, "
                        + "but saw " + view.status());
        assertEquals(resource.getId(), view.resourceId(), "the one-off must belong to the owned room");
        assertEquals(actor, view.bookedBy(), "the one-off must be attributed to the requesting actor");
        assertEquals(title, view.title(), "the one-off must keep its title");
        assertEquals(start, view.startsAt(), "the one-off must keep its start instant");
        assertEquals(end, view.endsAt(), "the one-off must keep its end instant");

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(1, rows.size(),
                "exactly one reservation row must be persisted for the successful one-off, "
                        + "but saw " + rows.size());
        PersistedReservation row = rows.get(0);
        assertEquals(title, row.title(), "the persisted one-off must keep its title");
        assertEquals(start, row.startsAt(), "the persisted one-off must keep its start instant");
        assertEquals(end, row.endsAt(), "the persisted one-off must keep its end instant");
        assertEquals(ReservationStatus.CONFIRMED, row.status(),
                "the persisted one-off must be CONFIRMED, but saw " + row.status());
        assertEquals(actor, row.bookedBy(), "the persisted one-off must keep its actor");
        assertNull(row.seriesId(),
                "the existing one-off flow must not require or stamp series membership, "
                        + "but saw seriesId " + row.seriesId());
    }

    // ---------------------------------------------------------------------
    // SCN-010#A2
    // "A one-off in an approval-required room still produces a PENDING
    //  booking and the pre-existing one-time behaviour is unchanged."
    // ---------------------------------------------------------------------
    @Test
    void scn010OneOffOnApprovalRequiredRoomStillProducesPendingBooking() {
        BookableResource resource = newResource(true);
        assertTrue(resource.isApprovalRequired(),
                "precondition: the owned room must require approval before the call");

        // Same shape as the SCN-010#A1 baseline; only the room's approval requirement differs.
        Instant start = Instant.parse("2030-01-07T09:00:00Z");
        Instant end = start.plusSeconds(ONE_HOUR_SECONDS);
        String title = "scn010-approval-one-off-" + UUID.randomUUID();
        String actor = "scn010-owner-" + UUID.randomUUID();

        assertEquals(0, persistedReservations(resource.getId()).size(),
                "precondition: the owned approval-required room must have no reservations");

        ReservationView view = service.create(new BookingInput(resource.getId(), title, start, end), actor);
        assertNotNull(view,
                "the existing one-time reservation flow must still succeed on an approval-required room");
        assertEquals(ReservationStatus.PENDING, view.status(),
                "a one-off in an approval-required room must still produce a PENDING booking, "
                        + "but saw " + view.status());
        assertEquals(resource.getId(), view.resourceId(), "the one-off must belong to the owned room");
        assertEquals(actor, view.bookedBy(), "the one-off must be attributed to the requesting actor");
        assertEquals(title, view.title(), "the one-off must keep its title");
        assertEquals(start, view.startsAt(), "the one-off must keep its start instant");
        assertEquals(end, view.endsAt(), "the one-off must keep its end instant");

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(1, rows.size(),
                "exactly one reservation row must be persisted for the pending one-off, "
                        + "but saw " + rows.size());
        PersistedReservation row = rows.get(0);
        assertEquals(ReservationStatus.PENDING, row.status(),
                "the persisted one-off in an approval-required room must be PENDING, "
                        + "but saw " + row.status());
        assertEquals(title, row.title(), "the persisted one-off must keep its title");
        assertEquals(start, row.startsAt(), "the persisted one-off must keep its start instant");
        assertEquals(end, row.endsAt(), "the persisted one-off must keep its end instant");
        assertEquals(actor, row.bookedBy(), "the persisted one-off must keep its actor");
        assertNull(row.seriesId(),
                "the one-time behaviour must remain unchanged and carry no series membership, "
                        + "but saw seriesId " + row.seriesId());
    }

    // ---------------------------------------------------------------------
    // SCN-010#A3
    // "A one-off reservation attempt overlapping an existing booking is still
    //  refused and creates no new booking, so the preserved existing booking
    //  rules remain in force for one-time requests."
    // ---------------------------------------------------------------------
    @Test
    void scn010OverlappingOneOffIsStillRefusedAndCreatesNoNewBooking() {
        BookableResource resource = newResource(false);

        Instant blockedStart = Instant.parse("2030-01-08T10:00:00Z");
        Instant blockedEnd = blockedStart.plusSeconds(ONE_HOUR_SECONDS);
        String blockerTitle = "scn010-blocker-" + UUID.randomUUID();

        // Fixture: the pre-existing booking already occupies 10:00-11:00 on the owned room.
        Reservation blocker = reservations.save(new Reservation(
                resource, "fixture-" + UUID.randomUUID(), blockerTitle,
                blockedStart, blockedEnd, ReservationStatus.CONFIRMED, FIXED_INSTANT));
        assertNotNull(blocker.getId(), "precondition: the existing booking must be persisted");

        List<PersistedReservation> before = persistedReservations(resource.getId());
        assertEquals(1, before.size(),
                "precondition: the owned room must hold exactly the one existing booking");
        PersistedReservation blockerBefore = before.get(0);
        assertEquals(blocker.getId(), blockerBefore.id(),
                "precondition: the sole row must be the pre-existing booking");

        // The attempt really overlaps the existing booking (10:30-11:30).
        Instant overlapStart = blockedStart.plusSeconds(1800);
        Instant overlapEnd = blockedEnd.plusSeconds(1800);
        String refusedTitle = "scn010-overlap-" + UUID.randomUUID();
        BookingInput overlapping =
                new BookingInput(resource.getId(), refusedTitle, overlapStart, overlapEnd);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.create(overlapping, "scn010-owner-" + UUID.randomUUID()),
                "a one-off overlapping an existing booking must still be refused");
        assertTrue(isConflict(failure),
                "the preserved existing booking rule must refuse the overlapping one-off as a "
                        + "conflict, but got: " + failure);

        List<PersistedReservation> after = persistedReservations(resource.getId());
        assertEquals(before.size(), after.size(),
                "the refused overlapping attempt must create no new booking, but the row count "
                        + "changed from " + before.size() + " to " + after.size());
        assertEquals(blockerBefore, after.get(0),
                "the pre-existing booking must be completely unchanged by the refused attempt");
        assertTrue(after.stream().noneMatch(row -> refusedTitle.equals(row.title())),
                "no row of the refused overlapping attempt may be persisted, but saw "
                        + after.stream().map(PersistedReservation::title).toList());

        // Nearby valid control: the same room, same duration and same request shape as the refused
        // attempt, differing only in that this interval does not overlap the existing booking.
        Instant freeStart = blockedEnd.plusSeconds(1800);
        Instant freeEnd = freeStart.plusSeconds(ONE_HOUR_SECONDS);
        String controlTitle = "scn010-control-" + UUID.randomUUID();
        ReservationView control = service.create(
                new BookingInput(resource.getId(), controlTitle, freeStart, freeEnd),
                "scn010-owner-" + UUID.randomUUID());
        assertNotNull(control,
                "control: a non-overlapping one-off on the same room must still succeed");
        assertEquals(ReservationStatus.CONFIRMED, control.status(),
                "control: the non-overlapping one-off must be CONFIRMED, but saw "
                        + control.status());

        List<PersistedReservation> afterControl = persistedReservations(resource.getId());
        assertEquals(2, afterControl.size(),
                "control: the valid non-overlapping request must persist exactly one new row");
        assertTrue(afterControl.stream().anyMatch(row -> controlTitle.equals(row.title())),
                "control: the non-overlapping request's row must be persisted");
        assertTrue(afterControl.stream()
                        .anyMatch(row -> blockerTitle.equals(row.title())),
                "control: the pre-existing booking must remain persisted");
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource(boolean approvalRequired) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "WB004 QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true,
                approvalRequired);
        return resources.save(resource);
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
