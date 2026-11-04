package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer obligations for reservation series scenarios SCN-010 .. SCN-013.
 *
 * <p>Each test drives the production {@link ReservationService} in its own committed transaction
 * (the test method itself is not transactional) and then reads the durable rows through JPQL, so
 * committed and rolled-back state is observed from the database rather than from the service's
 * return value. Every negative or side-effect assertion is paired with a control fixture that runs
 * the same request against a room that differs only in the property under test.</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Import({ReservationService.class, ReservationSeriesDatabaseContractTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesDatabaseContractTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final Duration OCCURRENCE_DURATION = Duration.ofMinutes(60);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return FIXED_CLOCK;
        }
    }

    @Autowired
    private ReservationService service;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from Reservation").executeUpdate();
            entityManager.createQuery("delete from BookableResource").executeUpdate();
        });
    }

    /**
     * SCN-010#A1 - a conflict on the last of twelve occurrences leaves zero rows from that series.
     *
     * <p>The control room receives the identical twelve-occurrence request and must persist twelve
     * rows, which proves the request itself is valid and that only the pre-seeded occurrence-12
     * overlap causes the rejection.</p>
     */
    @Test
    void seriesConflictingOnLastOfTwelveOccurrencesPersistsNoRows() {
        BookableResource contestedRoom = createRoom("SCN010-ROOM", true, false);
        BookableResource controlRoom = createRoom("SCN010-CONTROL", true, false);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        int occurrences = 12;
        String seriesTitle = "Weekly planning";
        Instant lastOccurrenceStart = firstStart.plusWeeks(occurrences - 1).toInstant();

        reservationRepository.save(new Reservation(contestedRoom, "carol", "Existing late booking",
                lastOccurrenceStart.plus(Duration.ofMinutes(10)),
                lastOccurrenceStart.plus(Duration.ofMinutes(40)),
                ReservationStatus.CONFIRMED, FIXED_NOW));

        ReservationSeriesView control = service.createSeries(
                weeklySeries(controlRoom.getId(), seriesTitle, firstStart, occurrences), "alice");
        assertEquals(occurrences, control.reservations().size(),
                "control fixture: the identical twelve-occurrence series must be accepted on a free room");
        assertEquals(occurrences, rowsFor(controlRoom.getId()).size(),
                "control fixture: the accepted series must persist one row per occurrence");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.createSeries(
                        weeklySeries(contestedRoom.getId(), seriesTitle, firstStart, occurrences), "alice"),
                "a series whose twelfth occurrence overlaps an existing booking must be rejected");
        assertConflictRejection(failure);

        List<Reservation> rows = rowsFor(contestedRoom.getId());
        assertEquals(1, rows.size(),
                "after the conflict on the last occurrence only the pre-existing booking may remain, found " + rows.size());
        assertEquals("Existing late booking", rows.get(0).getTitle(),
                "the pre-existing booking must not be removed by the rollback");
        assertEquals(0L, rowCount(contestedRoom.getId(), seriesTitle),
                "no reservation row from the rejected series may be persisted");

        Set<Instant> occurrenceStarts = new HashSet<>();
        for (int index = 0; index < occurrences; index++) {
            occurrenceStarts.add(firstStart.plusWeeks(index).toInstant());
        }
        assertFalse(rows.stream().anyMatch(row -> occurrenceStarts.contains(row.getStartsAt())),
                "none of the twelve occurrences, including the eleven non-conflicting ones, may be persisted");
    }

    /**
     * SCN-011#A1 - a series overlapping an existing one-off booking is rejected and creates no
     * bookings, while the identical request on a free control room is accepted.
     */
    @Test
    void seriesOverlappingExistingOneOffIsRejectedAndCreatesNoBookings() {
        BookableResource contestedRoom = createRoom("SCN011-ROOM", true, false);
        BookableResource controlRoom = createRoom("SCN011-CONTROL", true, false);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        String seriesTitle = "Weekly planning";
        Instant occurrenceOneStart = firstStart.toInstant();

        Reservation existing = reservationRepository.save(new Reservation(contestedRoom, "carol", "Existing one-off",
                occurrenceOneStart.plus(Duration.ofMinutes(10)),
                occurrenceOneStart.plus(Duration.ofMinutes(40)),
                ReservationStatus.CONFIRMED, FIXED_NOW));

        ReservationSeriesView control = service.createSeries(
                weeklySeries(controlRoom.getId(), seriesTitle, firstStart, 3), "alice");
        assertEquals(3, control.reservations().size(),
                "control fixture: the identical series must be accepted on a room without the one-off booking");
        assertEquals(3, rowsFor(controlRoom.getId()).size(),
                "control fixture: the accepted series must persist three rows");

        RuntimeException rejection = assertThrows(RuntimeException.class,
                () -> service.createSeries(
                        weeklySeries(contestedRoom.getId(), seriesTitle, firstStart, 3), "alice"),
                "a series whose first occurrence overlaps an existing one-off booking must be rejected");
        assertConflictRejection(rejection);

        List<Reservation> rows = rowsFor(contestedRoom.getId());
        assertEquals(1, rows.size(),
                "the rejected series must not add any row to the contested room, found " + rows.size());
        assertEquals(existing.getId(), rows.get(0).getId(),
                "the only remaining row must be the pre-existing one-off booking");
        assertEquals(0L, rowCount(contestedRoom.getId(), seriesTitle),
                "no reservation row from the rejected series may be persisted");
    }

    /**
     * SCN-011#A2 - the pre-existing one-off remains the only active reservation for the contested
     * room interval after the series is rejected.
     */
    @Test
    void existingOneOffRemainsTheOnlyActiveReservationForContestedInterval() {
        BookableResource contestedRoom = createRoom("SCN011-A2-ROOM", true, false);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        Instant occurrenceOneStart = firstStart.toInstant();
        Instant occurrenceOneEnd = occurrenceOneStart.plus(OCCURRENCE_DURATION);

        Reservation existing = reservationRepository.save(new Reservation(contestedRoom, "carol", "Existing one-off",
                occurrenceOneStart.plus(Duration.ofMinutes(10)),
                occurrenceOneStart.plus(Duration.ofMinutes(40)),
                ReservationStatus.CONFIRMED, FIXED_NOW));

        RuntimeException rejection = assertThrows(RuntimeException.class,
                () -> service.createSeries(
                        weeklySeries(contestedRoom.getId(), "Weekly planning", firstStart, 3), "alice"),
                "the overlapping series must be rejected before any occurrence is persisted");
        assertConflictRejection(rejection);

        List<Reservation> allRows = rowsFor(contestedRoom.getId());
        assertEquals(1, allRows.size(),
                "the rejected series must not persist additional rows for the contested room");

        List<Reservation> activeForInterval = allRows.stream()
                .filter(row -> row.getStatus() != ReservationStatus.CANCELLED)
                .filter(row -> overlaps(row, occurrenceOneStart, occurrenceOneEnd))
                .collect(Collectors.toList());
        assertEquals(1, activeForInterval.size(),
                "exactly one active reservation may cover the contested interval, found " + activeForInterval.size());

        Reservation survivor = activeForInterval.get(0);
        assertEquals(existing.getId(), survivor.getId(),
                "the surviving reservation must be the pre-existing one-off booking");
        assertEquals("carol", survivor.getBookedBy(),
                "the surviving reservation must still belong to its original booker");
        assertEquals("Existing one-off", survivor.getTitle(),
                "the surviving reservation must keep its original title");
        assertEquals(ReservationStatus.CONFIRMED, survivor.getStatus(),
                "the surviving reservation must remain an active confirmed booking");
        assertEquals(existing.getStartsAt(), survivor.getStartsAt(),
                "the surviving reservation must keep its original interval start");
        assertEquals(existing.getEndsAt(), survivor.getEndsAt(),
                "the surviving reservation must keep its original interval end");
    }

    /**
     * SCN-012#A2 - every persisted row of a series stores the same seriesId value.
     */
    @Test
    void eachPersistedSeriesBookingRowStoresTheSameSeriesId() {
        BookableResource room = createRoom("SCN012-A2", true, false);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", firstStart, 3), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(3, rows.size(), "the fixture requires three persisted series rows");
        for (Reservation row : rows) {
            assertNotNull(row.getSeriesId(),
                    "every persisted series booking must store a non-null seriesId: " + row);
        }
        Set<UUID> storedSeriesIds = rows.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toSet());
        assertEquals(1, storedSeriesIds.size(),
                "all persisted rows of one series must store the same seriesId, found " + storedSeriesIds);
    }

    /**
     * SCN-012#A3 - a one-off reservation created through the existing service path persists a null
     * seriesId and otherwise remains a valid confirmed booking.
     */
    @Test
    void oneOffReservationPersistsNullSeriesId() {
        BookableResource room = createRoom("SCN012-A3", true, false);
        Instant start = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN).toInstant();
        Instant end = start.plus(OCCURRENCE_DURATION);

        service.create(new BookingInput(room.getId(), "One-off", start, end), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(1, rows.size(), "the one-off booking must persist exactly one reservation row");
        Reservation row = rows.get(0);
        assertNull(row.getSeriesId(), "a one-off reservation must persist a null seriesId");
        assertEquals(ReservationStatus.CONFIRMED, row.getStatus(),
                "the one-off row must remain a valid confirmed booking");
        assertEquals(start, row.getStartsAt(), "the one-off row must keep the requested start instant");
        assertEquals(end, row.getEndsAt(), "the one-off row must keep the requested end instant");
    }

    /**
     * SCN-012#A4 - persisted records of series bookings carry the series identifier, and one-off
     * reservations remain valid records with a null seriesId.
     */
    @Test
    void seriesRowsCarrySeriesIdWhileOneOffRowsRemainValidWithNullSeriesId() {
        BookableResource seriesRoom = createRoom("SCN012-A4-SERIES", true, false);
        BookableResource oneOffRoom = createRoom("SCN012-A4-ONEOFF", true, false);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        Instant oneOffStart = ZonedDateTime.of(2030, 5, 7, 9, 0, 0, 0, BERLIN).toInstant();

        service.createSeries(weeklySeries(seriesRoom.getId(), "Weekly planning", firstStart, 3), "alice");
        service.create(new BookingInput(oneOffRoom.getId(), "One-off", oneOffStart,
                oneOffStart.plus(OCCURRENCE_DURATION)), "alice");

        List<Reservation> seriesRows = rowsFor(seriesRoom.getId());
        assertEquals(3, seriesRows.size(), "the fixture requires three persisted series rows");
        UUID seriesId = seriesRows.get(0).getSeriesId();
        assertNotNull(seriesId, "a persisted series booking must carry a series identifier");
        for (Reservation row : seriesRows) {
            assertEquals(seriesId, row.getSeriesId(),
                    "every persisted series booking record must carry the same series identifier");
            assertEquals(ReservationStatus.CONFIRMED, row.getStatus(),
                    "a persisted series booking record must remain valid");
        }
        assertEquals(0L, rowCount(oneOffRoom.getId(), "Weekly planning"),
                "series bookings must not leak into the one-off room");

        List<Reservation> oneOffRows = rowsFor(oneOffRoom.getId());
        assertEquals(1, oneOffRows.size(), "the one-off booking must persist exactly one row");
        Reservation oneOff = oneOffRows.get(0);
        assertNull(oneOff.getSeriesId(),
                "a one-off reservation must remain a valid record with a null seriesId");
        assertEquals("One-off", oneOff.getTitle(), "the one-off row must keep its submitted title");
        assertEquals(oneOffStart, oneOff.getStartsAt(), "the one-off row must keep the submitted start instant");
        assertEquals(oneOffStart.plus(OCCURRENCE_DURATION), oneOff.getEndsAt(),
                "the one-off row must keep the submitted end instant");
    }

    /**
     * SCN-013#A1 - for a room that requires approval, every occurrence of the series is created in
     * PENDING status; the control room does not require approval and yields confirmed occurrences.
     */
    @Test
    void approvalRequiredRoomCreatesPendingOccurrencesForEveryOccurrence() {
        BookableResource approvalRoom = createRoom("SCN013-A1-APPROVAL", true, true);
        BookableResource openRoom = createRoom("SCN013-A1-OPEN", true, false);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        String seriesTitle = "Weekly planning";

        ReservationSeriesView control = service.createSeries(
                weeklySeries(openRoom.getId(), seriesTitle, firstStart, 3), "alice");
        assertEquals(3, control.reservations().size(),
                "control fixture: the series must be created for a room that does not require approval");
        assertTrue(control.reservations().stream().allMatch(view -> view.status() == ReservationStatus.CONFIRMED),
                "control fixture: without an approval requirement every occurrence must be confirmed");

        ReservationSeriesView created = service.createSeries(
                weeklySeries(approvalRoom.getId(), seriesTitle, firstStart, 3), "alice");
        assertEquals(3, created.reservations().size(),
                "every occurrence of the series must be created for an approval-required room");
        assertTrue(created.reservations().stream().allMatch(view -> view.status() == ReservationStatus.PENDING),
                "every reported occurrence of an approval-required series must be PENDING, got "
                        + created.reservations().stream().map(view -> view.status()).collect(Collectors.toList()));

        List<Reservation> rows = rowsFor(approvalRoom.getId());
        assertEquals(3, rows.size(), "every occurrence must be persisted for the approval-required room");
        for (Reservation row : rows) {
            assertEquals(ReservationStatus.PENDING, row.getStatus(),
                    "occurrence starting " + row.getStartsAt() + " must be persisted as PENDING");
        }
    }

    /**
     * SCN-013#A2 - every occurrence of an approval-required series persists the PENDING approval
     * state in the database.
     */
    @Test
    void allSeriesOccurrencesPersistPendingApprovalStateInTheDatabase() {
        BookableResource approvalRoom = createRoom("SCN013-A2", true, true);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        service.createSeries(weeklySeries(approvalRoom.getId(), "Weekly planning", firstStart, 3), "alice");

        Long pendingRows = entityManager.createQuery(
                        "select count(r) from Reservation r where r.resource.id = :resourceId and r.status = :status",
                        Long.class)
                .setParameter("resourceId", approvalRoom.getId())
                .setParameter("status", ReservationStatus.PENDING)
                .getSingleResult();
        assertEquals(3L, pendingRows.longValue(),
                "all three occurrences must be persisted with the PENDING approval state");

        List<Reservation> rows = rowsFor(approvalRoom.getId());
        assertEquals(3, rows.size(), "every occurrence of the series must be persisted");
        for (Reservation row : rows) {
            assertEquals(ReservationStatus.PENDING, row.getStatus(),
                    "the stored occurrence starting " + row.getStartsAt() + " must be PENDING");
        }
    }

    /**
     * The approved assertions require the rejection to be a conflict (409 semantics), not an
     * unrelated validation or infrastructure failure. When the service surfaces an
     * {@link ApiException} its status is checked directly; otherwise the exception type itself
     * must identify a conflict.
     */
    private void assertConflictRejection(RuntimeException failure) {
        boolean apiConflict =
                failure instanceof ApiException && ((ApiException) failure).status() == HttpStatus.CONFLICT;
        boolean conflictTyped =
                failure.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("conflict");
        assertTrue(apiConflict || conflictTyped,
                "the series rejection must be a conflict rejection, was "
                        + failure.getClass().getName() + ": " + failure.getMessage());
    }

    private BookableResource createRoom(String code, boolean active, boolean approvalRequired) {
        return resourceRepository.save(new BookableResource(code, "Room " + code, ResourceType.ROOM,
                "HQ", 1, 8, active, approvalRequired));
    }

    private ReservationSeriesInput weeklySeries(Long resourceId, String title, ZonedDateTime firstStart,
                                                int occurrences) {
        ZonedDateTime firstEnd = firstStart.plus(OCCURRENCE_DURATION);
        return new ReservationSeriesInput(resourceId, title,
                firstStart.toOffsetDateTime(), firstEnd.toOffsetDateTime(),
                BERLIN.getId(), "WEEKLY", occurrences);
    }

    private boolean overlaps(Reservation row, Instant start, Instant end) {
        return row.getStartsAt().isBefore(end) && start.isBefore(row.getEndsAt());
    }

    private List<Reservation> rowsFor(long resourceId) {
        return entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId order by r.startsAt",
                        Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList();
    }

    private long rowCount(long resourceId, String title) {
        return entityManager.createQuery(
                        "select count(r) from Reservation r where r.resource.id = :resourceId and r.title = :title",
                        Long.class)
                .setParameter("resourceId", resourceId)
                .setParameter("title", title)
                .getSingleResult();
    }
}
