package com.example.workspace.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Database-layer verification of the FEATURE-001 recurring-reservation contract
 * (DOC-001): the accepted series must be materialised as exactly one persisted
 * reservation row per requested occurrence, every row must carry the persisted
 * series identifier, approval-required rooms must persist PENDING occurrences, a
 * rejected conflict must commit nothing, and concurrent requests must never leave
 * overlapping rows.
 *
 * <p><strong>Schema path used: the real Flyway migration path.</strong> The test
 * context starts with {@code spring.flyway.enabled=true} at the application's own
 * migration location on an in-memory H2 database in PostgreSQL mode, and Hibernate
 * is restricted to {@code ddl-auto=validate}. Against this context the application's
 * versioned migrations applied successfully (two migrations, including the
 * series-identifier change) and Hibernate validated the entity mapping against the
 * migrated schema, so no Hibernate-generated fallback was needed and no
 * uncontrolled schema change is used.
 * <strong>Limitation:</strong> H2 in PostgreSQL mode is an approximation of the
 * production database, so PostgreSQL-specific DDL semantics are not fully
 * exercised; this is migration-path coverage on H2 rather than a guarantee of
 * PostgreSQL parity. The JDBC-metadata discovery below additionally fails loudly
 * when the migrated schema lacks the series-identifier or status column required
 * by the business contract.</p>
 *
 * <p>All request/response interaction goes through the in-process HTTP boundary
 * (MockMvc) because no approved internal Java API contract exists; persistence
 * facts are read from the real datasource through {@link JdbcTemplate}. Table and
 * column names are never hardcoded: they are discovered at runtime from
 * {@code INFORMATION_SCHEMA} exactly as the established integration test does, and
 * the discovery fails loudly when the series-identifier or status column required
 * by the business contract is absent.</p>
 *
 * <p>Documented exclusions: DST gap/overlap policy (AMB-001) is avoided by using
 * only DST-stable future windows; exact 400/409 error bodies (AMB-002/AMB-003) and
 * the concurrency loser's status (AMB-004) are not asserted. Each test creates its
 * own active room with a unique code and a future window, so the tests are order
 * independent; the documented API has no delete endpoint, and the dedicated
 * in-memory schema is discarded with the context.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:reservation-series-persistence-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration"
        })
