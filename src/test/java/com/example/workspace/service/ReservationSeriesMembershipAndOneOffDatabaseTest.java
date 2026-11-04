package com.example.workspace.service;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Database-layer obligations for SCN-018#A3 and SCN-019#A3.
 *
 * <p>Each test drives the production {@link ReservationService} in its own committed transaction
 * (the test method itself is not transactional) and then reads the durable rows through JPQL or a
 * separate read transaction with a fresh persistence context, so committed state is observed from
 * the database rather than from the service's return value. The service return value is used only
 * as the reference set that the persisted rows must correspond to; no display/HTTP expectation is
 * asserted here because those belong to the e2e/api layers of the same scenarios.</p>
 *
 * <p>SCN-018#A3: the bookings reported for one submitted weekly series correspond one-to-one to
 * persisted rows that all share a single seriesId. SCN-019#A3: a one-off booking persists with a
 * null seriesId and remains retrievable from the database by its identifier.</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@Import({ReservationService.class, ReservationSeriesMembershipAndOneOffDatabaseTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesMembershipAndOneOffDatabaseTest {

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
     * SCN-018#A3 - the bookings reported for the submitted weekly series correspond to persisted
     * rows that all share exactly one non-null seriesId.
     *
     * <p>Both halves of the frozen assertion are observed at the database boundary: the persisted
     * row identifiers must be exactly the reported booking identifiers (correspondence), and the
     * stored seriesId must be one non-null value shared by every row of the series. The room is
     * fresh, so every row found for it belongs to this one submission.</p>
     */
    @Test
    @Tag("observation_boundary:database")
    @Tag("scenario:SCN-018")
    void persistedSeriesBookingsCorrespondToReportedBookingsAndShareOneSeriesId() {
        BookableResource room = createRoom("SCN018-A3", true, false);

        ReservationSeriesView created = service.createSeries(
                weeklySeries(room.getId(), "Weekly planning", FIRST_START, 3), "alice");

        List<ReservationView> reported = created.reservations();
        assertEquals(3, reported.size(),
                "the submitted series must report the three requested occurrences");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(3, rows.size(),
                "the submitted series must persist exactly one row per reported booking, found " + rows.size());

        Set<Long> reportedIds = reported.stream()
                .map(ReservationView::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        assertEquals(3, reportedIds.size(),
                "every reported booking must carry a persisted identifier, found " + reportedIds);
        Set<Long> persistedIds = rows.stream()
                .map(Reservation::getId)
                .collect(Collectors.toSet());
        assertEquals(reportedIds, persistedIds,
                "the bookings reported as one series must correspond to the persisted rows, reported="
                        + reportedIds + " persisted=" + persistedIds);

        for (Reservation row : rows) {
            assertNotNull(row.getSeriesId(),
                    "every persisted booking of the series must store a non-null seriesId: " + row);
        }
        Set<UUID> storedSeriesIds = rows.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toSet());
        assertEquals(1, storedSeriesIds.size(),
                "all persisted rows of the series must share exactly one seriesId, found " + storedSeriesIds);
    }

    /**
     * SCN-019#A3 - a one-off booking persists with a null seriesId and remains retrievable.
     *
     * <p>The row is re-read from the database in a separate transaction and a fresh persistence
     * context, so the observation is the durable row rather than the entity instance used for the
     * insert, and it is additionally counted through a JPQL query by identifier.</p>
     */
    @Test
    @Tag("observation_boundary:database")
    @Tag("scenario:SCN-019")
    void oneOffBookingPersistsNullSeriesIdAndRemainsRetrievable() {
        BookableResource room = createRoom("SCN019-A3", true, false);
        Instant start = ZonedDateTime.of(2030, 5, 7, 9, 0, 0, 0, BERLIN).toInstant();
        Instant end = start.plus(OCCURRENCE_DURATION);

        ReservationView created = service.create(new BookingInput(room.getId(), "One-off sync", start, end), "alice");
        assertNotNull(created.id(), "the created one-off booking must carry the identifier of its persisted row");

        Reservation persisted = readPersistedRow(created.id());

        assertNotNull(persisted, "the one-off booking must remain retrievable by its identifier after commit");
        assertNull(persisted.getSeriesId(), "a one-off booking must persist a null seriesId");
        assertEquals(room.getId(), persisted.getResource().getId(),
                "the retrieved row must belong to the room the one-off booking was created for");
        assertEquals("One-off sync", persisted.getTitle(),
                "the retrieved row must keep the submitted title");
        assertEquals(start, persisted.getStartsAt(),
                "the retrieved row must keep the submitted start instant");
        assertEquals(end, persisted.getEndsAt(),
                "the retrieved row must keep the submitted end instant");
        assertEquals("alice", persisted.getBookedBy(),
                "the retrieved row must keep the submitting employee");

        assertEquals(1L, countRowsWithId(created.id()),
                "the one-off booking must be retrievable as exactly one durable row by its identifier");
        assertEquals(1, rowsFor(room.getId()).size(),
                "the one-off booking must not leave additional rows behind for the room");
        assertEquals(1L, rowsFor(room.getId()).stream().filter(row -> row.getId().equals(created.id())).count(),
                "the durable row for the room must be the reported one-off booking");
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

    private List<Reservation> rowsFor(long resourceId) {
        return entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId order by r.startsAt",
                        Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList();
    }

    /**
     * Re-reads one reservation in a new transaction, so the returned instance comes from a fresh
     * persistence context rather than the one that inserted it.
     */
    private Reservation readPersistedRow(long reservationId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery(
                                "select r from Reservation r where r.id = :id",
                                Reservation.class)
                        .setParameter("id", reservationId)
                        .getResultList()
                        .stream()
                        .findFirst()
                        .orElse(null));
    }

    private long countRowsWithId(long reservationId) {
        return entityManager.createQuery(
                        "select count(r) from Reservation r where r.id = :id",
                        Long.class)
                .setParameter("id", reservationId)
                .getSingleResult();
    }
}
