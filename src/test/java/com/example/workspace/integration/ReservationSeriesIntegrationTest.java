package com.example.workspace.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Black-box integration tests for the recurring-reservation side effects of
 * FEATURE-001 (DOC-001 requirements 4, 5, 6 and 7): atomic rollback on a conflicting
 * occurrence, the stable series identifier, cancelling a single occurrence, and
 * concurrent overlapping requests.
 *
 * <p>Expected behaviour comes only from the frozen business contract
 * ({@code contexts/WBATCH-002.md}); this class never inspects production source.
 * All interaction with the application goes through the in-process HTTP boundary
 * (MockMvc) and, for persistence facts, through the real datasource of the running
 * Spring context via {@link JdbcTemplate}. Table and column names are discovered at
 * runtime from {@code INFORMATION_SCHEMA}; no entity getters, service methods or
 * repository methods beyond the standard JPA surface are assumed.</p>
 *
 * <p>Documented assumptions carried by this class:</p>
 * <ul>
 *   <li>AMB-001 (DST gap/overlap policy) is avoided: every fixture window is chosen
 *       so that all occurrences share one UTC offset.</li>
 *   <li>AMB-002 (exact 409 body fields) is not asserted: the 409 payload is only
 *       required to reference the conflicting occurrence by its local date and/or by
 *       an occurrence-index value, never by a specific field name.</li>
 *   <li>AMB-003 (400 body/other validation statuses) is out of scope here.</li>
 *   <li>AMB-004 (concurrency loser status) is not asserted: only that both requests
 *       do not succeed and that no overlapping rows are persisted.</li>
 * </ul>
 *
 * <p>Binding note: there is no approved Java API contract, so the resource fixture
 * and the one-off booking fixture are bound black-box against the in-process API and
 * their creation is asserted to succeed, so fixture drift fails loudly instead of
 * silently changing the scenario.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:reservation-series-integration-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false"
        })
