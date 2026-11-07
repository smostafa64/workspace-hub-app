package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationConflictException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-layer verification for the reservation-series feature.
 *
 * <p>Every assertion in this class observes the relational store through a fresh JPA
 * {@link EntityManager} created after the service transaction has completed. The embedded H2
 * database name is generated at class load time and the schema is derived from the application
 * entity mappings, per the approved {@code embedded-h2-unique-db} fixture contract. Each test
 * method owns a UUID-coded bookable resource so no method can observe another method's rows.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class, ReservationSeriesPersistenceTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesPersistenceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wbseries_" + UUID.randomUUID().toString().replace("-", "");

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
    // ---------------------------------------------------------------------
    @Test
    void scn001DatabaseHoldsThreeRowsSharingOneNonNullSeriesId() {
        BookableResource resource = newResource();
        String title = "series-" + UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.of(2030, 1, 8, 9, 0, 0, 0, ZoneOffset.ofHours(1));
        OffsetDateTime endsAt = startsAt.plusHours(1);

        ReservationSeriesView view = createSeries(resource.getId(), title, startsAt, endsAt, 3);

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(3, rows.size(),
                "the database must hold exactly the three reservation rows created for the request");
        assertNotNull(view, "the conflict-free series request must complete");

        UUID seriesId = rows.get(0).seriesId();
        assertNotNull(seriesId, "every persisted occurrence must carry a non-null seriesId");
        assertTrue(rows.stream().allMatch(row -> seriesId.equals(row.seriesId())),
                "all three persisted occurrences must share the identical seriesId");
        assertTrue(rows.stream().allMatch(row -> title.equals(row.title())),
                "the three persisted rows must belong to the requested series");
    }

    // ---------------------------------------------------------------------
    // SCN-001#A3
    // ---------------------------------------------------------------------
    @Test
    void scn001ThreeNewRowsAt0900BerlinOnConsecutiveWeeks() {
        BookableResource resource = newResource();
        String title = "series-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        assertEquals(0, persistedReservations(resource.getId()).size(),
                "precondition: the owned resource must have no reservations before the call");

        createSeries(resource.getId(), title, startsAt, endsAt, 3);

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(3, rows.size(), "exactly three new reservation rows must exist after the call");

        List<ZonedDateTime> localStarts = rows.stream()
                .map(row -> row.startsAt().atZone(BERLIN))
                .toList();
        for (ZonedDateTime local : localStarts) {
            assertEquals(LocalTime.of(9, 0), local.toLocalTime(),
                    "every occurrence must start at 09:00 local Europe/Berlin");
        }
        assertEquals(7, ChronoUnit.DAYS.between(
                        localStarts.get(0).toLocalDate(), localStarts.get(1).toLocalDate()),
                "the first and second occurrences must be one local week apart");
        assertEquals(7, ChronoUnit.DAYS.between(
                        localStarts.get(1).toLocalDate(), localStarts.get(2).toLocalDate()),
                "the second and third occurrences must be one local week apart");
    }

    // ---------------------------------------------------------------------
    // SCN-001#A4
    // ---------------------------------------------------------------------
    @Test
    void scn001ConflictFreeSeriesCreatesAllThreeRequestedOccurrences() {
        BookableResource resource = newResource();
        String title = "series-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        ReservationSeriesView view = createSeries(resource.getId(), title, startsAt, endsAt, 3);

        assertNotNull(view, "the conflict-free series must complete successfully");
        assertEquals(3, view.occurrences(), "the completed series must report all three occurrences");
        assertNotNull(view.seriesId(), "the completed series must carry a series identifier");
        assertEquals(3, view.reservations().size(),
                "the completed series must return all three requested occurrences");
        assertEquals(title, view.title());

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(3, rows.size(), "all three requested occurrences must be persisted");

        Set<Instant> expectedStarts = new LinkedHashSet<>();
        for (int i = 0; i < 3; i++) {
            expectedStarts.add(firstLocal.plusWeeks(i).toInstant());
        }
        Set<Instant> persistedStarts = rows.stream()
                .map(PersistedReservation::startsAt)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(expectedStarts, persistedStarts,
                "none of the three requested occurrences may be omitted from the store");
    }

    // ---------------------------------------------------------------------
    // SCN-002#A2
    // ---------------------------------------------------------------------
    @Test
    void scn002PersistedInstantsAreOneLocalWeekApartAndElapsedHoursDifferAcrossTransition() {
        ZoneRules rules = BERLIN.getRules();
        ZoneOffsetTransition transition = rules.nextTransition(FIXED_INSTANT);
        assertNotNull(transition,
                "a future Europe/Berlin DST transition must exist after the injected clock");

        LocalDate transitionDate = transition.getDateTimeBefore().toLocalDate();
        ZonedDateTime firstLocal = transitionDate.minusWeeks(1).atTime(9, 0).atZone(BERLIN);
        ZonedDateTime thirdLocal = firstLocal.plusWeeks(2);
        assertTrue(firstLocal.toLocalDate().isBefore(transitionDate)
                        && thirdLocal.toLocalDate().isAfter(transitionDate),
                "the three-occurrence weekly series must span the selected DST transition");

        BookableResource resource = newResource();
        String title = "series-" + UUID.randomUUID();
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        assertEquals(0, persistedReservations(resource.getId()).size(),
                "precondition: the owned resource must have no reservations before the call");

        createSeries(resource.getId(), title, startsAt, endsAt, 3);

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(3, rows.size(), "all three occurrences of the DST-spanning series must be stored");

        // Across a spring-forward transition the UTC instants are not monotonic with the local
        // occurrence order, so order by the Europe/Berlin wall clock before measuring gaps.
        List<Instant> instants = rows.stream()
                .sorted(Comparator.comparing(
                        (PersistedReservation row) -> LocalDateTime.ofInstant(row.startsAt(), BERLIN)))
                .map(PersistedReservation::startsAt)
                .toList();
        List<LocalDateTime> localStarts = instants.stream()
                .map(instant -> LocalDateTime.ofInstant(instant, BERLIN))
                .toList();
        for (int i = 0; i < 2; i++) {
            assertEquals(localStarts.get(i).plusWeeks(1), localStarts.get(i + 1),
                    "consecutive persisted occurrences must be exactly one local week apart");
        }

        long firstGapHours = Duration.between(instants.get(0), instants.get(1)).toHours();
        long secondGapHours = Duration.between(instants.get(1), instants.get(2)).toHours();
        assertTrue(firstGapHours != secondGapHours,
                "elapsed UTC hours must differ across the DST transition, but both gaps were "
                        + firstGapHours + "h");
        long expectedTotalHours =
                Duration.between(firstLocal.toInstant(), thirdLocal.toInstant()).toHours();
        assertEquals(expectedTotalHours, firstGapHours + secondGapHours,
                "the persisted UTC instants must span exactly two local weeks across the transition");
    }

    // ---------------------------------------------------------------------
    // SCN-011#A2
    // ---------------------------------------------------------------------
    @Test
    void scn011ConflictOnSecondOccurrenceLeavesNoNewRowsAndNoSeriesRecord() {
        BookableResource resource = newResource();
        String title = "series-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        ZonedDateTime secondLocal = firstLocal.plusWeeks(1);
        String blockerTitle = "blocker-" + UUID.randomUUID();
        reservations.save(new Reservation(resource, "fixture", blockerTitle,
                secondLocal.toInstant(), secondLocal.plusHours(1).toInstant(),
                ReservationStatus.CONFIRMED, FIXED_INSTANT));

        assertEquals(1, persistedReservations(resource.getId()).size(),
                "precondition: only the second weekly slot is occupied");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> createSeries(resource.getId(), title, startsAt, endsAt, 3));
        assertTrue(isConflict(failure),
                "the occupied second occurrence must surface as a reservation conflict, got: " + failure);

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(1, rows.size(),
                "after the conflict the store must contain zero new reservation rows");
        assertEquals(blockerTitle, rows.get(0).title(),
                "the only remaining row must be the pre-existing blocker");
        assertTrue(rows.stream().noneMatch(row -> title.equals(row.title())),
                "no row from the rejected series may remain in the store");
        assertTrue(rows.stream().allMatch(row -> row.seriesId() == null),
                "no series record may be stored for the rejected request");
    }

    // ---------------------------------------------------------------------
    // SCN-012#A1
    // ---------------------------------------------------------------------
    @Test
    void scn012ConflictOnLastOfTwelveOccurrencesCreatesZeroRowsIncludingElevenFreeWeeks() {
        BookableResource resource = newResource();
        String title = "series-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        ZonedDateTime lastLocal = firstLocal.plusWeeks(11);
        String blockerTitle = "blocker-" + UUID.randomUUID();
        reservations.save(new Reservation(resource, "fixture", blockerTitle,
                lastLocal.toInstant(), lastLocal.plusHours(1).toInstant(),
                ReservationStatus.CONFIRMED, FIXED_INSTANT));

        assertEquals(1, persistedReservations(resource.getId()).size(),
                "precondition: only the final weekly slot is occupied");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> createSeries(resource.getId(), title, startsAt, endsAt, 12));
        assertTrue(isConflict(failure),
                "the occupied final occurrence must surface as a reservation conflict, got: " + failure);

        List<PersistedReservation> rows = persistedReservations(resource.getId());
        assertEquals(1, rows.size(),
                "a conflict on the last occurrence must leave zero created rows, "
                        + "including the eleven free earlier weeks");
        assertEquals(blockerTitle, rows.get(0).title(),
                "the only remaining row must be the pre-existing blocker");
        assertTrue(rows.stream().noneMatch(row -> title.equals(row.title())),
                "no row from the rejected twelve-occurrence series may remain in the store");
        assertTrue(rows.stream().allMatch(row -> row.seriesId() == null),
                "no series record may be stored for the rejected request");
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "Series QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true, false);
        return resources.save(resource);
    }

    private ReservationSeriesView createSeries(long resourceId, String title, OffsetDateTime startsAt,
                                              OffsetDateTime endsAt, int occurrences) {
        ReservationSeriesInput input = seriesInput(resourceId, title, startsAt, endsAt, occurrences);
        return service.createSeries(input, "qa-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Constructs the request from the documented input contract. The control assertions prove the
     * supplied offsets and zone are retained, so a later type/format change cannot silently
     * normalize the DST-relevant input before the business call.
     */
    private static ReservationSeriesInput seriesInput(long resourceId, String title,
                                                      OffsetDateTime startsAt, OffsetDateTime endsAt,
                                                      int occurrences) {
        ReservationSeriesInput input = new ReservationSeriesInput(resourceId, title, startsAt, endsAt,
                SERIES_TIMEZONE, SERIES_FREQUENCY, occurrences);
        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain its supplied offset");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain its supplied offset");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: timezone must be retained");
        return input;
    }

    /** Reads the persisted rows for one owned resource through a brand-new EntityManager. */
    private List<PersistedReservation> persistedReservations(long resourceId) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<Object[]> rows = entityManager.createQuery(
                            "select r.id, r.startsAt, r.endsAt, r.seriesId, r.title, r.status "
                                    + "from Reservation r where r.resource.id = :resourceId "
                                    + "order by r.id", Object[].class)
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
                        (ReservationStatus) row[5]));
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
                                        String title, ReservationStatus status) {
    }
}
