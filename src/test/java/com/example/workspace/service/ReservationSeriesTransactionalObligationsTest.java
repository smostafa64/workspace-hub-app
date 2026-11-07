package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationConflictException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration-layer verification for SCN-002#A2, SCN-004#A4 and SCN-008#A2.
 *
 * <p>Every test invokes the real {@link ReservationService} bean in-process against the embedded H2
 * database owned by this test class (approved {@code embedded-h2-unique-db} fixture). Because the
 * test transaction propagation is {@link Propagation#NOT_SUPPORTED}, each service call runs in its
 * own real transaction and commits; the resulting store is then observed through a brand-new JPA
 * {@link EntityManager} (fresh connection from the persistence unit) or, for the rollback
 * observation of SCN-004#A4, through a genuinely independent JDBC connection obtained directly
 * from the driver rather than from the application pool. No mocks are used and no business rule is
 * reimplemented in the test.</p>
 *
 * <p>The database name is generated once per class and every method owns UUID-coded resources, so
 * no method can observe another method's rows and no fixed identifier or calendar slot is reused
 * across runs. The injected clock is fixed at 2030-01-01T00:00:00Z and every requested timestamp is
 * genuinely in the future relative to it.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ReservationService.class,
        ReservationSeriesTransactionalObligationsTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationSeriesTransactionalObligationsTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SERIES_TIMEZONE = "Europe/Berlin";
    private static final String SERIES_FREQUENCY = "WEEKLY";
    private static final Instant FIXED_INSTANT = Instant.parse("2030-01-01T00:00:00Z");
    private static final int OCCURRENCES = 3;
    private static final long BARRIER_TIMEOUT_SECONDS = 15;
    private static final long FUTURE_TIMEOUT_SECONDS = 90;

    /** Unique in-memory database owned by this test class only. */
    private static final String DATABASE_NAME =
            "wb003tx_" + UUID.randomUUID().toString().replace("-", "");

    /** Same database, reached without the Spring connection pool for the rollback observation. */
    private static final String INDEPENDENT_JDBC_URL =
            "jdbc:h2:mem:" + DATABASE_NAME
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

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
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
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
    // "Across the transition all occurrences keep identical local start and
    // end wall-clock times while their UTC instants reflect the zone offset
    // change."
    // ---------------------------------------------------------------------
    @Test
    void scn002AllOccurrencesKeepIdenticalLocalStartAndEndWallClockAcrossTheTransition() {
        DstWindow window = dstWindow();
        BookableResource resource = newResource();
        String title = "scn002-wall-clock-" + UUID.randomUUID();

        OffsetDateTime startsAt = window.firstLocal().toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);
        createSeries(resource.getId(), title, startsAt, endsAt, OCCURRENCES);

        List<PersistedRow> rows = readRows(resource.getId());
        assertEquals(OCCURRENCES, rows.size(),
                "all three occurrences of the DST-spanning series must be stored, but saw "
                        + rows.size());
        assertTrue(rows.stream().allMatch(row -> title.equals(row.title())),
                "every observed row must belong to the requested series, but saw "
                        + rows.stream().map(PersistedRow::title).toList());

        List<PersistedRow> ordered = rows.stream()
                .sorted(Comparator.comparing(
                        (PersistedRow row) -> row.startsAt().atZone(BERLIN).toLocalDateTime()))
                .toList();

        LocalTime expectedLocalStart = window.firstLocal().toLocalTime();
        LocalTime expectedLocalEnd = expectedLocalStart.plusHours(1);
        for (PersistedRow row : ordered) {
            assertEquals(expectedLocalStart, row.startsAt().atZone(BERLIN).toLocalTime(),
                    "occurrence " + row.id() + " must keep the requested local start wall-clock "
                            + "time, but rendered "
                            + row.startsAt().atZone(BERLIN).toLocalDateTime());
            assertEquals(expectedLocalEnd, row.endsAt().atZone(BERLIN).toLocalTime(),
                    "occurrence " + row.id() + " must keep the requested local end wall-clock "
                            + "time, but rendered "
                            + row.endsAt().atZone(BERLIN).toLocalDateTime());
        }

        for (int i = 0; i < ordered.size() - 1; i++) {
            assertEquals(ordered.get(i).startsAt().atZone(BERLIN).toLocalDate().plusWeeks(1),
                    ordered.get(i + 1).startsAt().atZone(BERLIN).toLocalDate(),
                    "consecutive occurrences must fall exactly one local week apart");
        }

        ZoneOffsetTransition transition = BERLIN.getRules().nextTransition(FIXED_INSTANT);
        assertNotNull(transition, "a future Europe/Berlin DST transition must exist");
        assertTrue(transition.getInstant().isAfter(ordered.get(0).startsAt())
                        && transition.getInstant().isBefore(ordered.get(2).startsAt()),
                "precondition: the persisted three-week window must really span the selected "
                        + "transition at " + transition.getInstant() + ", but the occurrences were "
                        + ordered.stream().map(PersistedRow::startsAt).toList());

        Set<ZoneOffset> startOffsets = ordered.stream()
                .map(row -> row.startsAt().atZone(BERLIN).getOffset())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(2, startOffsets.size(),
                "the three occurrences must span exactly one offset change, but rendered offsets "
                        + startOffsets);

        for (int i = 0; i < ordered.size() - 1; i++) {
            PersistedRow current = ordered.get(i);
            PersistedRow next = ordered.get(i + 1);
            long currentStartOffset =
                    current.startsAt().atZone(BERLIN).getOffset().getTotalSeconds();
            long nextStartOffset =
                    next.startsAt().atZone(BERLIN).getOffset().getTotalSeconds();
            long expectedStartGapSeconds =
                    Duration.ofDays(7).toSeconds() + currentStartOffset - nextStartOffset;
            assertEquals(expectedStartGapSeconds,
                    Duration.between(current.startsAt(), next.startsAt()).toSeconds(),
                    "the persisted UTC start instants must reflect the zone offset change between "
                            + "occurrences " + i + " and " + (i + 1));

            long currentEndOffset =
                    current.endsAt().atZone(BERLIN).getOffset().getTotalSeconds();
            long nextEndOffset =
                    next.endsAt().atZone(BERLIN).getOffset().getTotalSeconds();
            long expectedEndGapSeconds =
                    Duration.ofDays(7).toSeconds() + currentEndOffset - nextEndOffset;
            assertEquals(expectedEndGapSeconds,
                    Duration.between(current.endsAt(), next.endsAt()).toSeconds(),
                    "the persisted UTC end instants must reflect the zone offset change between "
                            + "occurrences " + i + " and " + (i + 1));
        }
    }

    // ---------------------------------------------------------------------
    // SCN-004#A4
    // "An independent connection opened after the 409 observes no partially
    // created occurrence."
    // ---------------------------------------------------------------------
    @Test
    void scn004IndependentConnectionOpenedAfterTheConflictObservesNoPartialOccurrence()
            throws Exception {
        BookableResource resource = newResource();
        String blockerTitle = "scn004-blocker-" + UUID.randomUUID();
        String seriesTitle = "scn004-series-" + UUID.randomUUID();
        ZonedDateTime firstLocal = ZonedDateTime.of(2030, 1, 8, 9, 0, 0, 0, BERLIN);
        ZonedDateTime secondLocal = firstLocal.plusWeeks(1);
        OffsetDateTime startsAt = firstLocal.toOffsetDateTime();
        OffsetDateTime endsAt = startsAt.plusHours(1);

        // Precondition: a genuine one-off reservation occupies only the second weekly occurrence.
        service.create(new BookingInput(resource.getId(), blockerTitle,
                        secondLocal.toInstant(), secondLocal.plusHours(1).toInstant()),
                "qa-blocker-" + UUID.randomUUID());

        List<PersistedRow> before = readRows(resource.getId());
        assertEquals(1, before.size(),
                "precondition: only the second weekly slot must be occupied, but saw "
                        + before.size() + " rows");
        assertEquals(blockerTitle, before.get(0).title(),
                "precondition: the occupied slot must be the pre-existing blocker");
        assertNull(before.get(0).seriesId(),
                "precondition: the blocker is a one-off reservation without a series identifier");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> createSeries(resource.getId(), seriesTitle, startsAt, endsAt, OCCURRENCES));
        assertTrue(isConflict(failure),
                "precondition: the occupied second occurrence must be rejected with 409, but got: "
                        + failure);

        // The observation below uses a connection created directly from the driver after the 409,
        // so it cannot reuse any session, statement cache or transaction of the failed service call.
        List<RawReservationRow> observed =
                readThroughIndependentConnection(resource.getId());

        assertTrue(observed.stream().noneMatch(row -> seriesTitle.equals(row.title())),
                "an independent connection opened after the 409 must observe no partially created "
                        + "occurrence of the rejected series, but saw " + observed);
        assertEquals(1, observed.size(),
                "the independent connection must observe exactly the pre-existing blocker and no "
                        + "partial occurrence, but saw " + observed);
        assertEquals(blockerTitle, observed.get(0).title(),
                "the only committed row must remain the pre-existing blocker, but saw "
                        + observed.get(0).title());
    }

    // ---------------------------------------------------------------------
    // SCN-008#A2
    // "With a concurrent recurring series and one-off for the same slot, at
    // most one of the two requests creates any reservation for that slot."
    // ---------------------------------------------------------------------
    @Test
    @Timeout(150)
    void scn008ConcurrentSeriesAndOneOffCreateReservationsForTheSlotAtMostOnce() {
        // Serial controls on separate, freshly owned rooms prove that both racing payloads are
        // individually valid, so a failure of the raced pair cannot be blamed on the payload or on
        // the injected clock and the "at most one" check cannot pass vacuously for a bad request.
        OffsetDateTime seriesStartsAt = ZonedDateTime
                .of(2030, 6, 4, 9, 0, 0, 0, BERLIN).toOffsetDateTime();
        OffsetDateTime seriesEndsAt = seriesStartsAt.plusHours(1);
        Instant oneOffStartsAt = seriesStartsAt.plusMinutes(15).toInstant();
        Instant oneOffEndsAt = seriesStartsAt.plusMinutes(45).toInstant();

        BookableResource seriesControlRoom = newResource();
        String controlSeriesTitle = "scn008-control-series-" + UUID.randomUUID();
        createSeries(seriesControlRoom.getId(), controlSeriesTitle, seriesStartsAt, seriesEndsAt,
                OCCURRENCES);
        assertEquals(OCCURRENCES, readRows(seriesControlRoom.getId()).size(),
                "control: the recurring payload must be accepted and fully persisted on a free room");

        BookableResource oneOffControlRoom = newResource();
        String controlOneOffTitle = "scn008-control-one-off-" + UUID.randomUUID();
        ReservationView controlOneOff = service.create(
                new BookingInput(oneOffControlRoom.getId(), controlOneOffTitle,
                        oneOffStartsAt, oneOffEndsAt),
                "qa-one-off-control-" + UUID.randomUUID());
        assertNotNull(controlOneOff,
                "control: the one-off payload must be accepted on a free room");
        assertEquals(1, readRows(oneOffControlRoom.getId()).size(),
                "control: the accepted one-off must persist exactly one reservation");

        BookableResource racedRoom = newResource();
        assertTrue(racedRoom.isActive(), "precondition: the raced room must be active");
        assertEquals(0, readRows(racedRoom.getId()).size(),
                "precondition: the raced room must have no prior reservations in the slot");

        String seriesTitle = "scn008-recurring-" + UUID.randomUUID();
        String oneOffTitle = "scn008-one-off-" + UUID.randomUUID();

        List<Outcome> outcomes = runConcurrently(
                () -> createSeries(racedRoom.getId(), seriesTitle, seriesStartsAt, seriesEndsAt,
                        OCCURRENCES),
                () -> service.create(
                        new BookingInput(racedRoom.getId(), oneOffTitle, oneOffStartsAt,
                                oneOffEndsAt),
                        "qa-one-off-" + UUID.randomUUID()));
        assertEquals(2, outcomes.size(),
                "control: both concurrent attempts must settle before the store is observed");

        Outcome seriesOutcome = outcomes.get(0);
        Outcome oneOffOutcome = outcomes.get(1);

        // Non-vacuity: the slot is free and both payloads are individually valid, so exactly one of
        // the two competing requests must succeed; both failing would make the frozen "at most one"
        // bound vacuous.
        long successCount = outcomes.stream().filter(Outcome::succeeded).count();
        assertEquals(1, successCount,
                "exactly one of the two valid competing requests must succeed on the free slot, but "
                        + successCount + " succeeded; outcomes: " + describe(outcomes));

        List<PersistedRow> rows = readRows(racedRoom.getId());
        boolean seriesCreatedForSlot = rows.stream()
                .anyMatch(row -> seriesTitle.equals(row.title())
                        && overlaps(row.startsAt(), row.endsAt(), oneOffStartsAt, oneOffEndsAt));
        boolean oneOffCreatedForSlot = rows.stream()
                .anyMatch(row -> oneOffTitle.equals(row.title())
                        && overlaps(row.startsAt(), row.endsAt(), oneOffStartsAt, oneOffEndsAt));

        // SCN-008#A2: at most one of the two requests creates any reservation for the slot.
        assertFalse(seriesCreatedForSlot && oneOffCreatedForSlot,
                "at most one of the concurrent recurring and one-off requests may create a "
                        + "reservation for the same slot, but both did; committed rows were " + rows
                        + "; outcomes: " + describe(outcomes));
        assertEquals(1, (seriesCreatedForSlot ? 1 : 0) + (oneOffCreatedForSlot ? 1 : 0),
                "exactly one of the two competing requests must have created a reservation for the "
                        + "slot; committed rows were " + rows + "; outcomes: "
                        + describe(outcomes));

        if (seriesCreatedForSlot) {
            assertTrue(seriesOutcome.succeeded(),
                    "the request that created the slot reservation must be the successful one; "
                            + "outcomes: " + describe(outcomes));
            assertEquals(OCCURRENCES, rows.size(),
                    "the winning series must persist every occurrence and nothing else, but saw "
                            + rows.size() + " rows");
            assertTrue(rows.stream().allMatch(row -> seriesTitle.equals(row.title())),
                    "every committed row must belong to the winning series, but saw "
                            + rows.stream().map(PersistedRow::title).toList());
            UUID seriesId = rows.get(0).seriesId();
            assertNotNull(seriesId,
                    "the winning series must persist one non-null series identifier");
            assertTrue(rows.stream().allMatch(row -> seriesId.equals(row.seriesId())),
                    "every persisted occurrence of the winner must share its series identifier, "
                            + "but saw " + rows.stream().map(PersistedRow::seriesId).toList());
        } else {
            assertTrue(oneOffOutcome.succeeded(),
                    "the request that created the slot reservation must be the successful one; "
                            + "outcomes: " + describe(outcomes));
            assertEquals(1, rows.size(),
                    "the winning one-off must persist exactly one reservation, but saw "
                            + rows.size() + " rows");
            assertEquals(oneOffTitle, rows.get(0).title(),
                    "the only committed row must belong to the winning one-off, but saw "
                            + rows.get(0).title());
            assertTrue(rows.stream().noneMatch(row -> seriesTitle.equals(row.title())),
                    "no occurrence of the losing series may remain in the store");
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures and observation helpers
    // ---------------------------------------------------------------------

    private BookableResource newResource() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        BookableResource resource = new BookableResource(
                "W" + suffix, "WB003 TX QA " + suffix, ResourceType.ROOM, "HQ", 2, 10, true, false);
        return resources.save(resource);
    }

    private ReservationSeriesView createSeries(long resourceId, String title, OffsetDateTime startsAt,
                                               OffsetDateTime endsAt, int occurrences) {
        ReservationSeriesInput input = new ReservationSeriesInput(resourceId, title, startsAt,
                endsAt, SERIES_TIMEZONE, SERIES_FREQUENCY, occurrences);
        assertEquals(startsAt, input.startsAt(),
                "input control: startsAt must retain its supplied offset");
        assertEquals(endsAt, input.endsAt(),
                "input control: endsAt must retain its supplied offset");
        assertEquals(SERIES_TIMEZONE, input.timezone(),
                "input control: the series timezone must be retained");
        assertEquals(SERIES_FREQUENCY, input.frequency(),
                "input control: the weekly frequency must be retained");
        assertEquals(occurrences, input.occurrences(),
                "input control: the requested occurrence count must be retained");
        return service.createSeries(input, "qa-" + UUID.randomUUID());
    }

    /**
     * Derives the next Europe/Berlin DST transition after the injected clock and returns a
     * three-occurrence weekly window that really spans it. Both the transition and the window are
     * computed dynamically, so the test never depends on a fixed calendar date.
     */
    private static DstWindow dstWindow() {
        ZoneRules rules = BERLIN.getRules();
        ZoneOffsetTransition transition = rules.nextTransition(FIXED_INSTANT);
        assertNotNull(transition,
                "a future Europe/Berlin DST transition must exist after the injected clock");

        java.time.LocalDate transitionDate = transition.getDateTimeBefore().toLocalDate();
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
                            "select r.id, r.startsAt, r.endsAt, r.seriesId, r.title from Reservation r "
                                    + "where r.resource.id = :resourceId order by r.id",
                            Object[].class)
                    .setParameter("resourceId", resourceId)
                    .getResultList();
            List<PersistedRow> result = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                result.add(new PersistedRow(
                        ((Number) row[0]).longValue(),
                        toInstant(row[1]),
                        toInstant(row[2]),
                        (UUID) row[3],
                        (String) row[4]));
            }
            return result;
        } finally {
            entityManager.close();
        }
    }

    /**
     * Reads the committed reservation rows of one owned resource through a JDBC connection created
     * directly by the driver, after the conflicting request has been rejected. This connection is in
     * no way enlisted in the failed service transaction and is independent of the application
     * connection pool.
     *
     * <p>The raw statement is filtered on the owned resource, so rows committed by other methods
     * sharing the same in-memory database can never be observed. The resource-id column is
     * discovered from the result-set metadata of the store itself and the resource id is bound as a
     * prepared-statement parameter.</p>
     */
    private List<RawReservationRow> readThroughIndependentConnection(long resourceId)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(INDEPENDENT_JDBC_URL, "sa", "")) {
            assertFalse(connection.isClosed(), "the independent connection must be open");
            String table = reservationTable(connection);
            requireSimpleIdentifier(table);

            int idColumn;
            int titleColumn;
            String resourceColumn;
            try (Statement probe = connection.createStatement();
                 ResultSet metadataRows = probe.executeQuery(
                         "select * from \"" + table + "\" where 1 = 0")) {
                ResultSetMetaData meta = metadataRows.getMetaData();
                idColumn = columnIndex(meta, "ID");
                titleColumn = columnIndex(meta, "TITLE");
                resourceColumn = meta.getColumnLabel(columnIndex(meta, "RESOURCE_ID"));
                requireSimpleIdentifier(resourceColumn);
            }

            String sql = "select * from \"" + table + "\" where " + resourceColumn + " = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, resourceId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<RawReservationRow> result = new ArrayList<>();
                    while (resultSet.next()) {
                        result.add(new RawReservationRow(resultSet.getLong(idColumn),
                                resultSet.getString(titleColumn)));
                    }
                    return result;
                }
            }
        }
    }

    /** Locates the reservation table through the store's own metadata, not a hardcoded name. */
    private static String reservationTable(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = connection.getSchema();
        List<String> tables = new ArrayList<>();
        try (ResultSet tableRows = metadata.getTables(
                connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
            while (tableRows.next()) {
                tables.add(tableRows.getString("TABLE_NAME"));
            }
        }
        for (String table : tables) {
            try (ResultSet columns = metadata.getColumns(
                    connection.getCatalog(), schema, table, "%")) {
                while (columns.next()) {
                    if ("SERIESID".equals(normalize(columns.getString("COLUMN_NAME")))) {
                        return table;
                    }
                }
            }
        }
        throw new AssertionError(
                "the reservation table could not be located through the independent connection");
    }

    private static int columnIndex(ResultSetMetaData meta, String expected) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (normalize(expected).equals(normalize(meta.getColumnLabel(i)))) {
                return i;
            }
        }
        throw new AssertionError("column " + expected + " is absent from the reservation row");
    }

    private static String normalize(String identifier) {
        return identifier.replace("_", "").toUpperCase(Locale.ROOT);
    }

    private static void requireSimpleIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("unexpected SQL identifier from metadata: " + identifier);
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

    /** Half-open interval overlap, so rows that only touch at an endpoint do not overlap. */
    private static boolean overlaps(Instant firstStart, Instant firstEnd, Instant secondStart,
                                    Instant secondEnd) {
        return firstStart.isBefore(secondEnd) && secondStart.isBefore(firstEnd);
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

    /**
     * Releases both actions through one barrier so the two service transactions really compete,
     * then waits for both with bounded timeouts. A barrier or task-level harness failure is a test
     * error, not a business outcome, and fails the test through {@link Future#get}.
     */
    private static List<Outcome> runConcurrently(Callable<Object> first, Callable<Object> second) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "wb003-race-" + UUID.randomUUID());
            thread.setDaemon(true);
            return thread;
        });
        try {
            Callable<Outcome> firstTask = () -> awaitReleaseThenRun(barrier, first);
            Callable<Outcome> secondTask = () -> awaitReleaseThenRun(barrier, second);
            List<Future<Outcome>> futures = new ArrayList<>(2);
            futures.add(executor.submit(firstTask));
            futures.add(executor.submit(secondTask));

            List<Outcome> outcomes = new ArrayList<>(2);
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                } catch (TimeoutException timeout) {
                    future.cancel(true);
                    fail("a concurrent request did not complete within "
                            + FUTURE_TIMEOUT_SECONDS + " seconds");
                } catch (ExecutionException failure) {
                    fail("a concurrent request task failed unexpectedly: " + failure.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail("interrupted while awaiting the concurrent requests");
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Outcome awaitReleaseThenRun(CyclicBarrier barrier, Callable<Object> action) {
        try {
            barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception releaseFailure) {
            throw new IllegalStateException(
                    "both concurrent requests could not be released together", releaseFailure);
        }
        try {
            return new Outcome(action.call(), null);
        } catch (RuntimeException businessFailure) {
            // A failing attempt is recorded, not asserted: the loser's status is unspecified
            // (AMB-008). Only the frozen mutual-exclusion bound and the winner's durable state are
            // asserted.
            return new Outcome(null, businessFailure);
        } catch (Exception checkedFailure) {
            return new Outcome(null, new IllegalStateException(checkedFailure));
        }
    }

    private static String describe(List<Outcome> outcomes) {
        return outcomes.stream()
                .map(outcome -> outcome.succeeded()
                        ? "SUCCESS " + outcome.value()
                        : "FAILURE " + outcome.failure().getClass().getName() + ": "
                                + outcome.failure().getMessage())
                .collect(Collectors.joining(" | "));
    }

    private record PersistedRow(Long id, Instant startsAt, Instant endsAt, UUID seriesId,
                                String title) {
    }

    private record RawReservationRow(long id, String title) {
    }

    private record DstWindow(ZonedDateTime firstLocal, ZonedDateTime thirdLocal) {
    }

    private record Outcome(Object value, RuntimeException failure) {
        boolean succeeded() {
            return failure == null;
        }
    }
}
