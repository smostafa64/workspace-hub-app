package com.example.workspace.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Black-box, in-process tests for the documented recurring-reservation contract
 * (DOC-001 / FEATURE-001): {@code POST /api/reservation-series} with
 * {@code resourceId}, {@code title}, {@code startsAt}, {@code endsAt},
 * {@code timezone}, {@code frequency = WEEKLY} and {@code occurrences}.
 *
 * <p>Expected behaviour is derived only from the business document
 * (DOC-001 requirements 1-3, 9 and the acceptance criteria). Only observables that
 * the document fixes are asserted: the local wall-clock start time of every
 * occurrence, the UTC instants implied by the supplied IANA zone and the
 * documented HTTP rejection codes. No error-body field names, no DST gap/overlap
 * policy (AMB-001) and no internal Java API are asserted.</p>
 *
 * <p>Binding notes (see writer report): there is no approved internal Java API
 * contract, so everything goes through the HTTP boundary with MockMvc. The fixture
 * step that creates the resource under test uses {@code POST /api/resources}; its
 * request/response field names were verified at runtime against the running API
 * (no approved interface document for that endpoint is configured in this task).
 * The series creation response observed in process is
 * {@code {seriesId, resourceId, title, timezone, frequency, occurrences, reservations[]}}
 * where every reservation carries {@code startsAt}/{@code endsAt}/{@code seriesId};
 * occurrences are therefore identified by their local wall-clock start time rather
 * than by a documented field name.</p>
 *
 * <p>Each test creates its own future data on a freshly created resource with a
 * unique code, so the tests are order independent. The documented API exposes no
 * delete endpoint, therefore no explicit cleanup is performed; the in-process
 * Spring context uses a dedicated in-memory schema for the run.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:reservation-series-recurrence-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.flyway.enabled=false"
        })