@AutoConfigureMockMvc
class ReservationSeriesIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZONE_ID = "Europe/Berlin";
    private static final ZoneId ZONE = ZoneId.of(ZONE_ID);
    private static final LocalTime SERIES_LOCAL_START = LocalTime.of(9, 0);
    private static final Duration SERIES_DURATION = Duration.ofMinutes(60);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    // ==================================================================
    // SCN-009 / REQ-004, SE-003, SE-006
    // Conflict at a middle occurrence -> the whole series is rolled back.
    // ==================================================================

    /** SCN-009#A1 - zero reservation rows from the failed series exist. */
    @Test
    void scn009a1ConflictingSeriesPersistsNoRowForTheFailedSeries() throws Exception {
        long resourceId = createResource(uniqueCode("S9A1"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);
        String seriesTitle = "SCN-009 A1 series " + UUID.randomUUID();

        // Pre-existing booking on the middle occurrence only.
        assertCreated(createOneOff(resourceId, "SCN-009 A1 pre-existing", occurrences.get(1)), "one-off fixture");
        List<Map<String, Object>> before = rowsForResource(resourceId);
        assertEquals(1, before.size(), "fixture precondition: exactly one pre-existing reservation is expected");

        MvcResult attempt = postSeries(resourceId, seriesTitle, occurrences.get(0), SERIES_DURATION, 3);
        assertEquals(409, attempt.getResponse().getStatus(),
                "a series whose middle occurrence conflicts must be rejected with HTTP 409; response body: "
                        + body(attempt));

        List<Map<String, Object>> after = rowsForResource(resourceId);
        assertEquals(before.size(), after.size(),
                "a rolled-back series must not add any row for the resource; rows after the 409: " + after);
        for (Map<String, Object> row : after) {
            assertNotEquals(seriesTitle, String.valueOf(row.get(titleColumn())),
                    "no persisted row may carry the title of the rejected series: " + row);
        }
        for (int index : new int[]{0, 2}) {
            assertFalse(startInstants(after).contains(occurrences.get(index)),
                    "no row may exist at the free occurrence " + index + " of the rejected series ("
                            + occurrences.get(index) + "); rows: " + after);
        }
    }

    /** SCN-009#A2 - the pre-existing conflicting reservation is left untouched. */
    @Test
    void scn009a2ConflictingSeriesLeavesPreExistingReservationUnchanged() throws Exception {
        long resourceId = createResource(uniqueCode("S9A2"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);

        assertCreated(createOneOff(resourceId, "SCN-009 A2 pre-existing", occurrences.get(1)), "one-off fixture");
        List<Map<String, Object>> before = rowsForResource(resourceId);
        assertEquals(1, before.size(), "fixture precondition: exactly one pre-existing reservation is expected");
        Map<String, Object> preExistingBefore = new LinkedHashMap<>(before.get(0));
        Object preExistingId = preExistingBefore.get(idColumn());

        MvcResult attempt = postSeries(resourceId, "SCN-009 A2 series " + UUID.randomUUID(),
                occurrences.get(0), SERIES_DURATION, 3);
        assertEquals(409, attempt.getResponse().getStatus(),
                "a series whose middle occurrence conflicts must be rejected with HTTP 409; response body: "
                        + body(attempt));

        List<Map<String, Object>> after = rowsForResource(resourceId);
        Map<String, Object> preExistingAfter = null;
        for (Map<String, Object> row : after) {
            if (String.valueOf(preExistingId).equals(String.valueOf(row.get(idColumn())))) {
                preExistingAfter = row;
            }
        }
        assertNotNull(preExistingAfter,
                "the pre-existing reservation " + preExistingId + " disappeared after the rejected series; rows: "
                        + after);
        assertEquals(preExistingBefore, preExistingAfter,
                "every persisted column of the pre-existing reservation must be unchanged after the rejected series");
    }

    /** SCN-009#A3 - the global reservation count changes by zero rows. */
    @Test
    void scn009a3ConflictingSeriesAddsZeroRowsToTheReservationTable() throws Exception {
        long resourceId = createResource(uniqueCode("S9A3"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);

        assertCreated(createOneOff(resourceId, "SCN-009 A3 pre-existing", occurrences.get(1)), "one-off fixture");
        long countBefore = totalReservationCount();

        MvcResult attempt = postSeries(resourceId, "SCN-009 A3 series " + UUID.randomUUID(),
                occurrences.get(0), SERIES_DURATION, 3);
        assertEquals(409, attempt.getResponse().getStatus(),
                "a series whose middle occurrence conflicts must be rejected with HTTP 409; response body: "
                        + body(attempt));

        long countAfter = totalReservationCount();
        assertEquals(countBefore, countAfter,
                "a rejected series must persist exactly zero new reservation rows (before=" + countBefore
                        + ", after=" + countAfter + ")");
    }

    /** SCN-009#A4 - the 409 payload identifies the conflicting occurrence. */
    @Test
    void scn009a4Conflict409IdentifiesTheConflictingOccurrence() throws Exception {
        long resourceId = createResource(uniqueCode("S9A4"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);

        assertCreated(createOneOff(resourceId, "SCN-009 A4 pre-existing", occurrences.get(1)), "one-off fixture");

        MvcResult attempt = postSeries(resourceId, "SCN-009 A4 series " + UUID.randomUUID(),
                occurrences.get(0), SERIES_DURATION, 3);
        assertEquals(409, attempt.getResponse().getStatus(),
                "a series whose middle occurrence conflicts must be rejected with HTTP 409; response body: "
                        + body(attempt));

        String responseBody = body(attempt);
        Instant conflict = occurrences.get(1);
        LocalDate conflictDate = conflict.atZone(ZONE).toLocalDate();
        boolean dateReferenced = responseBody.contains(conflictDate.toString())
                || responseBody.contains(conflict.toString())
                || responseBody.contains(conflict.atZone(ZONE).toOffsetDateTime().toString())
                || responseBody.contains(conflict.atZone(ZONE).toOffsetDateTime()
                        .withNano(0).toLocalDateTime().toString());
        boolean indexReferenced = OCCURRENCE_INDEX_REFERENCE.matcher(responseBody).find();
        assertTrue(dateReferenced || indexReferenced,
                "the 409 payload must identify the conflicting occurrence " + conflict + " (local date "
                        + conflictDate + ") or its occurrence index; body: " + responseBody);
    }

    /** Pattern for an occurrence-index value of 2 in any field name mentioning an index/occurrence. */
    private static final Pattern OCCURRENCE_INDEX_REFERENCE =
            Pattern.compile("\"[^\"]*(?i:index|occurrence)[^\"]*\"\\s*:\\s*2\\b");

    // ==================================================================
    // SCN-010 / REQ-005, SE-001 - one stable series identifier
    // ==================================================================

    /** SCN-010#A1 - every persisted occurrence shares a single series identifier. */
    @Test
    void scn010a1PersistedOccurrencesShareOneSeriesIdentifier() throws Exception {
        long resourceId = createResource(uniqueCode("S10A1"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);

        MvcResult created = postSeries(resourceId, "SCN-010 A1 series " + UUID.randomUUID(),
                occurrences.get(0), SERIES_DURATION, 3);
        assertCreated(created, "creating a conflict-free weekly series");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(occurrences.size(), rows.size(),
                "one persisted row per requested occurrence is expected; rows: " + rows);
        assertEquals(new LinkedHashSet<>(occurrences), startInstants(rows),
                "the persisted rows must cover exactly the requested occurrence start instants; rows: " + rows);

        Set<String> seriesValues = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object value = row.get(seriesColumn());
            assertNotNull(value,
                    "every occurrence of a series must persist a non-null series identifier; row: " + row);
            seriesValues.add(String.valueOf(value));
        }
        assertEquals(1, seriesValues.size(),
                "all persisted occurrences of one series must share exactly one series identifier, found "
                        + seriesValues + "; rows: " + rows);
    }

    /** SCN-010#A2 - each reservation in the response exposes that same seriesId. */
    @Test
    void scn010a2EachReservationResponseExposesTheSameSeriesId() throws Exception {
        long resourceId = createResource(uniqueCode("S10A2"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);

        MvcResult created = postSeries(resourceId, "SCN-010 A2 series " + UUID.randomUUID(),
                occurrences.get(0), SERIES_DURATION, 3);
        assertCreated(created, "creating a conflict-free weekly series");

        List<Map<String, Object>> rows = rowsForResource(resourceId);
        assertEquals(occurrences.size(), rows.size(),
                "one persisted row per requested occurrence is expected; rows: " + rows);
        String persistedSeriesId = singleSeriesValue(rows);

        List<JsonNode> reservations = reservationNodes(created);
        assertEquals(occurrences.size(), reservations.size(),
                "the series response must expose one reservation per occurrence; body: " + body(created));
        for (JsonNode reservation : reservations) {
            JsonNode seriesId = seriesIdNode(reservation);
            assertNotNull(seriesId,
                    "each reservation response must expose the documented seriesId field; reservation: "
                            + reservation);
            assertFalse(seriesId.isNull(), "seriesId must not be null for a series occurrence: " + reservation);
            assertEquals(persistedSeriesId, seriesId.asText(),
                    "the seriesId exposed by the reservation response must equal the persisted series identifier;"
                            + " reservation: " + reservation);
        }
    }

    /** SCN-010#A3 - the seriesId is unchanged on subsequent reads. */
    @Test
    void scn010a3SeriesIdIsUnchangedOnSubsequentReads() throws Exception {
        long resourceId = createResource(uniqueCode("S10A3"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);
        String title = "SCN-010 A3 series " + UUID.randomUUID();

        MvcResult created = postSeries(resourceId, title, occurrences.get(0), SERIES_DURATION, 3);
        assertCreated(created, "creating a conflict-free weekly series");

        Map<Instant, String> firstRead = seriesIdsByStart(reservationNodes(created));
        assertEquals(occurrences.size(), firstRead.size(),
                "the creation response must expose seriesId for every occurrence; body: " + body(created));
        String persistedSeriesId = singleSeriesValue(rowsForResource(resourceId));

        MvcResult reRead = getJson("/api/reservations");
        assertSuccess(reRead, "re-reading the reservations");
        Map<Instant, String> secondRead = seriesIdsForResource(
                MAPPER.readTree(reRead.getResponse().getContentAsByteArray()), resourceId);
        assertEquals(occurrences.size(), secondRead.size(),
                "the reservations listing must still expose seriesId for every occurrence of the series; body: "
                        + body(reRead));
        for (Instant start : occurrences) {
            assertEquals(persistedSeriesId, firstRead.get(start),
                    "the creation read of occurrence " + start + " must expose the persisted seriesId");
            assertEquals(persistedSeriesId, secondRead.get(start),
                    "the seriesId of occurrence " + start + " must be unchanged on a subsequent read");
        }
    }

    // ==================================================================
    // SCN-013 / REQ-006 - cancel a single occurrence of a series
    // ==================================================================

    /** SCN-013#A1 - the targeted occurrence is cancelled. */
    @Test
    void scn013a1CancelOneOccurrenceCancelsThatOccurrence() throws Exception {
        long resourceId = createResource(uniqueCode("S13A1"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);

        assertCreated(postSeries(resourceId, "SCN-013 A1 series " + UUID.randomUUID(),
                occurrences.get(0), SERIES_DURATION, 3), "creating a conflict-free weekly series");
        assertEquals(3, rowsForResource(resourceId).size(),
                "fixture precondition: the series must have persisted three occurrences");

        long targetId = reservationIdAt(resourceId, occurrences.get(1));
        MvcResult cancelled = cancelReservation(targetId);
        assertSuccess(cancelled, "cancelling occurrence " + targetId + " through the existing endpoint");

        Map<String, Object> target = rowById(resourceId, targetId);
        assertNotNull(target, "the targeted occurrence " + targetId + " must still exist after cancellation");
        assertEquals("CANCELLED", String.valueOf(target.get(statusColumn())).toUpperCase(Locale.ROOT),
                "the targeted occurrence must be cancelled in persistence; row: " + target);
    }

    /** SCN-013#A2 - the other occurrences of the series remain unchanged. */
    @Test
    void scn013a2OtherOccurrencesOfTheSeriesRemainUnchanged() throws Exception {
        long resourceId = createResource(uniqueCode("S13A2"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);

        assertCreated(postSeries(resourceId, "SCN-013 A2 series " + UUID.randomUUID(),
                occurrences.get(0), SERIES_DURATION, 3), "creating a conflict-free weekly series");
        Map<String, Map<String, Object>> before = rowsById(resourceId);
        assertEquals(3, before.size(), "fixture precondition: three occurrences are expected; rows: " + before);

        long targetId = reservationIdAt(resourceId, occurrences.get(1));
        assertSuccess(cancelReservation(targetId), "cancelling occurrence " + targetId);

        Map<String, Map<String, Object>> after = rowsById(resourceId);
        assertEquals(before.keySet(), after.keySet(),
                "cancelling one occurrence must not remove any occurrence of the series");
        assertEquals(before.size(), after.size(), "the series size must not change when one occurrence is cancelled");
        for (Map.Entry<String, Map<String, Object>> entry : before.entrySet()) {
            if (String.valueOf(entry.getKey()).equals(String.valueOf(targetId))) {
                continue;
            }
            Map<String, Object> now = after.get(entry.getKey());
            assertNotNull(now, "sibling occurrence " + entry.getKey() + " disappeared");
            assertEquals(entry.getValue(), now,
                    "sibling occurrence " + entry.getKey() + " must be completely unchanged after cancelling "
                            + targetId);
            assertNotEquals("CANCELLED", String.valueOf(now.get(statusColumn())).toUpperCase(Locale.ROOT),
                    "sibling occurrence " + entry.getKey() + " must not be cancelled; row: " + now);
        }
    }

    /** SCN-013#A3 - cancellation goes through the existing mapped endpoint. */
    @Test
    void scn013a3CancellationUsesTheExistingMappedEndpoint() throws Exception {
        long resourceId = createResource(uniqueCode("S13A3"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 3);
        List<Instant> occurrences = occurrenceInstants(first, 3);

        assertCreated(postSeries(resourceId, "SCN-013 A3 series " + UUID.randomUUID(),
                occurrences.get(0), SERIES_DURATION, 3), "creating a conflict-free weekly series");

        CancelRoute route = discoverCancellationRoute();
        assertNotNull(route,
                "the existing cancellation endpoint required by REQ-006 is not registered in the application "
                        + "handler mappings; mapped reservation routes: " + reservationRoutes());

        long targetId = reservationIdAt(resourceId, occurrences.get(0));
        MvcResult result = performCancellation(route, targetId);
        assertSuccess(result, "cancelling occurrence " + targetId + " through the existing mapped endpoint "
                + route);
        assertEquals("CANCELLED", String.valueOf(rowById(resourceId, targetId).get(statusColumn()))
                        .toUpperCase(Locale.ROOT),
                "cancelling through the pre-existing endpoint " + route + " must cancel the occurrence");
    }

    // ==================================================================
    // SCN-014 / REQ-007, SE-004 - concurrent overlapping requests
    // ==================================================================

    /** SCN-014#A1 - the two concurrent requests do not both succeed. */
    @Test
    void scn014a1ConcurrentOverlappingRequestsDoNotBothSucceed() throws Exception {
        long resourceId = createResource(uniqueCode("S14A1"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 2);
        List<Instant> occurrences = occurrenceInstants(first, 2);

        ConcurrentOutcome outcome = postConcurrently(
                oneOffJson(resourceId, "SCN-014 A1 one-off " + UUID.randomUUID(), occurrences.get(0),
                        SERIES_DURATION),
                seriesJson(resourceId, "SCN-014 A1 series " + UUID.randomUUID(), occurrences.get(0),
                        SERIES_DURATION, 2));
        assertTrue(outcome.oneOffError() == null,
                "the concurrent one-off request failed before producing an HTTP response: " + outcome.oneOffError());
        assertTrue(outcome.seriesError() == null,
                "the concurrent recurring request failed before producing an HTTP response: "
                        + outcome.seriesError());

        assertFalse(outcome.oneOffSuccess() && outcome.seriesSuccess(),
                "two concurrent overlapping requests for the same room must not both succeed; outcomes: " + outcome);
    }

    /** SCN-014#A2 - persisted reservations for the room are never overlapping. */
    @Test
    void scn014a2PersistedReservationsForTheRoomAreNotOverlapping() throws Exception {
        long resourceId = createResource(uniqueCode("S14A2"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 2);
        List<Instant> occurrences = occurrenceInstants(first, 2);

        ConcurrentOutcome outcome = postConcurrently(
                oneOffJson(resourceId, "SCN-014 A2 one-off " + UUID.randomUUID(), occurrences.get(0),
                        SERIES_DURATION),
                seriesJson(resourceId, "SCN-014 A2 series " + UUID.randomUUID(), occurrences.get(0),
                        SERIES_DURATION, 2));
        assertTrue(outcome.oneOffError() == null,
                "the concurrent one-off request failed before producing an HTTP response: " + outcome.oneOffError());
        assertTrue(outcome.seriesError() == null,
                "the concurrent recurring request failed before producing an HTTP response: "
                        + outcome.seriesError());

        List<Window> windows = windows(rowsForResource(resourceId));
        windows.sort((left, right) -> left.start().compareTo(right.start()));
        for (int index = 1; index < windows.size(); index++) {
            Window previous = windows.get(index - 1);
            Window current = windows.get(index);
            assertFalse(previous.end().isAfter(current.start()),
                    "persisted reservations for room " + resourceId + " overlap: " + previous + " and " + current
                            + "; outcomes: " + outcome);
        }
    }

    /** SCN-014#A3 - only the successful request's occurrences are persisted. */
    @Test
    void scn014a3OnlyTheSuccessfulRequestsOccurrencesArePersisted() throws Exception {
        long resourceId = createResource(uniqueCode("S14A3"), true);
        LocalDateTime first = stableWeeklyStart(SERIES_LOCAL_START, 2);
        List<Instant> occurrences = occurrenceInstants(first, 2);

        ConcurrentOutcome outcome = postConcurrently(
                oneOffJson(resourceId, "SCN-014 A3 one-off " + UUID.randomUUID(), occurrences.get(0),
                        SERIES_DURATION),
                seriesJson(resourceId, "SCN-014 A3 series " + UUID.randomUUID(), occurrences.get(0),
                        SERIES_DURATION, 2));
        assertTrue(outcome.oneOffError() == null,
                "the concurrent one-off request failed before producing an HTTP response: " + outcome.oneOffError());
        assertTrue(outcome.seriesError() == null,
                "the concurrent recurring request failed before producing an HTTP response: "
                        + outcome.seriesError());

        Set<Window> expected = new LinkedHashSet<>();
        if (outcome.oneOffSuccess()) {
            expected.add(new Window(occurrences.get(0),
                    occurrences.get(0).plus(SERIES_DURATION)));
        }
        if (outcome.seriesSuccess()) {
            for (Instant occurrence : occurrences) {
                expected.add(new Window(occurrence, occurrence.plus(SERIES_DURATION)));
            }
        }

        List<Window> persisted = windows(rowsForResource(resourceId));
        assertEquals(expected, new LinkedHashSet<>(persisted),
                "only the occurrences of the request(s) that returned success may be persisted; outcomes: " + outcome);
        assertEquals(expected.size(), persisted.size(),
                "no duplicate or extra reservation row may be persisted; outcomes: " + outcome
                        + ", persisted: " + persisted);
    }

    // ==================================================================
    // Fixture helpers
    // ==================================================================

    private record ConcurrentOutcome(int oneOffStatus, String oneOffBody, Throwable oneOffError,
                                     int seriesStatus, String seriesBody, Throwable seriesError) {

        boolean oneOffSuccess() {
            return oneOffStatus >= 200 && oneOffStatus < 300;
        }

        boolean seriesSuccess() {
            return seriesStatus >= 200 && seriesStatus < 300;
        }

        @Override
        public String toString() {
            return "oneOff=[status=" + oneOffStatus + ", body=" + oneOffBody + ", error=" + oneOffError
                    + "], series=[status=" + seriesStatus + ", body=" + seriesBody + ", error=" + seriesError + "]";
        }
    }

    private record Submission(MvcResult result, Throwable error) {
    }

    private static LocalDateTime stableWeeklyStart(LocalTime localStart, int occurrences) {
        LocalDate date = LocalDate.now(ZONE)
                .plusDays(45 + java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 500));
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

    private long createResource(String code, boolean active) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("code", code);
        payload.put("name", "Reservation series integration fixture " + code);
        payload.put("type", "ROOM");
        payload.put("building", "HQ");
        payload.put("floorNumber", 1);
        payload.put("capacity", 10);
        payload.put("active", active);
        payload.put("approvalRequired", false);

        MvcResult created = postJson("/api/resources", MAPPER.writeValueAsString(payload));
        assertSuccess(created,
                "fixture resource creation via POST /api/resources (code " + code + ")");
        Long id = idForCode(MAPPER.readTree(created.getResponse().getContentAsByteArray()), code);
        if (id == null) {
            MvcResult listed = getJson("/api/resources");
            assertSuccess(listed, "listing resources to resolve the fixture resource id");
            id = idForCode(MAPPER.readTree(listed.getResponse().getContentAsByteArray()), code);
        }
        assertNotNull(id, "could not resolve the id of fixture resource " + code + " from the API");
        return id;
    }

    private MvcResult createOneOff(long resourceId, String title, Instant start) throws Exception {
        return postJson("/api/reservations", oneOffJson(resourceId, title, start, SERIES_DURATION));
    }

    private String oneOffJson(long resourceId, String title, Instant start, Duration duration) throws Exception {
        // The one-off booking request is bound black-box: an ISO-8601 instant ("...Z") is the
        // representation both defined for the request window, and creation is asserted by the caller
        // so any drift in this binding fails loudly.
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("resourceId", resourceId);
        payload.put("title", title);
        payload.put("startsAt", start.toString());
        payload.put("endsAt", start.plus(duration).toString());
        return MAPPER.writeValueAsString(payload);
    }

    private MvcResult postSeries(long resourceId, String title, Instant firstOccurrence,
                                 Duration duration, int occurrences) throws Exception {
        return postJson("/api/reservation-series",
                seriesJson(resourceId, title, firstOccurrence, duration, occurrences));
    }

    private String seriesJson(long resourceId, String title, Instant firstOccurrence,
                              Duration duration, int occurrences) throws Exception {
        OffsetDateTime startsAt = firstOccurrence.atZone(ZONE).toOffsetDateTime();
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
                        .with(user("integration-series-writer").roles("EMPLOYEE", "MANAGER", "ADMIN", "USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult getJson(String url) throws Exception {
        return mockMvc.perform(get(url)
                        .with(user("integration-series-writer").roles("EMPLOYEE", "MANAGER", "ADMIN", "USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private static void assertSuccess(MvcResult result, String action) {
        int status = result.getResponse().getStatus();
        assertTrue(status >= 200 && status < 300,
                action + " failed with HTTP " + status + "; response body: " + body(result));
    }

    private static void assertCreated(MvcResult result, String action) {
        assertSuccess(result, action);
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
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
    // Cancellation endpoint binding (existing endpoint, discovered at runtime)
    // ==================================================================

    private record CancelRoute(HttpMethod method, String pattern) {

        String urlFor(long reservationId) {
            return pattern.replaceAll("\\{[^}]+}", String.valueOf(reservationId));
        }
    }

    private MvcResult cancelReservation(long reservationId) throws Exception {
        CancelRoute route = discoverCancellationRoute();
        assertNotNull(route,
                "no existing cancellation endpoint is registered for reservations; mapped reservation routes: "
                        + reservationRoutes());
        return performCancellation(route, reservationId);
    }

    private MvcResult performCancellation(CancelRoute route, long reservationId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.request(route.method(), route.urlFor(reservationId))
                        .with(user("integration-series-writer").roles("EMPLOYEE", "MANAGER", "ADMIN", "USER"))
                        .with(csrf())
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private CancelRoute discoverCancellationRoute() {
        List<CancelRoute> candidates = cancellationCandidates();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private List<CancelRoute> cancellationCandidates() {
        List<CancelRoute> named = new ArrayList<>();
        List<CancelRoute> inferred = new ArrayList<>();
        for (RequestMappingInfo info : requestMappingHandlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                String lower = pattern.toLowerCase(Locale.ROOT);
                if (!lower.contains("reservation") || !pattern.contains("{")) {
                    continue;
                }
                boolean cancelNamed = lower.contains("cancel");
                for (RequestMethod method : methods) {
                    if (cancelNamed) {
                        named.add(new CancelRoute(HttpMethod.valueOf(method.name()), pattern));
                    } else if (method == RequestMethod.DELETE) {
                        inferred.add(new CancelRoute(HttpMethod.valueOf(method.name()), pattern));
                    }
                }
            }
        }
        named.addAll(inferred);
        return named;
    }

    private String reservationRoutes() {
        Set<String> routes = new LinkedHashSet<>();
        for (RequestMappingInfo info : requestMappingHandlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (pattern.toLowerCase(Locale.ROOT).contains("reservation")) {
                    routes.add(info.getMethodsCondition().getMethods() + " " + pattern);
                }
            }
        }
        return routes.toString();
    }

    // ==================================================================
    // JSON response observation helpers
    // ==================================================================

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

    private static Map<Instant, String> seriesIdsForResource(JsonNode root, long resourceId) {
        Map<Instant, String> byStart = new HashMap<>();
        if (!root.isArray()) {
            return byStart;
        }
        for (JsonNode entry : root) {
            if (entry.path("resourceId").asLong(-1L) != resourceId) {
                continue;
            }
            Instant start = startInstantOf(entry);
            JsonNode seriesId = seriesIdNode(entry);
            if (start != null && seriesId != null && !seriesId.isNull()) {
                byStart.put(start, seriesId.asText());
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

    private record Window(Instant start, Instant end) {
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
            throw new IllegalStateException(
                    "expected exactly one reservation table in the test datasource, found " + candidates
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

        if (id == null || resource == null || start == null || end == null || series == null
                || status == null || title == null) {
            throw new IllegalStateException(
                    "reservation table " + table + " does not expose the columns required by the business "
                            + "contract (id/resource/start/end/series/status/title); columns: " + columns);
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

    private String idColumn() {
        return schema().id();
    }

    private String seriesColumn() {
        return schema().series();
    }

    private String statusColumn() {
        return schema().status();
    }

    private String titleColumn() {
        return schema().title();
    }

    private List<Map<String, Object>> rowsForResource(long resourceId) {
        ReservationSchema s = schema();
        return jdbc.queryForList("select * from " + quote(s.table()) + " where " + quote(s.resource()) + " = ?"
                + " order by " + quote(s.id()), resourceId);
    }

    private Map<String, Map<String, Object>> rowsById(long resourceId) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rowsForResource(resourceId)) {
            byId.put(String.valueOf(row.get(idColumn())), row);
        }
        return byId;
    }

    private Map<String, Object> rowById(long resourceId, long reservationId) {
        for (Map<String, Object> row : rowsForResource(resourceId)) {
            if (String.valueOf(reservationId).equals(String.valueOf(row.get(idColumn())))) {
                return row;
            }
        }
        return null;
    }

    private long reservationIdAt(long resourceId, Instant start) {
        for (Map<String, Object> row : rowsForResource(resourceId)) {
            Instant rowStart = toInstant(row.get(schema().start()));
            if (start.equals(rowStart)) {
                return Long.parseLong(String.valueOf(row.get(idColumn())));
            }
        }
        throw new IllegalStateException("no persisted reservation for resource " + resourceId + " starts at "
                + start + "; rows: " + rowsForResource(resourceId));
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

    private List<Window> windows(List<Map<String, Object>> rows) {
        List<Window> windows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Instant start = toInstant(row.get(schema().start()));
            Instant end = toInstant(row.get(schema().end()));
            if (start != null && end != null) {
                windows.add(new Window(start, end));
            }
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
