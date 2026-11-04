package com.example.workspace.service;

import com.example.workspace.api.ReservationSeriesInput;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer obligations for SCN-014: cancelling one occurrence of an existing three-occurrence
 * series must not disturb the other occurrences.
 *
 * <p>Each test drives the production {@link ReservationService} in its own committed transaction
 * (the test method itself is not transactional) and then reads the durable rows through JPQL, so
 * the post-cancellation state is observed from the database rather than from the cancellation
 * response. "Active" means a persisted reservation whose status is not
 * {@link ReservationStatus#CANCELLED}; {@code PENDING} is still an active hold.</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Import({ReservationService.class, ReservationSeriesCancellationDatabaseTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesCancellationDatabaseTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final Duration OCCURRENCE_DURATION = Duration.ofMinutes(60);
    private static final ZonedDateTime FIRST_START = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

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
     * SCN-014#A2 - after cancelling occurrence 2, occurrences 1 and 3 remain and retain the same
     * seriesId they had before the cancellation.
     */
    @Test
    void cancellingMiddleOccurrenceKeepsRemainingOccurrencesWithTheSameSeriesId() {
        BookableResource room = createRoom("SCN014-A2");
        Instant occurrenceOneStart = FIRST_START.toInstant();
        Instant occurrenceTwoStart = FIRST_START.plusWeeks(1).toInstant();
        Instant occurrenceThreeStart = FIRST_START.plusWeeks(2).toInstant();

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", 3), "alice");

        List<Reservation> beforeCancellation = rowsFor(room.getId());
        assertEquals(3, beforeCancellation.size(),
                "fixture: the three-occurrence series must be persisted before cancelling occurrence 2");
        UUID seriesIdBeforeCancellation = beforeCancellation.get(0).getSeriesId();
        assertNotNull(seriesIdBeforeCancellation, "fixture: the persisted series bookings must carry a seriesId");
        assertTrue(beforeCancellation.stream()
                        .allMatch(row -> seriesIdBeforeCancellation.equals(row.getSeriesId())),
                "fixture: all three persisted occurrences must share the seriesId before cancellation");

        Reservation occurrenceTwo = rowStartingAt(beforeCancellation, occurrenceTwoStart);
        service.cancel(occurrenceTwo.getId(), ownerAuthentication());

        List<Reservation> afterCancellation = rowsFor(room.getId());
        Reservation remainingOne = rowStartingAt(afterCancellation, occurrenceOneStart);
        Reservation remainingThree = rowStartingAt(afterCancellation, occurrenceThreeStart);

        assertEquals(seriesIdBeforeCancellation, remainingOne.getSeriesId(),
                "occurrence 1 must remain after the cancellation and retain its seriesId");
        assertEquals(seriesIdBeforeCancellation, remainingThree.getSeriesId(),
                "occurrence 3 must remain after the cancellation and retain its seriesId");
        assertEquals(1L, rowsStartingAt(room.getId(), occurrenceOneStart),
                "occurrence 1 must remain exactly once after cancellation");
        assertEquals(1L, rowsStartingAt(room.getId(), occurrenceThreeStart),
                "occurrence 3 must remain exactly once after cancellation");
    }

    /**
     * SCN-014#A3 - the cancelled occurrence is no longer an active reservation for the room, while
     * the remaining occurrences stay active. The pre-cancellation read is the control proving the
     * three occurrences were active before the cancellation.
     */
    @Test
    void cancelledOccurrenceIsNoLongerActiveWhileRemainingOccurrencesStayActive() {
        BookableResource room = createRoom("SCN014-A3");
        Instant occurrenceOneStart = FIRST_START.toInstant();
        Instant occurrenceOneEnd = occurrenceOneStart.plus(OCCURRENCE_DURATION);
        Instant occurrenceTwoStart = FIRST_START.plusWeeks(1).toInstant();
        Instant occurrenceTwoEnd = occurrenceTwoStart.plus(OCCURRENCE_DURATION);
        Instant occurrenceThreeStart = FIRST_START.plusWeeks(2).toInstant();
        Instant occurrenceThreeEnd = occurrenceThreeStart.plus(OCCURRENCE_DURATION);

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", 3), "alice");

        List<Reservation> beforeCancellation = rowsFor(room.getId());
        assertEquals(3, beforeCancellation.size(),
                "fixture: the three-occurrence series must be persisted before cancelling occurrence 2");
        assertTrue(beforeCancellation.stream()
                        .allMatch(row -> row.getStatus() != ReservationStatus.CANCELLED),
                "control: all three occurrences must be active reservations before the cancellation");
        assertEquals(1L, activeReservationsCovering(room.getId(), occurrenceTwoStart, occurrenceTwoEnd),
                "control: occurrence 2 must be an active reservation for the room before cancellation");

        Reservation occurrenceTwo = rowStartingAt(beforeCancellation, occurrenceTwoStart);
        service.cancel(occurrenceTwo.getId(), ownerAuthentication());

        assertEquals(0L, activeReservationsCovering(room.getId(), occurrenceTwoStart, occurrenceTwoEnd),
                "the cancelled occurrence must no longer be an active reservation for the room");
        assertEquals(1L, activeReservationsCovering(room.getId(), occurrenceOneStart, occurrenceOneEnd),
                "occurrence 1 must stay an active reservation for the room");
        assertEquals(1L, activeReservationsCovering(room.getId(), occurrenceThreeStart, occurrenceThreeEnd),
                "occurrence 3 must stay an active reservation for the room");

        List<Reservation> afterCancellation = rowsFor(room.getId());
        assertNotEquals(ReservationStatus.CANCELLED,
                rowStartingAt(afterCancellation, occurrenceOneStart).getStatus(),
                "occurrence 1 must stay an active reservation");
        assertNotEquals(ReservationStatus.CANCELLED,
                rowStartingAt(afterCancellation, occurrenceThreeStart).getStatus(),
                "occurrence 3 must stay an active reservation");
    }

    private BookableResource createRoom(String code) {
        return resourceRepository.save(new BookableResource(code, "Room " + code, ResourceType.ROOM,
                "HQ", 1, 8, true, false));
    }

    private ReservationSeriesInput weeklySeries(Long resourceId, String title, int occurrences) {
        ZonedDateTime firstEnd = FIRST_START.plus(OCCURRENCE_DURATION);
        return new ReservationSeriesInput(resourceId, title,
                FIRST_START.toOffsetDateTime(), firstEnd.toOffsetDateTime(),
                BERLIN.getId(), "WEEKLY", occurrences);
    }

    private Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }

    private Reservation rowStartingAt(List<Reservation> rows, Instant start) {
        return rows.stream()
                .filter(row -> row.getStartsAt().equals(start))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected a persisted occurrence starting at " + start + ", found " + rows));
    }

    private long rowsStartingAt(long resourceId, Instant start) {
        return rowsFor(resourceId).stream()
                .filter(row -> row.getStartsAt().equals(start))
                .count();
    }

    private long activeReservationsCovering(long resourceId, Instant start, Instant end) {
        return rowsFor(resourceId).stream()
                .filter(row -> row.getStatus() != ReservationStatus.CANCELLED)
                .filter(row -> row.getStartsAt().isBefore(end) && start.isBefore(row.getEndsAt()))
                .count();
    }

    private List<Reservation> rowsFor(long resourceId) {
        return entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId order by r.startsAt",
                        Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList();
    }
}
