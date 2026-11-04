package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer obligation for SCN-017: resubmitting the byte-identical series request must be
 * rejected as an overlap conflict and must add no duplicate bookings.
 *
 * <p>The production {@link ReservationService} is driven in its own committed transaction and the
 * durable rows are read back through JPQL. The control room receives the same series values but is
 * unbooked, which proves the request itself is valid and that the rejection is caused by the
 * already-booked room rather than by a malformed request.</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Import({ReservationService.class, ReservationSeriesResubmissionDatabaseTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesResubmissionDatabaseTest {

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
     * SCN-017#A1 - resubmitting the identical series request is rejected as an overlap conflict and
     * adds no additional bookings.
     */
    @Test
    void identicalSeriesResubmissionIsRejectedAsConflictAndAddsNoBookings() {
        BookableResource room = createRoom("SCN017-A1");
        BookableResource controlRoom = createRoom("SCN017-A1-CONTROL");

        ReservationSeriesInput request = weeklySeries(room.getId(), "Weekly planning", 3);
        ReservationSeriesView firstCreation = service.createSeries(request, "alice");
        assertNotNull(firstCreation.seriesId(), "fixture: the first submission must create a series");

        List<Reservation> afterFirstSubmission = rowsFor(room.getId());
        assertEquals(3, afterFirstSubmission.size(),
                "fixture: the first submission must persist exactly three bookings");
        Set<Long> rowIdsBeforeResubmission = afterFirstSubmission.stream()
                .map(Reservation::getId)
                .collect(Collectors.toSet());
        Set<UUID> seriesIdsBeforeResubmission = afterFirstSubmission.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toSet());
        Set<Instant> occurrenceStartsBeforeResubmission = afterFirstSubmission.stream()
                .map(Reservation::getStartsAt)
                .collect(Collectors.toSet());

        ReservationSeriesView control = service.createSeries(
                weeklySeries(controlRoom.getId(), "Weekly planning", 3), "alice");
        assertEquals(3, control.reservations().size(),
                "control: the identical series values against an unbooked room must be accepted");
        assertEquals(3, rowsFor(controlRoom.getId()).size(),
                "control: the accepted series must persist three bookings");

        RuntimeException rejection = assertThrows(RuntimeException.class,
                () -> service.createSeries(request, "alice"),
                "resubmitting the byte-identical series request must be rejected");
        assertConflictRejection(rejection);

        List<Reservation> afterResubmission = rowsFor(room.getId());
        assertEquals(3, afterResubmission.size(),
                "the rejected resubmission must not add any booking, found " + afterResubmission.size());
        assertEquals(rowIdsBeforeResubmission,
                afterResubmission.stream().map(Reservation::getId).collect(Collectors.toSet()),
                "the rejected resubmission must not replace or duplicate the existing booking rows");
        assertEquals(seriesIdsBeforeResubmission,
                afterResubmission.stream().map(Reservation::getSeriesId).collect(Collectors.toSet()),
                "the existing bookings must retain their original series");
        assertEquals(occurrenceStartsBeforeResubmission,
                afterResubmission.stream().map(Reservation::getStartsAt).collect(Collectors.toSet()),
                "no duplicate occurrence interval may be added by the rejected resubmission");
        assertEquals(3L, rowCount(room.getId(), "Weekly planning"),
                "the room must hold exactly three bookings under the series title after the resubmission");
    }

    private void assertConflictRejection(RuntimeException failure) {
        boolean apiConflict =
                failure instanceof ApiException && ((ApiException) failure).status() == HttpStatus.CONFLICT;
        boolean conflictTyped =
                failure.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("conflict");
        assertTrue(apiConflict || conflictTyped,
                "the resubmission must be rejected as an overlap conflict, not an unrelated failure; was "
                        + failure.getClass().getName() + ": " + failure.getMessage());
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
