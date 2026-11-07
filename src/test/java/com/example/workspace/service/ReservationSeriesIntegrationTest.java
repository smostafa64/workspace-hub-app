package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationConflictException;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration-layer verification for the reservation-series creation obligations.
 *
 * <p>Each test invokes the real {@link ReservationService} bean in-process against the embedded H2
 * database owned by this test class (approved {@code embedded-h2-unique-db} fixture). The service
 * transaction is allowed to commit because the test transaction propagation is
 * {@link Propagation#NOT_SUPPORTED}; the resulting committed state is then observed through a
 * brand-new JPA {@link EntityManager} created after the service call. No mocks are used and no
 * business rule is reimplemented in the test.</p>
 *
 * <p>The database name is generated once per class and every method owns UUID-coded bookable
 * resources, so no method can observe another method's rows and no fixed identifier or calendar
 * slot is reused across runs. The injected clock is fixed at 2030-01-01T00:00:00Z and every test
 * timestamp is genuinely in the future relative to it.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class, ReservationSeriesIntegrationTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesIntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");
    private static final int OCCURRENCES = 3;

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb004_" + UUID.randomUUID().toString().replace("-", "");

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
    // SCN-002#A2
    // ---------------------------------------------------------------------
    @Test
    void scn002PersistedUtcInstantsAreOneLocalWeekApartAndDifferAcrossTheTransition() {
        DstWindow window = dstWindow();
        BookableResource resource = newResource();
        String title = "scn002-instants-" + UUID.randomUUID();

        createSeries(resource.getId(), title,
                window.firstLocal().toOffsetDateTime(),
                window.firstLocal().toOffsetDateTime().plusHours(1));

        List<PersistedRow> rows = readRows(resource.getId());
        assertEquals(OCCURRENCES, rows.size(),
                "all occurrences of the DST-spanning series must be stored");
        assertTrue(rows.stream().allMatch(row -> title.equals(row.title())),
                "every observed row must belong to the requested series, but saw "
                        + rows.stream().map(PersistedRow::title).toList());

        List<ZonedDateTime> localStarts = sortedLocalStarts(rows);
        for (int i = 0; i < localStarts.size() - 1; i++) {
            assertEquals(localStarts.get(i).toLocalDateTime().plusWeeks(1),
                    localStarts.get(i + 1).toLocalDateTime(),
                    "consecutive persisted occurrences must be exactly one local week apart");
        }

        long firstGapHours = Duration.between(
                localStarts.get(0).toInstant(), localStarts.get(1).toInstant()).toHours();
        long secondGapHours = Duration.between(
                localStarts.get(1).toInstant(), localStarts.get(2).toInstant()).toHours();
        assertTrue(firstGapHours != secondGapHours,
                "elapsed UTC hours must differ across the DST transition, but both gaps were "
                        + firstGapHours + "h");
        long expectedTotalHours = Duration.between(
                window.firstLocal().toInstant(), window.thirdLocal().toInstant()).toHours();
        assertEquals(expectedTotalHours, firstGapHours + secondGapHours,
                "the persisted UTC instants must span exactly two local weeks across the transition");
        assertTrue(firstGapHours > 0 && secondGapHours > 0,
                "the persisted occurrences must advance forward in time");
    }

    // ---------------------------------------------------------------------
    // SCN-001#A2
    // ---------------------------------------------------------------------
    @Test
    void scn001DatabaseHoldsExactlyThreeRowsSharingOneNonNullSeriesId() {
        BookableResource resource = newResource();
        String title = "scn001-series-" + UUID.randomUUID();
        OffsetDateTime startsAt = ZonedDateTime
                .of(2030, 1, 8, 9, 0, 0, 0, BERLIN).toOffsetDateTime();

        assertEquals(0, readRows(resource.getId()).size(),
                "precondition: the owned resource must have no reservations before the call");

        createSeries(resource.getId(), title, startsAt, startsAt.plusHours(1));

        List<PersistedRow> rows = readRows(resource.getId());
        assertEquals(OCCURRENCES, rows.size(),
                "the database must hold exactly three reservation rows for the request, but saw "
                        + rows.size());
        assertTrue(rows.stream().allMatch(row -> title.equals(row.title())),
                "the three persisted rows must belong to the requested series, but saw "
                        + rows.stream().map(PersistedRow::title).toList());

        UUID seriesId = rows.get(0).seriesId();
        assertNotNull(seriesId, "every persisted occurrence must carry a non-null seriesId");
        for (PersistedRow row : rows) {
            assertNotNull(row.seriesId(),
                    "reservation row " + row.id() + " must persist a non-null seriesId");
            assertEquals(seriesId, row.seriesId(),
                    "reservation row " + row.id() + " must share the identical seriesId");
        }
    }

    // ---------------------------------------------------------------------
    // SCN-001#A3
    // ---------------------------------------------------------------------
    @Test
    void scn001CreatesThreeNewRowsAt0900BerlinOnConsecutiveWeeks() {
        BookableResource resource = newResource();
        String title = "scn001-weeks-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();

        assertEquals(0, readRows(resource.getId()).size(),
                "precondition: the owned resource must have no reservations before the call");

        createSeries(resource.getId(), title, startsAt, startsAt.plusHours(1));

        List<PersistedRow> rows = readRows(resource.getId());
        assertEquals(OCCURRENCES, rows.size(),
                "exactly three new reservation rows must exist after the call, but saw "
                        + rows.size());

        List<ZonedDateTime> localStarts = sortedLocalStarts(rows);
        assertEquals(firstLocal.toLocalDate(), localStarts.get(0).toLocalDate(),
                "the first new row must start on the requested first week");
        for (ZonedDateTime localStart : localStarts) {
            assertEquals(LocalTime.of(9, 0), localStart.toLocalTime(),
                    "every occurrence must start at 09:00 local Europe/Berlin, but saw "
                            + localStart.toLocalTime());
        }
        for (int i = 0; i < localStarts.size() - 1; i++) {
            assertEquals(7, ChronoUnit.DAYS.between(
                            localStarts.get(i).toLocalDate(), localStarts.get(i + 1).toLocalDate()),
                    "the occurrences must fall on consecutive weeks");
        }
    }

    // ---------------------------------------------------------------------
    // SCN-002#A1
    // ---------------------------------------------------------------------
    @Test
    void scn002EveryOccurrenceRendersTheSameBerlinWallClockAsTheFirst() {
        DstWindow window = dstWindow();
        BookableResource resource = newResource();
        String title = "scn002-clock-" + UUID.randomUUID();

        createSeries(resource.getId(), title,
                window.firstLocal().toOffsetDateTime(),
                window.firstLocal().toOffsetDateTime().plusHours(1));

        List<PersistedRow> rows = readRows(resource.getId());
        assertEquals(OCCURRENCES, rows.size(),
                "all occurrences of the DST-spanning series must be stored");
        assertTrue(rows.stream().allMatch(row -> title.equals(row.title())),
                "every observed row must belong to the requested series, but saw "
                        + rows.stream().map(PersistedRow::title).toList());

        List<ZonedDateTime> localStarts = sortedLocalStarts(rows);
        LocalTime firstClock = localStarts.get(0).toLocalTime();
        assertEquals(LocalTime.of(9, 0), firstClock,
                "control: the first persisted occurrence must retain the requested 09:00 Europe/Berlin wall clock");
        for (int i = 1; i < localStarts.size(); i++) {
            assertEquals(firstClock, localStarts.get(i).toLocalTime(),
                    "occurrence " + i + " must render the same Europe/Berlin wall-clock time as the "
                            + "first occurrence, but saw " + localStarts.get(i).toLocalTime());
        }
    }

    // ---------------------------------------------------------------------
    // SCN-010#A1
    // ---------------------------------------------------------------------
    @Test
    void scn010InactiveResourceIsRejectedAndCreatesNoReservationRows() {
        String base = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        BookableResource activeTwin = saveResource(base + "a", true);
        BookableResource inactive = saveResource(base + "b", false);
        assertTrue(activeTwin.isActive(), "control: the valid twin resource must be active");
        assertFalse(inactive.isActive(), "control: the rejected resource must really be inactive");

        OffsetDateTime startsAt = ZonedDateTime
                .of(2030, 6, 4, 9, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        assertEquals(0, readRows(activeTwin.getId()).size(),
                "precondition: the active twin must have no reservations");
        assertEquals(0, readRows(inactive.getId()).size(),
                "precondition: the inactive resource must have no reservations");

        // Valid control: the identical request differs only in the resource active flag and must
        // persist all three occurrences for the active twin.
        String activeTitle = "scn010-active-" + UUID.randomUUID();
        ReservationSeriesView activeSeries = createSeries(
                activeTwin.getId(), activeTitle, startsAt, endsAt);
        assertNotNull(activeSeries,
                "valid control: the same request must succeed for an active resource");
        assertEquals(OCCURRENCES, readRows(activeTwin.getId()).size(),
                "valid control: the active resource must receive all three occurrences");

        // Invalid partition: the same request for the inactive resource must be rejected.
        String inactiveTitle = "scn010-inactive-" + UUID.randomUUID();
        RuntimeException rejection = assertThrows(RuntimeException.class,
                () -> createSeries(inactive.getId(), inactiveTitle, startsAt, endsAt));
        assertTrue(rejection instanceof ApiException
                        || rejection instanceof ReservationConflictException,
                "the inactive-resource partition must be rejected as a business error, but got: "
                        + rejection);

        List<PersistedRow> rows = readRows(inactive.getId());
        assertTrue(rows.isEmpty(),
                "no reservation rows may be created for an inactive resource, but saw "
                        + rows.size() + " rows");
    }

    // ---------------------------------------------------------------------
    // SCN-011#A2
    // ---------------------------------------------------------------------
    @Test
    void scn011ConflictOnSecondOccurrenceLeavesZeroNewRowsAndNoSeriesRecord() {
        BookableResource resource = newResource();
        String blockerTitle = "scn011-blocker-" + UUID.randomUUID();
        String seriesTitle = "scn011-series-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN);
        ZonedDateTime secondLocal = firstLocal.plusWeeks(1);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();

        // Precondition: a genuine one-off reservation occupies only the second weekly slot.
        service.create(new BookingInput(resource.getId(), blockerTitle,
                        secondLocal.toInstant(), secondLocal.plusHours(1).toInstant()),
                "qa-blocker-" + UUID.randomUUID());

        List<PersistedRow> before = readRows(resource.getId());
        assertEquals(1, before.size(), "precondition: only the second weekly slot is occupied");
        assertEquals(blockerTitle, before.get(0).title(),
                "precondition: the occupied slot must be the blocker reservation");
        assertNull(before.get(0).seriesId(),
                "precondition: the blocker is a one-off reservation without a series identifier");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> createSeries(resource.getId(), seriesTitle, startsAt, startsAt.plusHours(1)));
        assertTrue(isConflict(failure),
                "the occupied second occurrence must surface as a reservation conflict, but got: "
                        + failure);

        List<PersistedRow> after = readRows(resource.getId());
        assertEquals(1, after.size(),
                "after the conflict the store must contain zero new reservation rows, but saw "
                        + after.size());
        assertEquals(blockerTitle, after.get(0).title(),
                "the only remaining row must be the pre-existing blocker");
        assertTrue(after.stream().noneMatch(row -> seriesTitle.equals(row.title())),
                "no row from the rejected series may remain in the store");
        assertTrue(after.stream().allMatch(row -> row.seriesId() == null),
                "no series record may be stored for the rejected request, but saw series ids "
                        + after.stream().map(PersistedRow::seriesId).toList());
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource() {
        return saveResource(
                UUID.randomUUID().toString().replace("-", "").substring(0, 12), true);
    }

    private BookableResource saveResource(String codeSuffix, boolean active) {
        BookableResource resource = new BookableResource(
                "W" + codeSuffix, "WB004 QA " + codeSuffix, ResourceType.ROOM, "HQ", 2, 10,
                active, false);
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
        assertEquals(OCCURRENCES, input.occurrences(),
                "input control: the requested occurrence count must be retained");
        return service.createSeries(input, "qa-" + UUID.randomUUID());
    }

    /**
     * Derives the next Europe/Berlin DST transition after the injected clock and returns a
     * three-occurrence weekly window that really spans it. Both the transition and the window are
     * computed dynamically, so the test never depends on a fixed year or scenario number.
     */
    private static DstWindow dstWindow() {
        ZoneRules rules = BERLIN.getRules();
        ZoneOffsetTransition transition = rules.nextTransition(FIXED_INSTANT);
        assertNotNull(transition,
                "a future Europe/Berlin DST transition must exist after the injected clock");

        LocalDate transitionDate = transition.getDateTimeBefore().toLocalDate();
        ZonedDateTime firstLocal = transitionDate.minusWeeks(1).atTime(9, 0).atZone(BERLIN);
        ZonedDateTime thirdLocal = firstLocal.plusWeeks(2);
        assertTrue(firstLocal.isAfter(FIXED_INSTANT.atZone(BERLIN)),
                "the selected weekly window must be in the future relative to the injected clock");
        assertTrue(firstLocal.toLocalDate().isBefore(transitionDate)
                        && thirdLocal.toLocalDate().isAfter(transitionDate),
                "the three-occurrence weekly series must span the selected DST transition");
        return new DstWindow(firstLocal, thirdLocal);
    }

    /** Reads the committed rows for one owned resource through a brand-new EntityManager. */
    private List<PersistedRow> readRows(long resourceId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.id, r.startsAt, r.seriesId, r.title from Reservation r "
                                    + "where r.resource.id = :resourceId order by r.id",
                            Object[].class)
                    .setParameter("resourceId", resourceId)
                    .getResultList();
            List<PersistedRow> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                result.add(new PersistedRow(
                        ((Number) row[0]).longValue(),
                        toInstant(row[1]),
                        (UUID) row[2],
                        (String) row[3]));
            }
            return result;
        } finally {
            entityManager.close();
        }
    }

    /** Renders the persisted instants in Europe/Berlin and orders them by local wall clock. */
    private static List<ZonedDateTime> sortedLocalStarts(List<PersistedRow> rows) {
        return rows.stream()
                .map(row -> row.startsAt().atZone(BERLIN))
                .sorted(Comparator.comparing(ZonedDateTime::toLocalDateTime))
                .toList();
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

    private record PersistedRow(Long id, Instant startsAt, UUID seriesId, String title) {
    }

    private record DstWindow(ZonedDateTime firstLocal, ZonedDateTime thirdLocal) {
    }
}
