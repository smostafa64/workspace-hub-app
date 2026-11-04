package com.example.workspace.integration;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer obligations for SCN-001, SCN-006, SCN-009 and SCN-010.
 *
 * <p>Scope is frozen by {@code frozen_test_obligations} of job WBATCH-005:
 * SCN-001#A2, SCN-001#A4, SCN-006#A1, SCN-006#A2, SCN-006#A4, SCN-009#A2 and SCN-010#A1.
 * No other assertion of those scenarios is claimed here.</p>
 *
 * <p>This class loads the real Spring Boot application context (no slice, no mocks) and drives the
 * production {@link ReservationService} bean through its Spring proxy, i.e. through the
 * application's own transaction infrastructure. State is then read back from the database in a
 * separate transaction, so committed and rolled-back rows are observed independently of the
 * service's return value. That boundary is what makes these tests complementary to the
 * {@code @DataJpaTest} slice tests of the database batch: here the application's transaction
 * manager and AOP proxy configuration are the ones under test, which is exactly where a missing or
 * self-invoked transaction (REQ-004) would leak partially persisted occurrences.</p>
 *
 * <p>All times are computed from a fixed injected clock and future, deterministic dates
 * (2030). The 2030 Europe/Berlin spring-forward transition is 2030-03-31 02:00 local, and 09:00
 * local exists exactly once on every occurrence date used by SCN-006, so no ambiguous/nonexistent
 * local time policy is exercised.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = Replace.ANY)
class ReservationSeriesIntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final Duration OCCURRENCE_DURATION = Duration.ofMinutes(60);

    /**
     * Replaces the application clock for this test context. The bean deliberately does not reuse
     * the production bean name, so no bean-definition override is required; {@code @Primary}
     * selects it for constructor injection into {@link ReservationService}.
     */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedTestClock() {
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

    /** Rooms created by the current test, registered before any assertion so cleanup always runs. */
    private final List<Long> createdResourceIds = new ArrayList<>();

    @AfterEach
    void removeCreatedFixtures() {
        for (Long resourceId : createdResourceIds) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                List<Reservation> reservations = entityManager.createQuery(
                                "select r from Reservation r where r.resource.id = :resourceId",
                                Reservation.class)
                        .setParameter("resourceId", resourceId)
                        .getResultList();
                for (Reservation reservation : reservations) {
                    entityManager.remove(reservation);
                }
                BookableResource room = entityManager.find(BookableResource.class, resourceId);
                if (room != null) {
                    entityManager.remove(room);
                }
            });
        }
        createdResourceIds.clear();
    }

    /**
     * SCN-001#A2 - the database contains exactly three reservation rows for the submitted
     * resourceId and title after the series is created.
     *
     * <p>The rows are read in a separate transaction, so the observation is the committed state and
     * not the series response. The count is filtered by both the submitted resource and title, and
     * the unfiltered row set of the fresh room is checked as well, so a stray occurrence cannot be
     * hidden behind the title filter.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-001")
    void seriesCreationPersistsExactlyThreeRowsForSubmittedResourceAndTitle() {
        BookableResource room = createRoom("SCN001A2");
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        String title = "Weekly planning";

        service.createSeries(weeklySeries(room.getId(), title, firstStart, 3), "alice");

        assertEquals(3L, rowCount(room.getId(), title),
                "after the series is created the database must contain exactly three rows for the submitted resourceId and title");
        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(3, rows.size(),
                "the submitted room must hold exactly the three submitted occurrence rows, found " + rows.size());
        assertTrue(rows.stream().allMatch(row -> title.equals(row.getTitle())),
                "every persisted row of the submitted room must carry the submitted title, found "
                        + rows.stream().map(Reservation::getTitle).collect(Collectors.toList()));
    }

    /**
     * SCN-001#A4 - all three created bookings carry the same non-null seriesId.
     *
     * <p>Read from the database in a separate transaction: every row must store a non-null
     * seriesId and the set of stored values must contain exactly one identifier.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-001")
    void allPersistedBookingsOfTheSeriesShareOneNonNullSeriesId() {
        BookableResource room = createRoom("SCN001A4");
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", firstStart, 3), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(3, rows.size(), "the fixture requires exactly three persisted bookings, found " + rows.size());
        for (Reservation row : rows) {
            assertNotNull(row.getSeriesId(),
                    "every booking created by the series must persist a non-null seriesId: " + row);
        }
        Set<UUID> distinctSeriesIds = rows.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toSet());
        assertEquals(1, distinctSeriesIds.size(),
                "all three created bookings must carry the same seriesId, found " + distinctSeriesIds);
    }

    /**
     * SCN-006#A1 - across a daylight-saving transition every occurrence still starts at the same
     * local wall-clock time in the supplied zone, so occurrences are spaced by local calendar
     * weeks rather than by a fixed UTC offset.
     *
     * <p>Falsifiable in both directions: an implementation that adds a fixed UTC offset yields
     * 10:00 local after the spring-forward transition, and an implementation that adds exactly
     * 168 hours yields a shifted local time. The observed UTC gaps must therefore contain the
     * 167-hour step caused by the +01:00 to +02:00 offset change.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-006")
    void weeklyExpansionKeepsLocalWallClockTimeAcrossDaylightSavingTransition() {
        BookableResource room = createRoom("SCN006A1");
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 3, 18, 9, 0, 0, 0, BERLIN);
        int occurrences = 4;

        service.createSeries(weeklySeries(room.getId(), "Weekly sync", firstStart, occurrences), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(occurrences, rows.size(),
                "the fixture requires one persisted row per requested occurrence, found " + rows.size());

        for (int index = 0; index < occurrences; index++) {
            ZonedDateTime expectedLocal = firstStart.plusWeeks(index);
            ZonedDateTime storedLocal = rows.get(index).getStartsAt().atZone(BERLIN);
            assertEquals(expectedLocal.toLocalDateTime(), storedLocal.toLocalDateTime(),
                    "occurrence " + (index + 1) + " must start at the same local wall-clock time in Europe/Berlin, stored "
                            + storedLocal);
        }

        assertEquals(ZoneOffset.ofHours(1), rows.get(0).getStartsAt().atZone(BERLIN).getOffset(),
                "the fixture must begin before the spring-forward transition in CET (+01:00)");
        assertEquals(ZoneOffset.ofHours(2), rows.get(3).getStartsAt().atZone(BERLIN).getOffset(),
                "the last occurrence must fall after the spring-forward transition in CEST (+02:00)");

        assertEquals(Duration.ofHours(168), Duration.between(rows.get(0).getStartsAt(), rows.get(1).getStartsAt()),
                "the first local calendar week must span 168 real hours");
        assertEquals(Duration.ofHours(167), Duration.between(rows.get(1).getStartsAt(), rows.get(2).getStartsAt()),
                "the week crossing the spring-forward transition must span 167 real hours, proving local weeks rather than a fixed UTC offset");
        assertEquals(Duration.ofHours(168), Duration.between(rows.get(2).getStartsAt(), rows.get(3).getStartsAt()),
                "the following local calendar week must span 168 real hours again");
    }

    /**
     * SCN-006#A2 - stored occurrence start instants are distinct UTC instants that each map back to
     * the same local wall-clock time in the supplied zone.
     *
     * <p>Observed on the committed rows: four pairwise different {@link Instant} values, each of
     * which renders as 09:00 Europe/Berlin, while the offsets of those instants are not all equal
     * (so the identity of the local time is not produced by a fixed offset).</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-006")
    void storedOccurrenceStartsAreDistinctUtcInstantsAtTheSameLocalWallClockTime() {
        BookableResource room = createRoom("SCN006A2");
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 3, 18, 9, 0, 0, 0, BERLIN);
        int occurrences = 4;

        service.createSeries(weeklySeries(room.getId(), "Weekly sync", firstStart, occurrences), "alice");

        List<Instant> storedStarts = rowsFor(room.getId()).stream()
                .map(Reservation::getStartsAt)
                .collect(Collectors.toList());
        assertEquals(occurrences, storedStarts.size(),
                "the fixture requires one persisted start per requested occurrence, found " + storedStarts.size());
        assertEquals(occurrences, new HashSet<>(storedStarts).size(),
                "every occurrence must be stored as a distinct UTC instant, found " + storedStarts);

        List<LocalTime> localTimes = storedStarts.stream()
                .map(instant -> instant.atZone(BERLIN).toLocalTime())
                .collect(Collectors.toList());
        assertEquals(Collections.nCopies(occurrences, LocalTime.of(9, 0)), localTimes,
                "every stored instant must map back to 09:00 Europe/Berlin, found " + localTimes);

        Set<ZoneOffset> distinctOffsets = storedStarts.stream()
                .map(instant -> instant.atZone(BERLIN).getOffset())
                .collect(Collectors.toCollection(HashSet::new));
        assertEquals(Set.of(ZoneOffset.ofHours(1), ZoneOffset.ofHours(2)), distinctOffsets,
                "the stored instants must span both the CET and CEST offsets, found " + distinctOffsets);
    }

    /**
     * SCN-006#A4 - expanding the series creates one booking for every requested occurrence date, so
     * no occurrence is dropped when the span crosses a daylight-saving transition.
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-006")
    void everyRequestedOccurrenceDateIsPersistedExactlyOnceAcrossDaylightSavingTransition() {
        BookableResource room = createRoom("SCN006A4");
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 3, 18, 9, 0, 0, 0, BERLIN);
        int occurrences = 4;

        service.createSeries(weeklySeries(room.getId(), "Weekly sync", firstStart, occurrences), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(occurrences, rows.size(),
                "no occurrence may be dropped when the series span crosses a daylight-saving transition, found " + rows.size());

        Map<LocalDate, Long> rowsPerLocalDate = rows.stream()
                .collect(Collectors.groupingBy(
                        row -> row.getStartsAt().atZone(BERLIN).toLocalDate(),
                        Collectors.counting()));
        assertEquals(Set.of(LocalDate.of(2030, 3, 18), LocalDate.of(2030, 3, 25),
                        LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 8)),
                rowsPerLocalDate.keySet(),
                "each requested occurrence date must be persisted, found " + rowsPerLocalDate);
        for (Map.Entry<LocalDate, Long> entry : rowsPerLocalDate.entrySet()) {
            assertEquals(1L, entry.getValue().longValue(),
                    "occurrence date " + entry.getKey() + " must be persisted exactly once, found " + rowsPerLocalDate);
        }
    }

    /**
     * SCN-009#A2 - after the 409 response the database contains zero reservation rows from the
     * rejected series, including the non-conflicting occurrences.
     *
     * <p>The identical series request is first applied to a control room that differs only in not
     * holding the conflicting booking, proving the request is otherwise valid. The rejection itself
     * is checked for conflict semantics so that the zero-row observation cannot be produced by an
     * unrelated validation failure; the HTTP status mapping of that conflict is the API layer's
     * obligation (SCN-009#A1) and is not claimed here.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-009")
    @Tag("fault_model:transaction_rollback")
    void rejectedSeriesLeavesZeroRowsIncludingNonConflictingOccurrences() {
        BookableResource contestedRoom = createRoom("SCN009ROOM");
        BookableResource controlRoom = createRoom("SCN009CTRL");
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        String seriesTitle = "Monthly review";
        int occurrences = 3;

        Instant occurrenceOneStart = firstStart.toInstant();
        Instant occurrenceTwoStart = firstStart.plusWeeks(1).toInstant();
        Instant occurrenceThreeStart = firstStart.plusWeeks(2).toInstant();

        Reservation existing = reservationRepository.save(new Reservation(
                contestedRoom, "carol", "Existing booking",
                occurrenceTwoStart.plus(Duration.ofMinutes(10)),
                occurrenceTwoStart.plus(Duration.ofMinutes(40)),
                ReservationStatus.CONFIRMED, FIXED_NOW));

        ReservationSeriesView control = service.createSeries(
                weeklySeries(controlRoom.getId(), seriesTitle, firstStart, occurrences), "alice");
        assertEquals(occurrences, control.reservations().size(),
                "control fixture: without the overlapping booking the identical series must be accepted");
        assertEquals(occurrences, rowsFor(controlRoom.getId()).size(),
                "control fixture: the accepted series must persist one row per occurrence");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.createSeries(
                        weeklySeries(contestedRoom.getId(), seriesTitle, firstStart, occurrences), "alice"),
                "a series whose second occurrence overlaps an existing reservation must be rejected");
        assertConflictFailure(failure);

        List<Reservation> rows = rowsFor(contestedRoom.getId());
        assertEquals(1, rows.size(),
                "after the rejection only the pre-existing conflicting booking may remain, found " + rows.size());
        assertEquals(existing.getId(), rows.get(0).getId(),
                "the surviving row must be the pre-existing booking");
        assertEquals("Existing booking", rows.get(0).getTitle(),
                "the pre-existing booking must not be removed by the rollback");
        assertEquals(0L, rowCount(contestedRoom.getId(), seriesTitle),
                "no reservation row from the rejected series may be persisted");
        assertTrue(rows.stream().noneMatch(row -> row.getStartsAt().equals(occurrenceOneStart)),
                "the non-conflicting occurrence 1 must not be persisted");
        assertTrue(rows.stream().noneMatch(row -> row.getStartsAt().equals(occurrenceThreeStart)),
                "the non-conflicting occurrence 3 must not be persisted");
    }

    /**
     * SCN-010#A1 - a conflict on the last of twelve occurrences still yields HTTP 409 and zero
     * persisted bookings from that series.
     *
     * <p>The service boundary reports the conflict as an {@link ApiException} carrying
     * {@code HttpStatus.CONFLICT} (or, if the application raises its dedicated conflict exception,
     * as a conflict-typed rejection); the controller-to-status mapping is a technical detail of that
     * same rejection. The control room receives the byte-identical twelve-occurrence request and
     * must persist twelve rows, proving that only the occurrence-12 overlap causes the rejection and
     * that the occurrence count itself is valid.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-010")
    @Tag("fault_model:transaction_rollback")
    void conflictOnLastOfTwelveOccurrencesYieldsConflictAndZeroPersistedBookings() {
        BookableResource contestedRoom = createRoom("SCN010ROOM");
        BookableResource controlRoom = createRoom("SCN010CTRL");
        ZonedDateTime firstStart = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
        String seriesTitle = "Weekly planning";
        int occurrences = 12;
        Instant lastOccurrenceStart = firstStart.plusWeeks(occurrences - 1).toInstant();

        Reservation existing = reservationRepository.save(new Reservation(
                contestedRoom, "carol", "Existing late booking",
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
        assertConflictFailure(failure);

        List<Reservation> rows = rowsFor(contestedRoom.getId());
        assertEquals(1, rows.size(),
                "after the conflict on the last occurrence only the pre-existing booking may remain, found " + rows.size());
        assertEquals(existing.getId(), rows.get(0).getId(),
                "the surviving row must be the pre-existing booking");
        assertEquals(0L, rowCount(contestedRoom.getId(), seriesTitle),
                "no reservation row from the rejected series may be persisted");

        Set<Instant> occurrenceStarts = new HashSet<>();
        for (int index = 0; index < occurrences; index++) {
            occurrenceStarts.add(firstStart.plusWeeks(index).toInstant());
        }
        assertTrue(rows.stream().noneMatch(row -> occurrenceStarts.contains(row.getStartsAt())),
                "none of the twelve occurrences, including the eleven non-conflicting ones, may be persisted");
    }

    /**
     * The rejection must be a conflict, not an unrelated validation or infrastructure failure;
     * otherwise a zero-row observation could pass for the wrong reason.
     */
    private void assertConflictFailure(RuntimeException failure) {
        if (failure instanceof ApiException apiException) {
            assertEquals(HttpStatus.CONFLICT, apiException.status(),
                    "the series rejection must carry conflict semantics, was " + apiException.status());
            return;
        }
        String simpleName = failure.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        assertTrue(simpleName.contains("conflict"),
                "the series rejection must be a conflict rejection, was "
                        + failure.getClass().getName() + ": " + failure.getMessage());
    }

    private BookableResource createRoom(String codePrefix) {
        BookableResource room = resourceRepository.save(new BookableResource(
                codePrefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6),
                "Room " + codePrefix, ResourceType.ROOM, "HQ", 1, 8, true, false));
        createdResourceIds.add(room.getId());
        return room;
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

    private long rowCount(long resourceId, String title) {
        return entityManager.createQuery(
                        "select count(r) from Reservation r where r.resource.id = :resourceId and r.title = :title",
                        Long.class)
                .setParameter("resourceId", resourceId)
                .setParameter("title", title)
                .getSingleResult();
    }
}
