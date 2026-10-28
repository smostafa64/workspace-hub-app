package com.example.workspace.integration;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration-layer verification that the 409 produced by a series request rejected against a real
 * stored conflicting reservation carries the conflicting date and occurrence index (SCN-023,
 * REQ-004).
 *
 * <p>The room is seeded through the documented single-reservation write binding
 * {@link ReservationService#create(BookingInput, String)} with a booking strictly inside the second
 * requested occurrence, then the three-occurrence weekly series is POSTed through the documented
 * HTTP entry point (POST /api/reservation-series through the full Spring MVC + security +
 * validation + service + JPA stack). The 409 is therefore raised against a reservation that really
 * exists in the store, not against a mocked collaborator.</p>
 *
 * <p>The 409 body's JSON field names are not frozen, and neither is the base of the reported
 * occurrence index (AMB-007) nor whether the body names the requested occurrence's date and index
 * or the existing reservation's (AMB-009). The assertion is therefore made on the documented
 * content only: the body must carry at least one date on the conflicting local day 2026-06-14
 * (both the requested second occurrence and the seeded booking fall on that Europe/Berlin day) and
 * at least one occurrence index identifying the second requested occurrence under either a 0-based
 * (1) or a 1-based (2) convention. The field names are located structurally, never invented.</p>
 *
 * <p>Frozen Europe/Berlin window: the weekly series requested as 09:00-09:30 on 2026-06-07
 * (2026-06-07T09:00+02:00) expands to 07:00Z on 2026-06-07, 2026-06-14 and 2026-06-21. The seeded
 * booking 07:05Z-07:25Z sits strictly inside the second occurrence. All instants lie in the future
 * relative to the fixed test clock (2026-03-01T00:00Z).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesConflictBodyIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /** Requested three-occurrence series: 09:00-09:30 Europe/Berlin on 2026-06-07 (+02:00). */
    private static final String SERIES_START = "2026-06-07T09:00:00+02:00";
    private static final String SERIES_END = "2026-06-07T09:30:00+02:00";
    private static final int OCCURRENCES = 3;

    /** Pre-existing booking strictly inside the second occurrence (07:00Z-07:30Z on 2026-06-14). */
    private static final Instant SEEDED_START = Instant.parse("2026-06-14T07:05:00Z");
    private static final Instant SEEDED_END = Instant.parse("2026-06-14T07:25:00Z");

    /** The Europe/Berlin local date of both the second occurrence and the seeded booking. */
    private static final LocalDate CONFLICTING_LOCAL_DATE = LocalDate.parse("2026-06-14");

    /** The second requested occurrence is index 1 (0-based) or index 2 (1-based). */
    private static final Set<Integer> SECOND_OCCURRENCE_INDEXES = Set.of(1, 2);

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-023#A1 (integration) - The 409 body reports at least one conflicting date and at least
     * one conflicting occurrence index for the rejected series request.
     */
    @Test
    void scn023A1ConflictBodyReportsAConflictingDateAndOccurrenceIndex() throws Exception {
        Long resourceId = createRoom("INT-023A1");
        reservationService.create(new BookingInput(resourceId, "Existing reservation",
                SEEDED_START, SEEDED_END), "seed");

        List<Reservation> seeded = storedReservations(resourceId);
        assertEquals(1, seeded.size(),
                "the fixture room must hold exactly the seeded conflicting reservation before the series "
                        + "request, but " + describe(seeded));
        assertTrue(seeded.stream().anyMatch(reservation -> SEEDED_START.equals(reservation.getStartsAt())),
                "the conflicting reservation must really be stored inside the second occurrence, but "
                        + describe(seeded));

        MvcResult result = mockMvc.perform(seriesRequest(resourceId, SERIES_START, SERIES_END, OCCURRENCES))
                .andReturn();

        String payload = body(result);
        assertEquals(409, result.getResponse().getStatus(),
                "a series whose second occurrence strictly overlaps the stored reservation must be answered "
                        + "with 409, but the status was " + result.getResponse().getStatus()
                        + " and the body was: " + payload);

        JsonNode root = readJson(payload);
        BodyScan scan = new BodyScan();
        inspect(root, scan);

        assertFalse(scan.conflictingDateValues.isEmpty(),
                "the 409 body must report at least one conflicting date on the conflicting local day "
                        + CONFLICTING_LOCAL_DATE + ", but no such date value was found in the body: " + payload);

        boolean indexReported = hasSecondOccurrenceIndex(scan);
        assertTrue(indexReported,
                "the 409 body must report at least one conflicting occurrence index identifying the second "
                        + "requested occurrence (index 1 when 0-based or 2 when 1-based), but no such value was "
                        + "found in the body: " + payload);
    }

    // ------------------------------------------------------------------
    // Conflict-body analysis (JSON field names are not frozen: AMB-007, AMB-009)
    // ------------------------------------------------------------------

    private static boolean hasSecondOccurrenceIndex(BodyScan scan) {
        boolean namedIndex = scan.indexKeyValues.stream()
                .anyMatch(SECOND_OCCURRENCE_INDEXES::contains);
        boolean pairedIndex = scan.pairedIndexValues.stream()
                .anyMatch(SECOND_OCCURRENCE_INDEXES::contains);
        return namedIndex || pairedIndex;
    }

    /**
     * Walks the JSON tree, collecting every textual value that denotes the conflicting local day,
     * every integral value carried by an index-named field, and every integral value that sits in
     * an object together with a conflicting date (the structural shape of one conflict entry).
     */
    private static void inspect(JsonNode node, BodyScan scan) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            boolean objectCarriesConflictDate = false;
            List<Integer> objectIntegralValues = new ArrayList<>();
            List<String> objectIntegralKeys = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT);
                JsonNode value = field.getValue();
                if (value.isTextual() && isOnConflictingLocalDate(value.asText())) {
                    objectCarriesConflictDate = true;
                }
                if (value.isIntegralNumber()) {
                    objectIntegralValues.add(value.intValue());
                    objectIntegralKeys.add(key);
                    if (key.contains("index")) {
                        scan.indexKeyValues.add(value.intValue());
                    }
                } else if (value.isTextual()) {
                    Integer textualIndex = parseIntegralText(value.asText());
                    if (textualIndex != null) {
                        objectIntegralValues.add(textualIndex);
                        objectIntegralKeys.add(key);
                        if (key.contains("index")) {
                            scan.indexKeyValues.add(textualIndex);
                        }
                    }
                }
                inspect(value, scan);
            }
            if (objectCarriesConflictDate) {
                for (int i = 0; i < objectIntegralValues.size(); i++) {
                    String key = objectIntegralKeys.get(i);
                    if (!key.endsWith("id")) {
                        scan.pairedIndexValues.add(objectIntegralValues.get(i));
                    }
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                inspect(element, scan);
            }
        } else if (node.isTextual()) {
            if (isOnConflictingLocalDate(node.asText())) {
                scan.conflictingDateValues.add(node.asText());
            }
        }
    }

    /** Parses a textual integral value (e.g. an occurrence index serialized as a string). */
    private static Integer parseIntegralText(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty() || text.length() > 9) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException notAnIndex) {
            return null;
        }
    }

    /**
     * True when the textual value denotes 2026-06-14 either as a plain local date, as local
     * date-time, or as an instant/offset date-time that reads 2026-06-14 in Europe/Berlin.
     */
    private static boolean isOnConflictingLocalDate(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return false;
        }
        try {
            if (LocalDate.parse(text).equals(CONFLICTING_LOCAL_DATE)) {
                return true;
            }
        } catch (DateTimeParseException ignored) {
            // not a plain local date
        }
        try {
            if (OffsetDateTime.parse(text).atZoneSameInstant(BERLIN).toLocalDate()
                    .equals(CONFLICTING_LOCAL_DATE)) {
                return true;
            }
        } catch (DateTimeParseException ignored) {
            // not an offset date-time
        }
        try {
            if (Instant.parse(text).atZone(BERLIN).toLocalDate().equals(CONFLICTING_LOCAL_DATE)) {
                return true;
            }
        } catch (DateTimeParseException ignored) {
            // not an instant
        }
        try {
            if (LocalDateTime.parse(text).toLocalDate().equals(CONFLICTING_LOCAL_DATE)) {
                return true;
            }
        } catch (DateTimeParseException ignored) {
            // not a local date-time
        }
        return false;
    }

    private static JsonNode readJson(String payload) {
        try {
            JsonNode root = new ObjectMapper().readTree(payload);
            assertNotNull(root,
                    "the 409 body of a series conflict must carry the conflicting date and occurrence "
                            + "index, but the body was empty: " + payload);
            return root;
        } catch (Exception failure) {
            throw new AssertionError(
                    "the 409 body of a series conflict must be readable JSON, but was: " + payload, failure);
        }
    }

    /** Collected evidence of the 409 body. */
    private static final class BodyScan {
        private final Set<String> conflictingDateValues = new LinkedHashSet<>();
        private final Set<Integer> indexKeyValues = new LinkedHashSet<>();
        private final Set<Integer> pairedIndexValues = new LinkedHashSet<>();
    }

    // ------------------------------------------------------------------
    // Fixtures and read helpers
    // ------------------------------------------------------------------

    private MockHttpServletRequestBuilder seriesRequest(Long resourceId, String startsAt, String endsAt,
                                                        int occurrences) {
        String body = """
                {
                  "resourceId": %d,
                  "title": "Weekly planning",
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "timezone": "Europe/Berlin",
                  "frequency": "WEEKLY",
                  "occurrences": %d
                }
                """.formatted(resourceId, startsAt, endsAt, occurrences);
        return post(SERIES_ENDPOINT)
                .with(user("alice").roles("EMPLOYEE", "MANAGER", "ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Long createRoom(String prefix) {
        String code = prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        ResourceView room = resourceService.create(new ResourceInput(
                code, "Room 101", ResourceType.ROOM, "HQ", 1, 8, true, false));
        assertNotNull(room.id(), "fixture room must be persisted with an identifier");
        fixtureResourceIds.add(room.id());
        return room.id();
    }

    /**
     * Reads the committed rows for one fixture resource in their own transaction, i.e. outside the
     * persistence context that served the write.
     */
    private List<Reservation> storedReservations(Long resourceId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        List<Reservation> stored = template.execute(status -> entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId", Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList());
        if (stored == null) {
            return List.of();
        }
        return stored.stream()
                .sorted(Comparator.comparing(Reservation::getStartsAt).thenComparing(Reservation::getId))
                .toList();
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException unsupportedEncoding) {
            throw new IllegalStateException(
                    "UTF-8 must be supported to read the response body", unsupportedEncoding);
        }
    }

    private static String describe(List<Reservation> reservations) {
        return reservations.stream()
                .map(reservation -> "[id=" + reservation.getId()
                        + ", startsAt=" + reservation.getStartsAt()
                        + ", endsAt=" + reservation.getEndsAt() + "]")
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
     * Deterministic clock fixture: the frozen acceptance window (June 2026) must be in the future
     * for the write path, independent of the machine clock.
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
