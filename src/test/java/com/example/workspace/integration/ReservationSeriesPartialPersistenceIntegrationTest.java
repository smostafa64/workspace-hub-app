package com.example.workspace.integration;

import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-layer verification of the partial-persistence outcome of a series request that
 * contains one invalid occurrence (SCN-020, REQ-011, C3).
 *
 * <p>Each test drives the documented HTTP entry point (POST /api/reservation-series through the
 * full Spring MVC + security + validation + service + JPA stack) and then observes the committed
 * rows in a fresh transaction. The HTTP response body is read only for the content the frozen
 * contract requires it to carry: the shared series identifier and the reported occurrence
 * indexes. The JSON field names of that body are not frozen (AMB-001), so identity is asserted on
 * the documented content, never on an invented schema.</p>
 *
 * <p>The partial arrangement uses the Europe/Berlin spring-forward transition: a weekly series
 * requested with the wall-clock window 01:50-03:00 is 70 minutes long on 2030-03-24 and
 * 2030-04-07, but only 10 minutes on 2030-03-31, where the local hour 02:00-03:00 does not exist.
 * The middle requested occurrence therefore fails the 15-minute minimum duration while the first
 * and the third occurrence stay valid, which is the frozen SCN-020 precondition. All instants lie
 * in 2030, i.e. in the future relative to the fixed test clock (2026-03-01T00:00Z).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesPartialPersistenceIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";
    private static final String BERLIN = "Europe/Berlin";

    /** Requested weekly wall-clock window 01:50-03:00 Europe/Berlin. */
    private static final String PARTIAL_START = "2030-03-24T01:50:00+01:00";
    private static final String PARTIAL_END = "2030-03-24T03:00:00+01:00";

    /** The three requested occurrence starts in UTC; the middle one is 10 minutes long. */
    private static final List<Instant> OCCURRENCE_STARTS = List.of(
            Instant.parse("2030-03-24T00:50:00Z"),
            Instant.parse("2030-03-31T00:50:00Z"),
            Instant.parse("2030-04-06T23:50:00Z"));

    private static final Duration VALID_OCCURRENCE_DURATION = Duration.ofMinutes(70);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-020#A1 (integration) - The committed rows are exactly the two valid occurrences: the
     * failed middle occurrence leaves a gap in the stored series and no row exists at its position.
     */
    @Test
    void scn020A1TwoValidOccurrencesPersistWithAGapAtTheFailedPosition() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, PARTIAL_START, PARTIAL_END, BERLIN, "WEEKLY", "3"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "a series whose middle occurrence fails duration validation must be answered with 400, "
                        + "but the body was: " + body(result));

        List<Reservation> stored = committedReservations(resourceId);
        assertEquals(2, stored.size(),
                "exactly the two valid occurrences may be persisted, so the stored series has a gap, but "
                        + describe(stored));

        Instant firstStart = OCCURRENCE_STARTS.get(0);
        Instant failedStart = OCCURRENCE_STARTS.get(1);
        Instant thirdStart = OCCURRENCE_STARTS.get(2);

        Set<Instant> storedStarts = stored.stream()
                .map(Reservation::getStartsAt)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(Set.of(firstStart, thirdStart), storedStarts,
                "the persisted rows must be exactly the first and the third requested occurrence, so the "
                        + "failed middle occurrence leaves a gap in the stored series, but " + describe(stored));

        assertTrue(stored.stream().noneMatch(reservation -> failedStart.equals(reservation.getStartsAt())),
                "no row may exist at the position of the failed middle occurrence " + failedStart
                        + ", but " + describe(stored));

        for (Reservation reservation : stored) {
            assertEquals(VALID_OCCURRENCE_DURATION,
                    Duration.between(reservation.getStartsAt(), reservation.getEndsAt()),
                    "every persisted occurrence must keep the valid 70-minute duration of the accepted "
                            + "occurrences, but occurrence " + reservation.getId() + " has "
                            + Duration.between(reservation.getStartsAt(), reservation.getEndsAt()));
        }

        UUID seriesId = stored.get(0).getSeriesId();
        assertNotNull(seriesId,
                "the persisted occurrences of the partially persisted series must still carry the series "
                        + "identifier");
        for (Reservation reservation : stored) {
            assertEquals(seriesId, reservation.getSeriesId(),
                    "the two persisted occurrences must belong to the one series whose middle occurrence "
                            + "failed");
        }
    }

    /**
     * SCN-020#A2 (integration) - The persisted and failed occurrence indexes reported in the 400
     * body identify the actual stored outcome: the reported persisted indexes correspond
     * one-to-one with the occurrences for which reservations were created, and the reported failed
     * index corresponds to the occurrence for which no reservation was created, under one
     * consistent numbering convention (0-based or 1-based).
     *
     * <p>The actual outcome is established from the committed store first; the response body is
     * then required to describe exactly that outcome. Because the body's JSON field names are not
     * frozen (AMB-001), the indexes are located structurally: the three occurrence indexes that
     * appear in the body must be exactly the persisted ones plus the failed one, and one integer
     * list in the body must be exactly the persisted indexes, one-to-one.</p>
     */
    @Test
    void scn020A2ReportedOccurrenceIndexesIdentifyTheStoredOutcome() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, PARTIAL_START, PARTIAL_END, BERLIN, "WEEKLY", "3"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "a partially persisted series must be answered with 400, but the body was: " + body(result));

        List<Reservation> stored = committedReservations(resourceId);
        assertEquals(2, stored.size(),
                "the partial outcome must have stored exactly the two valid occurrences, but "
                        + describe(stored));

        Set<Integer> persistedPositions = new TreeSet<>();
        for (Reservation reservation : stored) {
            int position = OCCURRENCE_STARTS.indexOf(reservation.getStartsAt());
            assertTrue(position >= 0,
                    "every persisted row must sit at one of the three requested occurrence starts, but "
                            + reservation.getStartsAt() + " is not one of them");
            persistedPositions.add(position);
        }
        assertEquals(Set.of(0, 2), persistedPositions,
                "the committed rows must be the first and the third occurrence, leaving the middle one "
                        + "unpersisted, but the stored positions are " + persistedPositions);

        assertReportedIndexesIdentifyTheOutcome(body(result), persistedPositions, 1);
    }

    /**
     * SCN-020#A4 (integration) - The seriesId reported in the 400 body equals the series
     * identifier stored on the persisted occurrences.
     *
     * <p>The body's JSON field names are not frozen (AMB-001), so identity is verified on the
     * documented content: the body carries exactly the shared series identifier of the persisted
     * rows and no other identifier.</p>
     */
    @Test
    void scn020A4ReportedSeriesIdEqualsTheSeriesIdStoredOnTheOccurrences() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, PARTIAL_START, PARTIAL_END, BERLIN, "WEEKLY", "3"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "the partially persisted series must be answered with 400, but the body was: "
                        + body(result));

        List<Reservation> stored = committedReservations(resourceId);
        assertEquals(2, stored.size(),
                "the partial outcome must have stored exactly the two valid occurrences, but "
                        + describe(stored));

        UUID storedSeriesId = stored.get(0).getSeriesId();
        assertNotNull(storedSeriesId,
                "the persisted occurrences must carry the series identifier that the 400 body reports");
        for (Reservation reservation : stored) {
            assertEquals(storedSeriesId, reservation.getSeriesId(),
                    "every persisted occurrence of the series must carry the same series identifier, so the "
                            + "reported identifier is unambiguous");
        }

        Set<String> reportedUuids = uuidsIn(body(result));
        assertEquals(Set.of(storedSeriesId.toString().toLowerCase(Locale.ROOT)), reportedUuids,
                "the 400 body must report the series identifier stored on the persisted occurrences "
                        + storedSeriesId + " and no other identifier, but the body was: " + body(result));
    }

    // ------------------------------------------------------------------
    // Reported-index analysis (field names are not frozen: AMB-001)
    // ------------------------------------------------------------------

    /**
     * Asserts that the 400 body reports the supplied persisted occurrence positions and the
     * supplied failed position under one consistent numbering convention.
     *
     * <p>Accepted conventions are 0-based and 1-based. For the accepted convention: the set of
     * occurrence-index values appearing anywhere in the body must be exactly the indexes of the
     * two persisted occurrences plus the index of the failed occurrence (so no occurrence index is
     * unaccounted for and no non-existent occurrence index is claimed), and one integer list in
     * the body must be exactly the persisted indexes, one-to-one.</p>
     */
    private static void assertReportedIndexesIdentifyTheOutcome(String body,
                                                                Set<Integer> persistedPositions,
                                                                int failedPosition) {
        int occurrenceCount = OCCURRENCE_STARTS.size();
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(body);
        } catch (Exception failure) {
            throw new AssertionError("the 400 body of a partially persisted series must be readable JSON, "
                    + "but was: " + body, failure);
        }
        assertNotNull(root,
                "the 400 body of a partially persisted series must carry the reported series outcome, "
                        + "but was: " + body);

        Set<Integer> integralValues = new TreeSet<>();
        List<List<Integer>> integralLists = new ArrayList<>();
        collectIntegralValues(root, integralValues, integralLists);

        List<String> matchingConventions = new ArrayList<>();
        for (int base : new int[] {0, 1}) {
            Set<Integer> expectedOccurrenceIndexes = new TreeSet<>();
            for (int persistedPosition : persistedPositions) {
                expectedOccurrenceIndexes.add(persistedPosition + base);
            }
            expectedOccurrenceIndexes.add(failedPosition + base);

            Set<Integer> reportedOccurrenceIndexes = integralValues.stream()
                    .filter(value -> value >= base && value < base + occurrenceCount)
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!reportedOccurrenceIndexes.equals(expectedOccurrenceIndexes)) {
                continue;
            }

            List<Integer> expectedPersistedList = persistedPositions.stream()
                    .sorted()
                    .map(position -> position + base)
                    .collect(Collectors.toList());
            boolean persistedListReportedOneToOne = integralLists.stream().anyMatch(values ->
                    values.size() == expectedPersistedList.size()
                            && values.stream().sorted().collect(Collectors.toList())
                            .equals(expectedPersistedList));
            if (persistedListReportedOneToOne) {
                matchingConventions.add(base == 0 ? "0-based" : "1-based");
            }
        }

        assertFalse(matchingConventions.isEmpty(),
                "the 400 body must report the occurrence indexes of the persisted occurrences "
                        + persistedPositions + " (0-based) and the index of the failed occurrence "
                        + failedPosition + " (0-based) under one consistent 0-based or 1-based convention, "
                        + "but no such reporting was found. Body: " + body);
    }

    /**
     * Collects every integral JSON value and every JSON array that consists solely of integral
     * values (such an array is a candidate reported index list).
     */
    private static void collectIntegralValues(JsonNode node, Set<Integer> values,
                                              List<List<Integer>> lists) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            List<Integer> elements = new ArrayList<>();
            boolean allIntegral = true;
            for (JsonNode element : node) {
                if (element.isIntegralNumber()) {
                    elements.add(element.intValue());
                } else {
                    allIntegral = false;
                }
                collectIntegralValues(element, values, lists);
            }
            if (allIntegral && !elements.isEmpty()) {
                lists.add(elements);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> collectIntegralValues(entry.getValue(), values, lists));
            return;
        }
        if (node.isIntegralNumber()) {
            values.add(node.intValue());
        }
    }

    // ------------------------------------------------------------------
    // Fixtures and read helpers
    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder seriesPost(Long resourceId, String startsAt, String endsAt,
                                                     String timezone, String frequency,
                                                     String occurrencesJson) {
        String body = """
                {
                  "resourceId": %d,
                  "title": "Weekly planning",
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "timezone": "%s",
                  "frequency": "%s",
                  "occurrences": %s
                }
                """.formatted(resourceId, startsAt, endsAt, timezone, frequency, occurrencesJson);
        return post(SERIES_ENDPOINT)
                .with(user("alice").roles("EMPLOYEE", "MANAGER", "ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Long createRoom(String name, int floorNumber, boolean approvalRequired) {
        String code = "R" + floorNumber + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        ResourceView room = resourceService.create(new ResourceInput(
                code, name, ResourceType.ROOM, "HQ", floorNumber, 8, true, approvalRequired));
        assertNotNull(room.id(), "fixture room must be persisted with an identifier");
        fixtureResourceIds.add(room.id());
        return room.id();
    }

    /**
     * Reads the committed rows for one fixture resource in their own transaction, i.e. outside the
     * persistence context that served the write, ordered by start instant.
     */
    private List<Reservation> committedReservations(Long resourceId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        List<Reservation> stored = template.execute(status -> entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId", Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList());
        if (stored == null) {
            return List.of();
        }
        return stored.stream()
                .sorted(java.util.Comparator.comparing(Reservation::getStartsAt)
                        .thenComparing(Reservation::getId))
                .toList();
    }

    private static String body(MvcResult result) throws Exception {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException unsupportedEncoding) {
            throw new IllegalStateException(
                    "UTF-8 must be supported to read the response body", unsupportedEncoding);
        }
    }

    private static Set<String> uuidsIn(String body) {
        Set<String> uuids = new TreeSet<>();
        Matcher matcher = UUID_PATTERN.matcher(body);
        while (matcher.find()) {
            uuids.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return uuids;
    }

    private static String describe(List<Reservation> reservations) {
        return reservations.stream()
                .map(reservation -> "[id=" + reservation.getId()
                        + ", startsAt=" + reservation.getStartsAt()
                        + ", endsAt=" + reservation.getEndsAt()
                        + ", status=" + reservation.getStatus()
                        + ", seriesId=" + reservation.getSeriesId() + "]")
                .collect(Collectors.joining(", ", "stored={", "}"));
    }

    /**
     * Removes the rows created by this test class so the embedded store stays clean for the
     * remaining suites; fixture rooms are uniquely coded and never shared between tests.
     */
    @AfterEach
    void removeFixtureReservations() {
        if (fixtureResourceIds.isEmpty()) {
            return;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            for (Long resourceId : fixtureResourceIds) {
                entityManager.createQuery("delete from Reservation r where r.resource.id = :resourceId")
                        .setParameter("resourceId", resourceId)
                        .executeUpdate();
            }
        });
        fixtureResourceIds.clear();
    }

    /**
     * Deterministic clock fixture: the frozen write window (2030) must be in the future for the
     * write path, independent of the machine clock.
     */
    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