@AutoConfigureMockMvc
class ReservationSeriesPersistenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZONE_ID = "Europe/Berlin";
    private static final ZoneId ZONE = ZoneId.of(ZONE_ID);
    private static final LocalTime SERIES_LOCAL_START = LocalTime.of(9, 0);
    private static final Duration ONE_HOUR = Duration.ofMinutes(60);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ==================================================================
    // SCN-001 / REQ-001, REQ-002, SE-001
    // ==================================================================

    /** SCN-001#A1 - the request succeeds and reports three occurrences. */
    @Test
    void scn001a1SeriesRequestSucceedsAndReportsThreeOccurrences() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S1A1"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 3);

        MvcResult created = postSeries(resourceId, "SCN-001 A1 weekly series " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 3);
        assertSuccess(created, "creating a three-occurrence weekly series");

        List<Instant> reported = occurrenceStartsInResponse(created, ZONE, SERIES_LOCAL_START);
        assertEquals(3, reported.size(),
                "the response of an accepted three-occurrence series must report exactly three occurrences "
                        + "starting at the requested local time " + SERIES_LOCAL_START + " " + ZONE_ID
                        + "; reported start instants: " + reported + "; response body: " + body(created));
    }

    /** SCN-001#A2 - all three occurrences share the supplied-zone local start time. */
    @Test
    void scn001a2AllThreeOccurrencesShareTheSuppliedZoneLocalStartTime() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S1A2"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 3);

        MvcResult created = postSeries(resourceId, "SCN-001 A2 weekly series " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 3);
        assertSuccess(created, "creating a three-occurrence weekly series");

        List<Instant> reported = occurrenceStartsInResponse(created, ZONE, SERIES_LOCAL_START);
        assertEquals(3, reported.size(),
                "three occurrences at local " + SERIES_LOCAL_START + " " + ZONE_ID
                        + " are expected; reported start instants: " + reported + "; response body: "
                        + body(created));
        for (Instant occurrence : reported) {
            assertEquals(SERIES_LOCAL_START, occurrence.atZone(ZONE).toLocalTime(),
                    "occurrence " + occurrence + " does not start at the series local wall-clock time "
                            + SERIES_LOCAL_START + " in the supplied zone " + ZONE_ID + "; response body: "
                            + body(created));
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (Instant occurrence : reported) {
            dates.add(occurrence.atZone(ZONE).toLocalDate());
        }
        Set<LocalDate> expectedDates = new LinkedHashSet<>();
        for (int week = 0; week < 3; week++) {
            expectedDates.add(firstStart.toLocalDate().plusWeeks(week));
        }
        assertEquals(expectedDates, dates,
                "the three occurrences must fall on consecutive weekly dates while keeping the same local "
                        + "wall-clock start time; response body: " + body(created));
    }

    /** SCN-001#A3 - persisted rows correspond to the three expanded occurrences. */
    @Test
    void scn001a3PersistedRowsMatchTheThreeExpandedOccurrences() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S1A3"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> expectedStarts = occurrenceInstants(firstStart, 3);

        MvcResult created = postSeries(resourceId, "SCN-001 A3 weekly series " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 3);
        assertSuccess(created, "creating a three-occurrence weekly series");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(expectedStarts.size(), rows.size(),
                "exactly one persisted reservation row per expanded occurrence is expected; persisted rows: "
                        + rows);
        assertEquals(new LinkedHashSet<>(expectedStarts), startInstants(rows),
                "the persisted start instants must match the three weekly occurrences expanded in " + ZONE_ID
                        + " (first local start " + firstStart + "); persisted rows: " + rows);
        for (Map<String, Object> row : rows) {
            assertEquals(ONE_HOUR, windowOf(row).duration(),
                    "every persisted occurrence must keep the requested 60 minute duration; row: " + row);
        }
    }

    // ==================================================================
    // SCN-003 / REQ-001, REQ-009
    // ==================================================================

    /** SCN-003#A1 - occurrences = 2 succeeds and persists 2 occurrences. */
    @Test
    void scn003a1TwoOccurrencesAcceptedAndPersisted() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S3A1"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 2);
        List<Instant> expectedStarts = occurrenceInstants(firstStart, 2);

        MvcResult created = postSeries(resourceId, "SCN-003 A1 two occurrences " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 2);
        assertSuccess(created, "creating a weekly series with the minimum occurrence count 2");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(2, rows.size(),
                "occurrences = 2 must persist exactly two reservation rows; persisted rows: " + rows);
        assertEquals(new LinkedHashSet<>(expectedStarts), startInstants(rows),
                "the two persisted start instants must equal the two weekly occurrences; persisted rows: " + rows);
    }

    /** SCN-003#A2 - occurrences = 12 succeeds and persists 12 occurrences. */
    @Test
    void scn003a2TwelveOccurrencesAcceptedAndPersisted() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S3A2"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 12);
        List<Instant> expectedStarts = occurrenceInstants(firstStart, 12);

        MvcResult created = postSeries(resourceId, "SCN-003 A2 twelve occurrences " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 12);
        assertSuccess(created, "creating a weekly series with the maximum occurrence count 12");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(12, rows.size(),
                "occurrences = 12 must persist exactly twelve reservation rows; persisted rows: " + rows);
        assertEquals(new LinkedHashSet<>(expectedStarts), startInstants(rows),
                "the twelve persisted start instants must equal the twelve weekly occurrences; persisted rows: "
                        + rows);
    }

    // ==================================================================
    // SCN-005 / REQ-003
    // ==================================================================

    /** SCN-005#A1 - 15-minute occurrences are accepted and persisted. */
    @Test
    void scn005a1FifteenMinuteOccurrencesAcceptedAndPersisted() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S5A1"));
        LocalDateTime firstStart = stableWeeklyStart(LocalTime.of(12, 0), 2);
        Duration fifteenMinutes = Duration.ofMinutes(15);
        List<Instant> expectedStarts = occurrenceInstants(firstStart, 2);

        MvcResult created = postSeries(resourceId, "SCN-005 A1 fifteen minute series " + UUID.randomUUID(),
                firstStart, fifteenMinutes, 2);
        assertSuccess(created, "creating a weekly series whose occurrences last exactly 15 minutes");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(2, rows.size(),
                "a 15 minute series must persist one row per occurrence; persisted rows: " + rows);
        assertEquals(new LinkedHashSet<>(expectedStarts), startInstants(rows),
                "the persisted start instants must equal the two weekly occurrences; persisted rows: " + rows);
        for (Map<String, Object> row : rows) {
            assertEquals(fifteenMinutes, windowOf(row).duration(),
                    "every persisted occurrence must last exactly the accepted boundary of 15 minutes; row: "
                            + row);
        }
    }

    /** SCN-005#A2 - 8-hour occurrences are accepted and persisted. */
    @Test
    void scn005a2EightHourOccurrencesAcceptedAndPersisted() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S5A2"));
        LocalDateTime firstStart = stableWeeklyStart(LocalTime.of(8, 0), 2);
        Duration eightHours = Duration.ofHours(8);
        List<Instant> expectedStarts = occurrenceInstants(firstStart, 2);

        MvcResult created = postSeries(resourceId, "SCN-005 A2 eight hour series " + UUID.randomUUID(),
                firstStart, eightHours, 2);
        assertSuccess(created, "creating a weekly series whose occurrences last exactly 8 hours");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(2, rows.size(),
                "an 8 hour series must persist one row per occurrence; persisted rows: " + rows);
        assertEquals(new LinkedHashSet<>(expectedStarts), startInstants(rows),
                "the persisted start instants must equal the two weekly occurrences; persisted rows: " + rows);
        for (Map<String, Object> row : rows) {
            assertEquals(eightHours, windowOf(row).duration(),
                    "every persisted occurrence must last exactly the accepted boundary of 8 hours; row: " + row);
        }
    }

    // ==================================================================
    // SCN-009#A3 / REQ-004, SE-003
    // ==================================================================

    /** SCN-009#A3 - a rejected conflicting series adds zero reservation rows. */
    @Test
    void scn009a3ConflictingSeriesAddsZeroRowsToTheReservationTable() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S9A3"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(firstStart, 3);

        MvcResult oneOff = postOneOff(resourceId, "SCN-009 A3 pre-existing " + UUID.randomUUID(),
                occurrences.get(1));
        assertSuccess(oneOff, "creating the pre-existing one-off reservation that conflicts with the middle "
                + "occurrence of the proposed series");

        long countBefore = totalReservationCount();

        MvcResult attempt = postSeries(resourceId, "SCN-009 A3 conflicting series " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 3);
        assertEquals(409, attempt.getResponse().getStatus(),
                "a series whose middle occurrence conflicts with an existing reservation must be rejected with "
                        + "HTTP 409 (REQ-004); response body: " + body(attempt));

        long countAfter = totalReservationCount();
        assertEquals(countBefore, countAfter,
                "a rejected conflicting series must commit exactly zero new reservation rows anywhere in the "
                        + "reservation table (before=" + countBefore + ", after=" + countAfter + ")");
    }

    // ==================================================================
    // SCN-010#A3 / REQ-005, SE-001
    // ==================================================================

    /** SCN-010#A3 - the seriesId is unchanged on subsequent reads. */
    @Test
    void scn010a3SeriesIdIsUnchangedOnSubsequentReads() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S10A3"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> expectedStarts = occurrenceInstants(firstStart, 3);

        MvcResult created = postSeries(resourceId, "SCN-010 A3 weekly series " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 3);
        assertSuccess(created, "creating a three-occurrence weekly series");

        String persistedSeriesId = singleSeriesValue(rowsForResource(resourceId));
        Map<Instant, String> creationRead = seriesIdsByStart(reservationNodes(created));
        assertEquals(expectedStarts.size(), creationRead.size(),
                "the creation response must expose one seriesId per occurrence; response body: " + body(created));

        MvcResult firstReRead = getJson("/api/reservations");
        assertSuccess(firstReRead, "re-reading the reservations for the first time");
        Map<Instant, String> firstReReadSeriesIds = seriesIdsForResource(root(firstReRead), resourceId);

        MvcResult secondReRead = getJson("/api/reservations");
        assertSuccess(secondReRead, "re-reading the reservations for the second time");
        Map<Instant, String> secondReReadSeriesIds = seriesIdsForResource(root(secondReRead), resourceId);

        for (Instant occurrence : expectedStarts) {
            String fromCreation = creationRead.get(occurrence);
            assertNotNull(fromCreation,
                    "the creation response exposes no seriesId for occurrence " + occurrence + "; response "
                            + "body: " + body(created));
            assertEquals(persistedSeriesId, fromCreation,
                    "the creation-response seriesId of occurrence " + occurrence
                            + " must equal the persisted series identifier");

            String fromFirstReRead = firstReReadSeriesIds.get(occurrence);
            assertNotNull(fromFirstReRead,
                    "the first subsequent read exposes no seriesId for occurrence " + occurrence + "; response "
                            + "body: " + body(firstReRead));
            String fromSecondReRead = secondReReadSeriesIds.get(occurrence);
            assertNotNull(fromSecondReRead,
                    "the second subsequent read exposes no seriesId for occurrence " + occurrence + "; response "
                            + "body: " + body(secondReRead));

            assertEquals(fromCreation, fromFirstReRead,
                    "the seriesId of occurrence " + occurrence + " must be unchanged on the first subsequent "
                            + "read (creation=" + fromCreation + ", subsequent=" + fromFirstReRead + ")");
            assertEquals(fromCreation, fromSecondReRead,
                    "the seriesId of occurrence " + occurrence + " must stay unchanged on a later read "
                            + "(creation=" + fromCreation + ", later=" + fromSecondReRead + ")");
        }
    }

    // ==================================================================
    // SCN-012 / REQ-006, SE-002
    // ==================================================================

    /** SCN-012#A1 - every series occurrence is persisted with PENDING status. */
    @Test
    void scn012a1EverySeriesOccurrenceIsPersistedPending() throws Exception {
        long resourceId = createApprovalRequiredResource(uniqueCode("S12A1"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 2);

        MvcResult created = postSeries(resourceId, "SCN-012 A1 approval series " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 2);
        assertSuccess(created, "creating a two-occurrence weekly series for an approval-required room");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(2, rows.size(),
                "the approval-required series must persist one row per occurrence; persisted rows: " + rows);
        for (Map<String, Object> row : rows) {
            Object status = row.get(statusColumn());
            assertNotNull(status,
                    "every persisted series occurrence must carry a status; row: " + row);
            assertEquals("PENDING", String.valueOf(status).toUpperCase(Locale.ROOT),
                    "every occurrence of a series booked on an approval-required room must be persisted with "
                            + "PENDING status (REQ-006/SE-002); row: " + row);
        }
    }

    /** SCN-012#A2 - no series occurrence is auto-confirmed. */
    @Test
    void scn012a2NoSeriesOccurrenceIsAutoConfirmed() throws Exception {
        long resourceId = createApprovalRequiredResource(uniqueCode("S12A2"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 2);

        MvcResult created = postSeries(resourceId, "SCN-012 A2 approval series " + UUID.randomUUID(),
                firstStart, ONE_HOUR, 2);
        assertSuccess(created, "creating a two-occurrence weekly series for an approval-required room");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(2, rows.size(),
                "the approval-required series must persist one row per occurrence; persisted rows: " + rows);
        for (Map<String, Object> row : rows) {
            Object status = row.get(statusColumn());
            assertNotNull(status,
                    "every persisted series occurrence must carry a status; row: " + row);
            String normalised = String.valueOf(status).toUpperCase(Locale.ROOT);
            assertFalse(normalised.contains("CONFIRM"),
                    "no occurrence of a series booked on an approval-required room may be auto-confirmed; row: "
                            + row);
            assertNotEquals("APPROVED", normalised,
                    "no occurrence of a series booked on an approval-required room may be persisted as approved "
                            + "without a manager decision; row: " + row);
        }
    }

    // ==================================================================
    // SCN-014#A2 / REQ-007, SE-004
    // ==================================================================

    /** SCN-014#A2 - persisted reservations for the room are not overlapping. */
    @Test
    void scn014a2PersistedRoomReservationsAreNotOverlapping() throws Exception {
        long resourceId = createActiveResource(uniqueCode("S14A2"));
        LocalDateTime firstStart = stableWeeklyStart(SERIES_LOCAL_START, 2);
        List<Instant> occurrences = occurrenceInstants(firstStart, 2);

        ConcurrentOutcome outcome = postConcurrently(
                oneOffJson(resourceId, "SCN-014 A2 one-off " + UUID.randomUUID(), occurrences.get(0)),
                seriesJson(resourceId, "SCN-014 A2 series " + UUID.randomUUID(), firstStart, ONE_HOUR, 2));
        assertTrue(outcome.oneOffError() == null,
                "the concurrent one-off request failed before producing an HTTP response: "
                        + outcome.oneOffError());
        assertTrue(outcome.seriesError() == null,
                "the concurrent recurring request failed before producing an HTTP response: "
                        + outcome.seriesError());

        List<Window> windows = windows(rowsForResource(resourceId));
        windows.sort(Comparator.comparing(Window::start));
        for (Window window : windows) {
            assertTrue(window.end().isAfter(window.start()),
                    "a persisted reservation must end after it starts; window: " + window + "; outcomes: "
                            + outcome);
        }
        for (int index = 1; index < windows.size(); index++) {
            Window previous = windows.get(index - 1);
            Window current = windows.get(index);
            assertFalse(previous.end().isAfter(current.start()),
                    "persisted reservations for room " + resourceId + " overlap: " + previous + " and "
                            + current + "; outcomes: " + outcome);
        }
    }

    // ==================================================================
    // Fixture helpers
    // ==================================================================

    private record ConcurrentOutcome(int oneOffStatus, String oneOffBody, Throwable oneOffError,
                                     int seriesStatus, String seriesBody, Throwable seriesError) {

        @Override
        public String toString() {
            return "oneOff=[status=" + oneOffStatus + ", body=" + oneOffBody + ", error=" + oneOffError
                    + "], series=[status=" + seriesStatus + ", body=" + seriesBody + ", error=" + seriesError
                    + "]";
        }
    }

    private record Submission(MvcResult result, Throwable error) {
    }

    private record Window(Instant start, Instant end) {

        Duration duration() {
            return Duration.between(start, end);
        }
    }

    private static LocalDateTime stableWeeklyStart(LocalTime localStart, int occurrences) {
        LocalDate date = LocalDate.now(ZONE).plusDays(45);
        for (int attempt = 0; attempt < 104; attempt++) {
            LocalDateTime candidate = LocalDateTime.of(date, localStart);
            ZoneOffset offset = ZONE.getRules().getOffset(candidate);
            boolean stable = true;
            for (int week = 1; week < occurrences; week++) {
                if (!ZONE.getRules().getOffset(candidate.plusWeeks(week)).equals(offset)) {
                    stable = false;
                    break;
                }
            }
            if (stable && candidate.atZone(ZONE).toInstant().isAfter(Instant.now().plus(Duration.ofDays(7)))) {
                return candidate;
            }
            date = date.plusWeeks(1);
        }
        throw new IllegalStateException("no DST-stable future weekly window found for " + localStart);
    }

    private static List<Instant> occurrenceInstants(LocalDateTime firstStart, int occurrences) {
        List<Instant> instants = new ArrayList<>();
        for (int week = 0; week < occurrences; week++) {
            instants.add(firstStart.plusWeeks(week).atZone(ZONE).toInstant());
        }
        return instants;
    }

    private static String uniqueCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private long createActiveResource(String code) throws Exception {
        return createResource(code, true, false);
    }

    private long createApprovalRequiredResource(String code) throws Exception {
        return createResource(code, true, true);
    }

    private long createResource(String code, boolean active, boolean approvalRequired) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("code", code);
        payload.put("name", "Reservation series persistence fixture " + code);
        payload.put("type", "ROOM");
        payload.put("building", "HQ");
        payload.put("floorNumber", 1);
        payload.put("capacity", 10);
        payload.put("active", active);
        payload.put("approvalRequired", approvalRequired);

        MvcResult created = postJson("/api/resources", MAPPER.writeValueAsString(payload));
        assertSuccess(created, "fixture resource creation via POST /api/resources (code " + code + ")");
        JsonNode createdResource = MAPPER.readTree(created.getResponse().getContentAsByteArray());
        assertEquals(active, createdResource.path("active").asBoolean(),
                "fixture resource " + code + " was not stored with the requested active flag; response: "
                        + body(created));
        assertEquals(approvalRequired, createdResource.path("approvalRequired").asBoolean(),
                "fixture resource " + code + " was not stored with the requested approvalRequired flag; "
                        + "an approval-required fixture is required by SCN-012. response: " + body(created));

        Long id = idForCode(createdResource, code);
        if (id == null) {
            MvcResult listed = getJson("/api/resources");
            assertSuccess(listed, "listing resources to resolve the fixture resource id");
            id = idForCode(MAPPER.readTree(listed.getResponse().getContentAsByteArray()), code);
        }
        assertNotNull(id, "could not resolve the id of fixture resource " + code + " from the API");
        return id;
    }

    private MvcResult postOneOff(long resourceId, String title, Instant start) throws Exception {
        return postJson("/api/reservations", oneOffJson(resourceId, title, start));
    }

    private String oneOffJson(long resourceId, String title, Instant start) throws Exception {
        // The one-off booking request is bound black-box: the ISO-8601 instant
        // ("...Z") representation is used for the request window and every caller
        // asserts the outcome, so binding drift fails loudly.
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("resourceId", resourceId);
        payload.put("title", title);
        payload.put("startsAt", start.toString());
        payload.put("endsAt", start.plus(ONE_HOUR).toString());
        return MAPPER.writeValueAsString(payload);
    }

    private MvcResult postSeries(long resourceId, String title, LocalDateTime firstStart,
                                 Duration duration, int occurrences) throws Exception {
        return postJson("/api/reservation-series",
                seriesJson(resourceId, title, firstStart, duration, occurrences));
    }

    private String seriesJson(long resourceId, String title, LocalDateTime firstStart,
                              Duration duration, int occurrences) throws Exception {
        OffsetDateTime startsAt = firstStart.atZone(ZONE).toOffsetDateTime();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("resourceId", resourceId);
        payload.put("title", title);
        payload.put("startsAt", startsAt.toString());
        payload.put("endsAt", startsAt.plus(duration).toString());
        payload.put("timezone", ZONE_ID);
        payload.put("frequency", "WEEKLY");
        payload.put("occurrences", occurrences);
        return MAPPER.writeValueAsString(payload);
    }

    // ==================================================================
    // In-process HTTP helpers
    // ==================================================================

    private MvcResult postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url)
                        .with(user("persistence-series-writer").roles("EMPLOYEE", "MANAGER", "ADMIN", "USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult getJson(String url) throws Exception {
        return mockMvc.perform(get(url)
                        .with(user("persistence-series-writer").roles("EMPLOYEE", "MANAGER", "ADMIN", "USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private static void assertSuccess(MvcResult result, String action) {
        int status = result.getResponse().getStatus();
        assertTrue(status >= 200 && status < 300,
                action + " failed with HTTP " + status + "; response body: " + body(result));
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static JsonNode root(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    private ConcurrentOutcome postConcurrently(String oneOffJson, String seriesJson) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> oneOff = pool.submit(() -> {
                barrier.await(20, TimeUnit.SECONDS);
                return postJson("/api/reservations", oneOffJson);
            });
            Future<MvcResult> series = pool.submit(() -> {
                barrier.await(20, TimeUnit.SECONDS);
                return postJson("/api/reservation-series", seriesJson);
            });
            Submission oneOffSubmission = await(oneOff);
            Submission seriesSubmission = await(series);
            return new ConcurrentOutcome(
                    oneOffSubmission.result() == null ? -1 : oneOffSubmission.result().getResponse().getStatus(),
                    oneOffSubmission.result() == null ? "<no response>" : body(oneOffSubmission.result()),
                    oneOffSubmission.error(),
                    seriesSubmission.result() == null ? -1 : seriesSubmission.result().getResponse().getStatus(),
                    seriesSubmission.result() == null ? "<no response>" : body(seriesSubmission.result()),
                    seriesSubmission.error());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Submission await(Future<MvcResult> future) {
        try {
            return new Submission(future.get(120, TimeUnit.SECONDS), null);
        } catch (java.util.concurrent.ExecutionException failure) {
            return new Submission(null, failure.getCause() == null ? failure : failure.getCause());
        } catch (Exception failure) {
            return new Submission(null, failure);
        }
    }

    // ==================================================================
    // JSON response observation helpers
    // ==================================================================

    private static List<Instant> occurrenceStartsInResponse(MvcResult result, ZoneId zone, LocalTime localStart)
            throws Exception {
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        List<OffsetDateTime> values = new ArrayList<>();
        collectDateTimes(root, values);
        Set<Instant> starts = new LinkedHashSet<>();
        for (OffsetDateTime value : values) {
            if (value.atZoneSameInstant(zone).toLocalTime().equals(localStart)) {
                starts.add(value.toInstant());
            }
        }
        List<Instant> sorted = new ArrayList<>(starts);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    private static void collectDateTimes(JsonNode node, List<OffsetDateTime> sink) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            OffsetDateTime parsed = parseOffsetDateTime(node.asText());
            if (parsed != null) {
                sink.add(parsed);
            }
            return;
        }
        if (node.isObject()) {
            node.forEach(child -> collectDateTimes(child, sink));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectDateTimes(child, sink);
            }
        }
    }

    private static List<JsonNode> reservationNodes(MvcResult result) throws Exception {
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        JsonNode array = findArrayByName(root, "reservations");
        if (array == null) {
            List<JsonNode> arrays = new ArrayList<>();
            collectArrays(root, arrays);
            for (JsonNode candidate : arrays) {
                if (candidate.size() > 0 && candidate.get(0).isObject()
                        && (hasFieldContaining(candidate.get(0), "start")
                        || hasFieldContaining(candidate.get(0), "seriesid"))) {
                    array = candidate;
                    break;
                }
            }
        }
        List<JsonNode> reservations = new ArrayList<>();
        if (array != null) {
            for (JsonNode element : array) {
                reservations.add(element);
            }
        }
        return reservations;
    }

    private static Map<Instant, String> seriesIdsByStart(List<JsonNode> reservations) {
        Map<Instant, String> byStart = new HashMap<>();
        for (JsonNode reservation : reservations) {
            Instant start = startInstantOf(reservation);
            JsonNode seriesId = seriesIdNode(reservation);
            assertNotNull(start, "the reservation response exposes no start date-time: " + reservation);
            assertNotNull(seriesId, "the reservation response exposes no seriesId: " + reservation);
            byStart.put(start, seriesId.asText());
        }
        return byStart;
    }

    private static Map<Instant, String> seriesIdsForResource(JsonNode listing, long resourceId) {
        Map<Instant, String> byStart = new HashMap<>();
        List<JsonNode> arrays = new ArrayList<>();
        collectArrays(listing, arrays);
        for (JsonNode array : arrays) {
            for (JsonNode entry : array) {
                if (!entry.isObject() || entry.path("resourceId").asLong(-1L) != resourceId) {
                    continue;
                }
                Instant start = startInstantOf(entry);
                JsonNode seriesId = seriesIdNode(entry);
                if (start != null && seriesId != null && !seriesId.isNull()) {
                    byStart.put(start, seriesId.asText());
                }
            }
        }
        return byStart;
    }

    private static JsonNode seriesIdNode(JsonNode reservation) {
        if (reservation == null) {
            return null;
        }
        var fields = reservation.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equalsIgnoreCase("seriesId")) {
                return field.getValue();
            }
        }
        return null;
    }

    private static Instant startInstantOf(JsonNode reservation) {
        if (reservation == null || !reservation.isObject()) {
            return null;
        }
        var fields = reservation.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey().toLowerCase(Locale.ROOT);
            if (field.getValue().isTextual() && (name.contains("start") || name.equals("from"))) {
                OffsetDateTime parsed = parseOffsetDateTime(field.getValue().asText());
                if (parsed != null) {
                    return parsed.toInstant();
                }
            }
        }
        return null;
    }

    private static OffsetDateTime parseOffsetDateTime(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException notAnOffsetDateTime) {
            // try the remaining shapes
        }
        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException notAnInstant) {
            // try the remaining shape
        }
        try {
            return ZonedDateTime.parse(text).toOffsetDateTime();
        } catch (DateTimeParseException notAZonedDateTime) {
            return null;
        }
    }

    private static JsonNode findArrayByName(JsonNode node, String name) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().equalsIgnoreCase(name) && field.getValue().isArray()) {
                    return field.getValue();
                }
                JsonNode nested = findArrayByName(field.getValue(), name);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode nested = findArrayByName(child, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static void collectArrays(JsonNode node, List<JsonNode> sink) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            sink.add(node);
            for (JsonNode child : node) {
                collectArrays(child, sink);
            }
            return;
        }
        if (node.isObject()) {
            node.forEach(child -> collectArrays(child, sink));
        }
    }

    private static boolean hasFieldContaining(JsonNode object, String token) {
        var fields = object.fields();
        while (fields.hasNext()) {
            if (fields.next().getKey().toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static Long idForCode(JsonNode root, String code) {
        List<JsonNode> matches = new ArrayList<>();
        collectObjectsWithCode(root, code, matches);
        for (JsonNode match : matches) {
            JsonNode id = match.get("id");
            if (id != null && id.canConvertToLong()) {
                return id.asLong();
            }
        }
        return null;
    }

    private static void collectObjectsWithCode(JsonNode node, String code, List<JsonNode> sink) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode codeNode = node.get("code");
            if (codeNode != null && codeNode.isTextual() && code.equals(codeNode.asText())) {
                sink.add(node);
            }
            node.forEach(child -> collectObjectsWithCode(child, code, sink));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectObjectsWithCode(child, code, sink);
            }
        }
    }

    // ==================================================================
    // Persistence helpers (schema discovered from JDBC metadata)
    // ==================================================================

    private record ReservationSchema(String table, String id, String resource, String start, String end,
                                     String series, String status, String title) {
    }

    private ReservationSchema schema;

    private ReservationSchema schema() {
        if (schema == null) {
            schema = discoverSchema();
        }
        return schema;
    }

    private ReservationSchema discoverSchema() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'", String.class);
        List<String> candidates = new ArrayList<>();
        for (String table : tables) {
            if (table.toUpperCase(Locale.ROOT).contains("RESERVATION")) {
                candidates.add(table);
            }
        }
        if (candidates.size() != 1) {
            fail("expected exactly one reservation table in the test datasource, found " + candidates
                    + "; tables: " + tables);
        }
        String table = candidates.get(0);
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = 'PUBLIC' and table_name = ?", String.class, table);

        String id = exactColumn(columns, "ID");
        if (id == null) {
            id = containsColumn(columns, "ID", Set.of("RESOURCE", "SERIES"));
        }
        String resource = containsColumn(columns, "RESOURCE", Set.of());
        String start = containsColumn(columns, "START", Set.of("END"));
        String end = containsColumn(columns, "END", Set.of("START"));
        String series = containsColumn(columns, "SERIES", Set.of());
        String status = containsColumn(columns, "STATUS", Set.of());
        String title = containsColumn(columns, "TITLE", Set.of());

        if (id == null || resource == null || start == null || end == null || title == null) {
            fail("reservation table " + table + " does not expose the columns required to verify the series "
                    + "contract (id/resource/start/end/title); columns: " + columns);
        }
        if (series == null) {
            fail("reservation table " + table + " exposes no series-identifier column, but REQ-005/SE-001 "
                    + "require every series occurrence to persist a stable series identifier; columns: "
                    + columns);
        }
        if (status == null) {
            fail("reservation table " + table + " exposes no status column, but REQ-006/SE-002 require the "
                    + "persisted occurrence status (PENDING for approval-required rooms); columns: " + columns);
        }
        return new ReservationSchema(table, id, resource, start, end, series, status, title);
    }

    private static String exactColumn(List<String> columns, String name) {
        for (String column : columns) {
            if (column.equalsIgnoreCase(name)) {
                return column;
            }
        }
        return null;
    }

    private static String containsColumn(List<String> columns, String token, Set<String> excludedTokens) {
        for (String column : columns) {
            String upper = column.toUpperCase(Locale.ROOT);
            if (!upper.contains(token)) {
                continue;
            }
            boolean excluded = false;
            for (String excludedToken : excludedTokens) {
                if (upper.contains(excludedToken)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                return column;
            }
        }
        return null;
    }

    private String seriesColumn() {
        return schema().series();
    }

    private String statusColumn() {
        return schema().status();
    }

    private List<Map<String, Object>> rowsForResource(long resourceId) {
        ReservationSchema s = schema();
        return jdbc.queryForList("select * from " + quote(s.table()) + " where " + quote(s.resource()) + " = ?"
                + " order by " + quote(s.id()), resourceId);
    }

    private long totalReservationCount() {
        Long count = jdbc.queryForObject("select count(*) from " + quote(schema().table()), Long.class);
        return count == null ? -1L : count;
    }

    private String singleSeriesValue(List<Map<String, Object>> rows) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get(seriesColumn());
            if (value != null) {
                values.add(String.valueOf(value));
            }
        }
        assertEquals(1, values.size(),
                "expected exactly one persisted series identifier for the resource, found " + values
                        + "; rows: " + rows);
        return values.iterator().next();
    }

    private Set<Instant> startInstants(List<Map<String, Object>> rows) {
        Set<Instant> instants = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Instant value = toInstant(row.get(schema().start()));
            if (value != null) {
                instants.add(value);
            }
        }
        return instants;
    }

    private Window windowOf(Map<String, Object> row) {
        Instant start = toInstant(row.get(schema().start()));
        Instant end = toInstant(row.get(schema().end()));
        assertNotNull(start, "persisted reservation row exposes no start value: " + row);
        assertNotNull(end, "persisted reservation row exposes no end value: " + row);
        return new Window(start, end);
    }

    private List<Window> windows(List<Map<String, Object>> rows) {
        List<Window> windows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            windows.add(windowOf(row));
        }
        return windows;
    }

    private String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof String text) {
            OffsetDateTime parsed = parseOffsetDateTime(text);
            if (parsed != null) {
                return parsed.toInstant();
            }
        }
        throw new IllegalStateException("unsupported persisted date-time value type "
                + value.getClass().getName() + ": " + value);
    }
}
