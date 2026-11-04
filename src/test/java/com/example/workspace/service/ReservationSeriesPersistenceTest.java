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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real JPA/H2 persistence boundary for reservation series creation.
 *
 * <p>Every test drives the production {@link ReservationService} in its own committed
 * transaction (the test method itself is not transactional) and then reads the persisted rows
 * through JPQL, so inserted and rolled-back state is observed from the database rather than from
 * the service's return value.</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Import({ReservationService.class, ReservationSeriesPersistenceTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesPersistenceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final Duration OCCURRENCE_DURATION = Duration.ofMinutes(60);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @org.springframework.context.annotation.Primary
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
     * SCN-001#A2 - exactly three reservation rows for the submitted resourceId and title.
     */
    @Test
    void storesExactlyThreeRowsForSubmittedResourceAndTitle() {
        BookableResource room = createRoom("SCN001-A2", true);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", firstStart, 3), "alice");

        assertEquals(3L, rowCount(room.getId(), "Weekly planning"),
                "after the series is created the database must hold exactly three rows for the submitted resourceId and title");
        assertEquals(3, rowsFor(room.getId()).size(),
                "the submitted room must not hold any additional reservation rows");
    }

    /**
     * SCN-001#A4 - all three persisted bookings share one non-null seriesId.
     */
    @Test
    void allPersistedBookingsOfTheSeriesShareOneNonNullSeriesId() {
        BookableResource room = createRoom("SCN001-A4", true);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", firstStart, 3), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(3, rows.size(), "the fixture requires exactly three persisted bookings");
        for (Reservation row : rows) {
            assertNotNull(row.getSeriesId(), "every booking of a series must persist a non-null seriesId");
        }
        Set<UUID> distinctSeriesIds = rows.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toSet());
        assertEquals(1, distinctSeriesIds.size(),
                "all three bookings must carry the same seriesId, found " + distinctSeriesIds);
    }

    /**
     * SCN-001#A5 - each occurrence occupies its own room interval, so a later overlapping
     * one-off request is rejected as an overlap.
     */
    @Test
    void eachOccurrenceOccupiesItsOwnIntervalAndBlocksALaterOverlap() {
        BookableResource room = createRoom("SCN001-A5", true);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", firstStart, 3), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(3, rows.size(), "each requested occurrence must be persisted as its own room booking");
        for (int index = 0; index < 3; index++) {
            Instant expectedStart = firstStart.plusWeeks(index).toInstant();
            assertEquals(expectedStart, rows.get(index).getStartsAt(),
                    "occurrence " + (index + 1) + " must persist its own interval start");
            assertEquals(expectedStart.plus(OCCURRENCE_DURATION), rows.get(index).getEndsAt(),
                    "occurrence " + (index + 1) + " must occupy the full 60 minute interval");
        }

        Instant occurrenceTwoStart = firstStart.plusWeeks(1).toInstant();
        Instant probeStart = occurrenceTwoStart.plus(Duration.ofMinutes(10));
        Instant probeEnd = occurrenceTwoStart.plus(Duration.ofMinutes(40));
        assertTrue(reservationRepository.existsConflictingReservation(room.getId(), probeStart, probeEnd),
                "the persisted occurrence 2 interval must be visible to the overlap query");

        BookingInput oneOff = new BookingInput(room.getId(), "Ad hoc", probeStart, probeEnd);
        assertThrows(ApiException.class, () -> service.create(oneOff, "bob"),
                "a one-off request overlapping occurrence 2 must be rejected as an overlapping reservation");
        assertEquals(3, rowsFor(room.getId()).size(),
                "the rejected one-off request must not persist a fourth row");
    }

    /**
     * SCN-005#A1 - a series for an inactive resource is rejected and persists zero rows.
     */
    @Test
    void inactiveResourceSeriesIsRejectedAndPersistsNoRows() {
        BookableResource activeControl = createRoom("SCN005-ACTIVE", true);
        BookableResource inactive = createRoom("SCN005-INACTIVE", false);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        ReservationSeriesView control = service.createSeries(
                weeklySeries(activeControl.getId(), "Quarterly review", firstStart, 3), "alice");
        assertEquals(3, control.reservations().size(),
                "control fixture: the identical request against an active room must be accepted");
        assertEquals(3, rowsFor(activeControl.getId()).size(),
                "control fixture: the active room must hold three rows");

        ReservationSeriesInput inactiveRequest = weeklySeries(inactive.getId(), "Quarterly review", firstStart, 3);
        assertThrows(RuntimeException.class, () -> service.createSeries(inactiveRequest, "alice"),
                "a series request for an inactive resource must be rejected");

        assertEquals(0L, rowCount(inactive.getId(), "Quarterly review"),
                "an inactive resource must not receive any booking");
        assertEquals(0, rowsFor(inactive.getId()).size(),
                "an inactive resource must not receive any reservation row");
    }

    /**
     * SCN-006#A2 - persisted occurrence starts are distinct UTC instants that each map back to
     * the same local wall-clock time in the supplied zone.
     */
    @Test
    void storedStartInstantsAreDistinctUtcInstantsAtTheSameLocalWallClockTime() {
        BookableResource room = createRoom("SCN006-A2", true);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 3, 18, 9, 0, 0, 0, BERLIN);

        service.createSeries(weeklySeries(room.getId(), "Weekly sync", firstStart, 4), "alice");

        List<Instant> starts = rowsFor(room.getId()).stream()
                .map(Reservation::getStartsAt)
                .collect(Collectors.toList());
        assertEquals(4, starts.size(), "the fixture requires four persisted occurrence rows");
        assertEquals(4, new HashSet<>(starts).size(),
                "every occurrence must be stored as a distinct UTC instant, found " + starts);

        List<LocalTime> localTimes = starts.stream()
                .map(instant -> instant.atZone(BERLIN).toLocalTime())
                .collect(Collectors.toList());
        assertEquals(Collections.nCopies(4, LocalTime.of(9, 0)), localTimes,
                "every stored instant must map back to 09:00 Europe/Berlin");

        assertEquals(List.of(ZoneOffset.ofHours(1), ZoneOffset.ofHours(1),
                        ZoneOffset.ofHours(2), ZoneOffset.ofHours(2)),
                starts.stream().map(instant -> instant.atZone(BERLIN).getOffset()).collect(Collectors.toList()),
                "the persisted instants must cross the daylight-saving transition with shifting offsets");
    }

    /**
     * SCN-006#A4 - one booking per requested occurrence date across a daylight-saving transition.
     */
    @Test
    void createsOneBookingForEveryRequestedOccurrenceDateAcrossDaylightSavingTransition() {
        BookableResource room = createRoom("SCN006-A4", true);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 3, 18, 9, 0, 0, 0, BERLIN);
        int requestedOccurrences = 4;

        service.createSeries(weeklySeries(room.getId(), "Weekly sync", firstStart, requestedOccurrences), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(requestedOccurrences, rows.size(),
                "no occurrence may be dropped when the series span crosses a daylight-saving transition");
        Set<LocalDate> occurrenceDates = rows.stream()
                .map(row -> row.getStartsAt().atZone(BERLIN).toLocalDate())
                .collect(Collectors.toSet());
        assertEquals(Set.of(LocalDate.of(2030, 3, 18), LocalDate.of(2030, 3, 25),
                        LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 8)),
                occurrenceDates, "each requested occurrence date must be persisted exactly once");
    }

    /**
     * SCN-009#A2 - after the conflict the database holds zero rows from the rejected series,
     * including the non-conflicting occurrences.
     */
    @Test
    void rejectedSeriesLeavesNoRowsIncludingNonConflictingOccurrences() {
        BookableResource room = createRoom("SCN009-ROOM", true);
        BookableResource controlRoom = createRoom("SCN009-CONTROL", true);
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        String seriesTitle = "Monthly review";

        Instant occurrenceOneStart = firstStart.toInstant();
        Instant occurrenceTwoStart = firstStart.plusWeeks(1).toInstant();
        Instant occurrenceThreeStart = firstStart.plusWeeks(2).toInstant();

        reservationRepository.save(new Reservation(room, "carol", "Existing booking",
                occurrenceTwoStart.plus(Duration.ofMinutes(10)),
                occurrenceTwoStart.plus(Duration.ofMinutes(40)),
                ReservationStatus.CONFIRMED, FIXED_NOW));

        ReservationSeriesView control = service.createSeries(
                weeklySeries(controlRoom.getId(), seriesTitle, firstStart, 3), "alice");
        assertEquals(3, control.reservations().size(),
                "control fixture: without the overlapping booking the identical series must be accepted");

        ReservationSeriesInput conflictingRequest = weeklySeries(room.getId(), seriesTitle, firstStart, 3);
        assertThrows(RuntimeException.class, () -> service.createSeries(conflictingRequest, "alice"),
                "a series conflicting on its second occurrence must be rejected");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(1, rows.size(),
                "only the pre-existing conflicting booking may remain after the rollback, found " + rows.size());
        assertEquals("Existing booking", rows.get(0).getTitle(),
                "the pre-existing booking must not be removed by the rollback");
        assertEquals(0L, rowCount(room.getId(), seriesTitle),
                "no reservation row from the rejected series may be persisted");
        assertFalse(rows.stream().anyMatch(row -> row.getStartsAt().equals(occurrenceOneStart)),
                "the non-conflicting occurrence 1 must not be persisted");
        assertFalse(rows.stream().anyMatch(row -> row.getStartsAt().equals(occurrenceThreeStart)),
                "the non-conflicting occurrence 3 must not be persisted");
    }

    private BookableResource createRoom(String code, boolean active) {
        return resourceRepository.save(
                new BookableResource(code, "Room " + code, ResourceType.ROOM, "HQ", 1, 8, active, false));
    }

    private ReservationSeriesInput weeklySeries(Long resourceId, String title, ZonedDateTime firstStart, int occurrences) {
        ZonedDateTime firstEnd = firstStart.plus(OCCURRENCE_DURATION);
        return new ReservationSeriesInput(resourceId, title,
                firstStart.toOffsetDateTime(), firstEnd.toOffsetDateTime(),
                BERLIN.getId(), "WEEKLY", occurrences);
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