@AutoConfigureMockMvc
class ReservationSeriesRecurrenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZONE_ID = "Europe/Berlin";
    private static final LocalTime SERIES_LOCAL_START = LocalTime.of(9, 0);

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------------
    // SCN-002 / REQ-002 - weekly expansion across a Europe/Berlin DST change
    // ------------------------------------------------------------------

    /** SCN-002#A1 */
    @Test
    void dstBoundaryPreservesLocalStartTimeForEveryOccurrence() throws Exception {
        DstSeries series = dstSeries(SERIES_LOCAL_START);
        long resourceId = createResource(uniqueResourceCode("S2A1"), true);

        MvcResult created = postSeries(resourceId, "SCN-002 A1 weekly series", series.firstStart(),
                Duration.ofMinutes(30), ZONE_ID, series.expectedDates().size());
        assertSuccess(created, "creating a weekly series across the DST boundary");

        List<Instant> starts = occurrenceStartsAtLocalTime(created, series.zone(), series.localStart());
        assertFalse(starts.isEmpty(),
                "the series response exposed no occurrence start instants at local " + series.localStart()
                        + " in " + series.zone() + "; response body: " + body(created));

        for (Instant start : starts) {
            assertEquals(series.localStart(), start.atZone(series.zone()).toLocalTime(),
                    "occurrence " + start + " does not keep the series local wall-clock start time in "
                            + series.zone());
        }

        Set<LocalDate> actualDates = new LinkedHashSet<>();
        for (Instant start : starts) {
            actualDates.add(start.atZone(series.zone()).toLocalDate());
        }
        assertEquals(new HashSet<>(series.expectedDates()), actualDates,
                "every weekly occurrence date, including the one after the DST transition, must exist at the "
                        + "same local clock time");
    }

    /** SCN-002#A2 */
    @Test
    void dstBoundaryUtcInstantsFollowZoneOffsetInsteadOfFixedCadence() throws Exception {
        DstSeries series = dstSeries(SERIES_LOCAL_START);
        long resourceId = createResource(uniqueResourceCode("S2A2"), true);

        MvcResult created = postSeries(resourceId, "SCN-002 A2 weekly series", series.firstStart(),
                Duration.ofMinutes(30), ZONE_ID, series.expectedDates().size());
        assertSuccess(created, "creating a weekly series across the DST boundary");

        List<Instant> expected = series.expectedInstants();
        List<Instant> actual = new ArrayList<>(
                new LinkedHashSet<>(occurrenceStartsAtLocalTime(created, series.zone(), series.localStart())));
        actual.sort(Comparator.naturalOrder());

        assertEquals(expected, actual,
                "UTC instants must equal the instants obtained by resolving the same local start time in "
                        + series.zone() + " on each occurrence date; response body: " + body(created));

        assertEquals(gaps(expected), gaps(actual),
                "the cadence between consecutive UTC instants must follow the zone offset change");
        assertTrue(gaps(actual).stream().anyMatch(gap -> !gap.equals(Duration.ofDays(7))),
                "UTC instants must not follow a fixed 7-day cadence across the DST boundary; actual gaps: "
                        + gaps(actual));

        Set<ZoneOffset> expectedOffsets = offsets(expected, series.zone());
        Set<ZoneOffset> actualOffsets = offsets(actual, series.zone());
        assertEquals(expectedOffsets, actualOffsets,
                "the UTC offset carried by each occurrence instant must match the zone offset on that date");
        assertEquals(2, actualOffsets.size(),
                "the series spans exactly one DST offset change, so two distinct offsets are expected");
    }

    /** SCN-002#A3 */
    @Test
    void dstBoundaryDoesNotSkipOrDuplicateOccurrences() throws Exception {
        DstSeries series = dstSeries(SERIES_LOCAL_START);
        long resourceId = createResource(uniqueResourceCode("S2A3"), true);

        MvcResult created = postSeries(resourceId, "SCN-002 A3 weekly series", series.firstStart(),
                Duration.ofMinutes(30), ZONE_ID, series.expectedDates().size());
        assertSuccess(created, "creating a weekly series across the DST boundary");

        List<Instant> starts = occurrenceStartsAtLocalTime(created, series.zone(), series.localStart());
        assertFalse(starts.isEmpty(),
                "the series response exposed no occurrence start instants; response body: " + body(created));

        Set<LocalDate> actualDates = new LinkedHashSet<>();
        for (Instant start : starts) {
            actualDates.add(start.atZone(series.zone()).toLocalDate());
        }
        assertEquals(new HashSet<>(series.expectedDates()), actualDates,
                "exactly one occurrence per consecutive weekly date is expected - a missing date means a skipped "
                        + "occurrence and an extra date means a spurious one; response body: " + body(created));

        Set<Instant> distinctStarts = new HashSet<>(starts);
        assertEquals(series.expectedDates().size(), distinctStarts.size(),
                "each occurrence date must resolve to exactly one distinct instant; response body: " + body(created));
        assertEquals(series.expectedDates().size(), reservationCountForResource(resourceId),
                "exactly one booking per occurrence must be persisted - a higher count means an occurrence was "
                        + "created twice");
        for (LocalDate date : series.expectedDates()) {
            Instant expected = LocalDateTime.of(date, series.localStart()).atZone(series.zone()).toInstant();
            assertTrue(distinctStarts.contains(expected),
                    "the occurrence for local date " + date + " at " + series.localStart() + " " + series.zone()
                            + " is missing (expected instant " + expected + ")");
        }
    }

    // ------------------------------------------------------------------
    // SCN-017 / REQ-001, REQ-002, REQ-003, REQ-009 - recurrence and validation
    // ------------------------------------------------------------------

    /** SCN-017#A1 */
    @Test
    void weeklyExpansionYieldsOccurrencesAtSameLocalClockTime() throws Exception {
        ZoneId zone = ZoneId.of(ZONE_ID);
        LocalTime localStart = LocalTime.of(10, 15);
        int occurrences = 4;
        LocalDateTime firstStart = firstStableWeeklyStart(zone, localStart, occurrences);
        long resourceId = createResource(uniqueResourceCode("S17A1"), true);

        MvcResult created = postSeries(resourceId, "SCN-017 A1 weekly series", firstStart,
                Duration.ofMinutes(60), ZONE_ID, occurrences);
        assertSuccess(created, "creating a weekly series inside a DST-stable window");

        List<Instant> starts = occurrenceStartsAtLocalTime(created, zone, localStart);
        assertEquals(occurrences, new HashSet<>(starts).size(),
                "one occurrence per requested week must be created at the same local clock time; response body: "
                        + body(created));
        for (Instant start : starts) {
            assertEquals(localStart, start.atZone(zone).toLocalTime(),
                    "occurrence " + start + " does not start at the requested local clock time " + localStart);
        }

        Set<LocalDate> expectedDates = new LinkedHashSet<>();
        for (int week = 0; week < occurrences; week++) {
            expectedDates.add(firstStart.toLocalDate().plusWeeks(week));
        }
        Set<LocalDate> actualDates = new LinkedHashSet<>();
        for (Instant start : starts) {
            actualDates.add(start.atZone(zone).toLocalDate());
        }
        assertEquals(expectedDates, actualDates, "occurrences must be one calendar week apart");
    }

    /** SCN-017#A2 */
    @Test
    void occurrenceCountBoundariesAcceptTwoAndTwelveAndRejectOneAndThirteen() throws Exception {
        ZoneId zone = ZoneId.of(ZONE_ID);
        LocalDateTime start = firstStableWeeklyStart(zone, LocalTime.of(10, 0), 12);
        Duration oneHour = Duration.ofMinutes(60);

        long twoOccurrenceResource = createResource(uniqueResourceCode("S17A2N2"), true);
        MvcResult twoOccurrences = postSeries(twoOccurrenceResource,
                "SCN-017 A2 two occurrences", start, oneHour, ZONE_ID, 2);
        assertSuccess(twoOccurrences, "occurrences = 2 must be accepted");
        assertEquals(2, reservationCountForResource(twoOccurrenceResource),
                "occurrences = 2 must create exactly two bookings");

        long twelveOccurrenceResource = createResource(uniqueResourceCode("S17A2N12"), true);
        MvcResult twelveOccurrences = postSeries(twelveOccurrenceResource,
                "SCN-017 A2 twelve occurrences", start, oneHour, ZONE_ID, 12);
        assertSuccess(twelveOccurrences, "occurrences = 12 must be accepted");
        assertEquals(12, reservationCountForResource(twelveOccurrenceResource),
                "occurrences = 12 must create exactly twelve bookings");

        long oneOccurrenceResource = createResource(uniqueResourceCode("S17A2N1"), true);
        MvcResult oneOccurrence = postSeries(oneOccurrenceResource,
                "SCN-017 A2 one occurrence", start, oneHour, ZONE_ID, 1);
        assertEquals(400, oneOccurrence.getResponse().getStatus(),
                "occurrences = 1 must be rejected with HTTP 400; response body: " + body(oneOccurrence));
        assertEquals(0, reservationCountForResource(oneOccurrenceResource),
                "a rejected occurrences = 1 request must not create any booking");

        long thirteenOccurrenceResource = createResource(uniqueResourceCode("S17A2N13"), true);
        MvcResult thirteenOccurrences = postSeries(thirteenOccurrenceResource,
                "SCN-017 A2 thirteen occurrences", start, oneHour, ZONE_ID, 13);
        assertEquals(400, thirteenOccurrences.getResponse().getStatus(),
                "occurrences = 13 must be rejected with HTTP 400; response body: " + body(thirteenOccurrences));
        assertEquals(0, reservationCountForResource(thirteenOccurrenceResource),
                "a rejected occurrences = 13 request must not create any booking");
    }

    /** SCN-017#A3 */
    @Test
    void durationsOfFifteenMinutesAndEightHoursAreAccepted() throws Exception {
        ZoneId zone = ZoneId.of(ZONE_ID);

        LocalDateTime shortStart = firstStableWeeklyStart(zone, LocalTime.of(12, 0), 2);
        MvcResult fifteenMinutes = postSeries(createResource(uniqueResourceCode("S17A3M15"), true),
                "SCN-017 A3 fifteen minute occurrences", shortStart, Duration.ofMinutes(15), ZONE_ID, 2);
        assertSuccess(fifteenMinutes, "a 15 minute duration must be accepted");

        LocalDateTime longStart = firstStableWeeklyStart(zone, LocalTime.of(8, 0), 2);
        MvcResult eightHours = postSeries(createResource(uniqueResourceCode("S17A3H8"), true),
                "SCN-017 A3 eight hour occurrences", longStart, Duration.ofHours(8), ZONE_ID, 2);
        assertSuccess(eightHours, "an 8 hour duration must be accepted");
    }

    /** SCN-017#A4 */
    @Test
    void pastOccurrencesAndInactiveResourcesAreRejected() throws Exception {
        ZoneId zone = ZoneId.of(ZONE_ID);

        LocalDateTime pastStart = LocalDate.now(zone).minusDays(14).atTime(LocalTime.of(10, 0));
        MvcResult pastOccurrences = postSeries(createResource(uniqueResourceCode("S17A4P"), true),
                "SCN-017 A4 past occurrences", pastStart, Duration.ofMinutes(60), ZONE_ID, 2);
        assertTrue(isClientError(pastOccurrences.getResponse().getStatus()),
                "a series containing past occurrences must be rejected with a client error, got HTTP "
                        + pastOccurrences.getResponse().getStatus() + "; response body: " + body(pastOccurrences));

        LocalDateTime futureStart = firstStableWeeklyStart(zone, LocalTime.of(11, 0), 2);
        long inactiveResourceId = createResource(uniqueResourceCode("S17A4I"), false);
        MvcResult inactiveResource = postSeries(inactiveResourceId, "SCN-017 A4 inactive resource",
                futureStart, Duration.ofMinutes(60), ZONE_ID, 2);
        assertTrue(isClientError(inactiveResource.getResponse().getStatus()),
                "a series targeting an inactive resource must be rejected with a client error, got HTTP "
                        + inactiveResource.getResponse().getStatus() + "; response body: "
                        + body(inactiveResource));
    }

    // ------------------------------------------------------------------
    // DST fixture helpers
    // ------------------------------------------------------------------

    private record DstSeries(ZoneId zone, LocalTime localStart, List<LocalDate> expectedDates) {

        LocalDateTime firstStart() {
            return LocalDateTime.of(expectedDates.get(0), localStart);
        }

        List<Instant> expectedInstants() {
            List<Instant> instants = new ArrayList<>();
            for (LocalDate date : expectedDates) {
                instants.add(LocalDateTime.of(date, localStart).atZone(zone).toInstant());
            }
            return instants;
        }
    }

    /**
     * Builds a four-occurrence weekly series that starts before the next Europe/Berlin
     * spring-forward transition and ends after it. All dates are in the future.
     */
    private static DstSeries dstSeries(LocalTime localStart) {
        ZoneId zone = ZoneId.of(ZONE_ID);
        ZoneOffsetTransition transition = nextSpringForward(zone, Instant.now());
        LocalDate transitionDate = transition.getDateTimeAfter().toLocalDate();
        LocalDate firstDate = transitionDate.minusDays(13);

        List<LocalDate> dates = new ArrayList<>();
        for (int week = 0; week < 4; week++) {
            dates.add(firstDate.plusWeeks(week));
        }
        assertTrue(dates.get(1).isBefore(transitionDate) && dates.get(2).isAfter(transitionDate),
                "fixture must span the DST transition on " + transitionDate);
        assertTrue(LocalDateTime.of(dates.get(0), localStart).atZone(zone).toInstant().isAfter(Instant.now()),
                "fixture occurrences must all be in the future");
        return new DstSeries(zone, localStart, dates);
    }

    private static ZoneOffsetTransition nextSpringForward(ZoneId zone, Instant after) {
        Instant cursor = after;
        for (int probe = 0; probe < 100; probe++) {
            ZoneOffsetTransition transition = zone.getRules().nextTransition(cursor);
            if (transition == null) {
                break;
            }
            if (transition.getOffsetAfter().getTotalSeconds() > transition.getOffsetBefore().getTotalSeconds()) {
                return transition;
            }
            cursor = transition.getInstant().plusSeconds(1);
        }
        throw new IllegalStateException("No upcoming spring-forward transition for zone " + zone);
    }

    private static LocalDateTime firstStableWeeklyStart(ZoneId zone, LocalTime localStart, int occurrences) {
        LocalDate date = LocalDate.now(zone).plusDays(21);
        for (int attempt = 0; attempt < 104; attempt++) {
            LocalDateTime candidate = LocalDateTime.of(date, localStart);
            ZoneOffset offset = zone.getRules().getOffset(candidate);
            boolean stable = true;
            for (int week = 1; week < occurrences; week++) {
                if (!zone.getRules().getOffset(candidate.plusWeeks(week)).equals(offset)) {
                    stable = false;
                    break;
                }
            }
            if (stable) {
                return candidate;
            }
            date = date.plusWeeks(1);
        }
        throw new IllegalStateException("No DST-stable weekly window found in zone " + zone);
    }

    private static List<Duration> gaps(List<Instant> instants) {
        List<Duration> gaps = new ArrayList<>();
        for (int index = 1; index < instants.size(); index++) {
            gaps.add(Duration.between(instants.get(index - 1), instants.get(index)));
        }
        return gaps;
    }

    private static Set<ZoneOffset> offsets(List<Instant> instants, ZoneId zone) {
        Set<ZoneOffset> offsets = new LinkedHashSet<>();
        for (Instant instant : instants) {
            offsets.add(instant.atZone(zone).getOffset());
        }
        return offsets;
    }

    // ------------------------------------------------------------------
    // HTTP helpers (documented contract only)
    // ------------------------------------------------------------------

    private MvcResult postSeries(long resourceId, String title, LocalDateTime localStart, Duration duration,
                                 String timeZone, int occurrences) throws Exception {
        ZonedDateTime zonedStart = localStart.atZone(ZoneId.of(timeZone));
        OffsetDateTime startsAt = zonedStart.toOffsetDateTime();
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("resourceId", resourceId);
        payload.put("title", title);
        payload.put("startsAt", startsAt.toString());
        payload.put("endsAt", startsAt.plus(duration).toString());
        payload.put("timezone", timeZone);
        payload.put("frequency", "WEEKLY");
        payload.put("occurrences", occurrences);
        return postJson("/api/reservation-series", MAPPER.writeValueAsString(payload));
    }

    private MvcResult postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url)
                        .with(user("series-writer").roles("EMPLOYEE", "MANAGER", "ADMIN", "USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn();
    }

    private MvcResult getJson(String url) throws Exception {
        return mockMvc.perform(get(url)
                        .with(user("series-writer").roles("EMPLOYEE", "MANAGER", "ADMIN", "USER"))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private static void assertSuccess(MvcResult result, String action) {
        int status = result.getResponse().getStatus();
        assertTrue(status >= 200 && status < 300,
                action + " failed with HTTP " + status + "; response body: " + body(result));
    }

    private static boolean isClientError(int status) {
        return status >= 400 && status < 500;
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Occurrence observation helpers
    // ------------------------------------------------------------------

    /**
     * Collects every occurrence start instant the response exposes at the expected
     * local clock time. The business document does not fix the response field names,
     * so all offset-bearing date-time values in the payload are considered and the
     * occurrences are identified by their local wall-clock time in the supplied zone.
     */
    private static List<Instant> occurrenceStartsAtLocalTime(MvcResult result, ZoneId zone, LocalTime localTime)
            throws Exception {
        JsonNode root = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        List<OffsetDateTime> payloadDateTimes = new ArrayList<>();
        collectDateTimes(root, payloadDateTimes);

        List<Instant> starts = new ArrayList<>();
        for (OffsetDateTime value : payloadDateTimes) {
            if (value.atZoneSameInstant(zone).toLocalTime().equals(localTime)) {
                starts.add(value.toInstant());
            }
        }
        return starts;
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

    private static OffsetDateTime parseOffsetDateTime(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException notAnOffsetDateTime) {
            // fall through to the remaining documented date-time shapes
        }
        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException notAnInstant) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(text).toOffsetDateTime();
        } catch (DateTimeParseException notAZonedDateTime) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Resource fixture
    // ------------------------------------------------------------------

    /**
     * Creates an active or inactive ROOM through the resource endpoint so the series
     * under test targets a resource that this test owns. The response shape used to
     * read the id back was verified against the running API (id, code, name, type,
     * building, floorNumber, capacity, active, approvalRequired).
     */
    private long createResource(String code, boolean active) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("code", code);
        payload.put("name", "Reservation series fixture " + code);
        payload.put("type", "ROOM");
        payload.put("building", "HQ");
        payload.put("floorNumber", 1);
        payload.put("capacity", 10);
        payload.put("active", active);
        payload.put("approvalRequired", false);

        MvcResult created = postJson("/api/resources", MAPPER.writeValueAsString(payload));
        int status = created.getResponse().getStatus();
        assertTrue(status >= 200 && status < 300,
                "fixture resource creation via POST /api/resources failed with HTTP " + status
                        + "; response body: " + body(created));

        JsonNode createdResource = MAPPER.readTree(created.getResponse().getContentAsByteArray());
        assertEquals(active, createdResource.path("active").asBoolean(),
                "fixture resource " + code + " was not stored with the requested active flag");

        Long id = findIdForCode(createdResource, code);
        if (id == null) {
            MvcResult listed = getJson("/api/resources");
            if (listed.getResponse().getStatus() >= 200 && listed.getResponse().getStatus() < 300) {
                id = findIdForCode(MAPPER.readTree(listed.getResponse().getContentAsByteArray()), code);
            }
        }
        assertNotNull(id, "could not resolve the id of fixture resource " + code + " from the API responses");
        return id;
    }

    /** Counts reservations the documented reservation listing exposes for a resource. */
    private int reservationCountForResource(long resourceId) throws Exception {
        MvcResult listed = getJson("/api/reservations");
        int status = listed.getResponse().getStatus();
        assertTrue(status >= 200 && status < 300,
                "listing reservations failed with HTTP " + status + "; response body: " + body(listed));
        JsonNode root = MAPPER.readTree(listed.getResponse().getContentAsByteArray());
        int count = 0;
        if (root.isArray()) {
            for (JsonNode entry : root) {
                if (entry.path("resourceId").asLong(-1L) == resourceId) {
                    count++;
                }
            }
        }
        return count;
    }

    private static Long findIdForCode(JsonNode root, String code) {
        List<JsonNode> matching = new ArrayList<>();
        collectObjectsWithCode(root, code, matching);
        for (JsonNode object : matching) {
            JsonNode id = object.get("id");
            if (id != null && id.canConvertToLong()) {
                return id.asLong();
            }
        }
        if (!matching.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        collectIds(root, ids);
        return ids.size() == 1 ? ids.get(0) : null;
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

    private static void collectIds(JsonNode node, List<Long> sink) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode id = node.get("id");
            if (id != null && id.canConvertToLong()) {
                sink.add(id.asLong());
            }
            node.forEach(child -> collectIds(child, sink));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectIds(child, sink);
            }
        }
    }

    private static String uniqueResourceCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }
}
