package com.example.workspace.integration;

import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ResourceService;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration-layer verification that the resolved endsAt boundary instant of a recurring series is
 * the one persisted on the occurrence row, across both Europe/Berlin DST transitions (SCN-026,
 * REQ-002, C1).
 *
 * <p>Each test POSTs the frozen request through the documented HTTP entry point
 * (POST /api/reservation-series through the full Spring MVC + security + validation + service + JPA
 * stack) and then reads the committed occurrence rows in a fresh transaction. The assertion is made
 * against the stored end instant and the stored duration, never against the request DTO: the frozen
 * layer rationale for SCN-026 assigns to this layer the proof that "the resolved end instant is
 * persisted on the occurrence row".</p>
 *
 * <p>Frozen Europe/Berlin expectations:</p>
 * <ul>
 *   <li>2026-03-29: the local end boundary 02:30 does not exist and is shifted forward by the
 *       one-hour gap to 03:30, i.e. 01:30Z; the first occurrence (start 01:00 local = 00:00Z) is
 *       90 minutes long and stays valid.</li>
 *   <li>2026-10-25: the local end boundary 02:30 is ambiguous and resolves to the earlier UTC
 *       instant 00:30Z, not 01:30Z; the first occurrence (start 01:00 local +02:00 = 2026-10-24
 *       23:00Z) is 90 minutes long and stays valid.</li>
 * </ul>
 *
 * <p>Both windows lie in the future relative to the fixed test clock (2026-03-01T00:00Z). Fetching
 * the first occurrence is done by reading the persisted rows ordered by start instant.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesDstEndBoundaryIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static final int OCCURRENCES = 2;
    private static final Duration EXPECTED_DURATION = Duration.ofMinutes(90);

    /** Spring-forward: local 01:00 (+01:00) to the shifted end 03:30 (+02:00) = 01:30Z. */
    private static final String SPRING_START = "2026-03-29T01:00:00+01:00";
    private static final String SPRING_END = "2026-03-29T02:30:00+01:00";
    private static final Instant SPRING_FIRST_START = Instant.parse("2026-03-29T00:00:00Z");
    private static final Instant SPRING_FIRST_END = Instant.parse("2026-03-29T01:30:00Z");

    /** Fall-back: local 01:00 (+02:00) to the earlier ambiguous end 00:30Z. */
    private static final String AUTUMN_START = "2026-10-25T01:00:00+02:00";
    private static final String AUTUMN_END = "2026-10-25T02:30:00+02:00";
    private static final Instant AUTUMN_FIRST_START = Instant.parse("2026-10-24T23:00:00Z");
    private static final Instant AUTUMN_FIRST_END = Instant.parse("2026-10-25T00:30:00Z");
    private static final Instant AUTUMN_LATER_AMBIGUOUS_INSTANT = Instant.parse("2026-10-25T01:30:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-026#A1 (integration) - The nonexistent 2026-03-29 end boundary local 02:30 is shifted
     * forward to 03:30 (01:30Z), so the persisted first occurrence ends at 2026-03-29T01:30:00Z and
     * keeps a 90-minute duration.
     */
    @Test
    void scn026A1SpringForwardEndBoundaryIsPersistedAs0130Z() throws Exception {
        Long resourceId = createRoom("INT-026A1");

        MvcResult result = mockMvc.perform(seriesRequest(resourceId, SPRING_START, SPRING_END, OCCURRENCES))
                .andReturn();
        String payload = body(result);
        assertTrue(result.getResponse().getStatus() >= 200 && result.getResponse().getStatus() < 300,
                "the series whose end boundary shifts forward must stay valid and be created, but the "
                        + "status was " + result.getResponse().getStatus() + " with body: " + payload);

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(OCCURRENCES, stored.size(),
                "both occurrences of the accepted series must be persisted, but " + describe(stored));

        Reservation first = stored.get(0);
        assertEquals(SPRING_FIRST_START, first.getStartsAt(),
                "the first occurrence must start at 01:00 local Europe/Berlin (2026-03-29T00:00:00Z), but "
                        + describe(stored));
        assertEquals(SPRING_FIRST_END, first.getEndsAt(),
                "the nonexistent local end boundary 02:30 must be persisted as its shifted instant "
                        + "2026-03-29T01:30:00Z (03:30 local), but the stored end was " + first.getEndsAt());
        assertEquals(EXPECTED_DURATION, Duration.between(first.getStartsAt(), first.getEndsAt()),
                "the shifted end boundary must leave the first occurrence with a 90-minute duration, but it "
                        + "was " + Duration.between(first.getStartsAt(), first.getEndsAt()));

        ZonedDateTime localEnd = first.getEndsAt().atZone(BERLIN);
        assertEquals(LocalTime.of(3, 30), localEnd.toLocalTime(),
                "the persisted end instant must read 03:30 local Europe/Berlin after the forward shift, but "
                        + "it reads " + localEnd);
    }

    /**
     * SCN-026#A2 (integration) - The ambiguous 2026-10-25 end boundary local 02:30 resolves to the
     * earlier UTC instant, so the persisted first occurrence ends at 2026-10-25T00:30:00Z rather
     * than 2026-10-25T01:30:00Z.
     */
    @Test
    void scn026A2AutumnAmbiguousEndBoundaryIsPersistedAs0030Z() throws Exception {
        Long resourceId = createRoom("INT-026A2");

        MvcResult result = mockMvc.perform(seriesRequest(resourceId, AUTUMN_START, AUTUMN_END, OCCURRENCES))
                .andReturn();
        String payload = body(result);
        assertTrue(result.getResponse().getStatus() >= 200 && result.getResponse().getStatus() < 300,
                "the series whose end boundary is ambiguous must stay valid and be created, but the "
                        + "status was " + result.getResponse().getStatus() + " with body: " + payload);

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(OCCURRENCES, stored.size(),
                "both occurrences of the accepted series must be persisted, but " + describe(stored));

        Reservation first = stored.get(0);
        assertEquals(AUTUMN_FIRST_START, first.getStartsAt(),
                "the first occurrence must start at 01:00 local Europe/Berlin (2026-10-24T23:00:00Z), but "
                        + describe(stored));
        assertEquals(AUTUMN_FIRST_END, first.getEndsAt(),
                "the ambiguous local end boundary 02:30 must be persisted as the earlier instant "
                        + "2026-10-25T00:30:00Z, but the stored end was " + first.getEndsAt());
        assertNotEquals(AUTUMN_LATER_AMBIGUOUS_INSTANT, first.getEndsAt(),
                "the ambiguous end boundary must not resolve to the later 2026-10-25T01:30:00Z instant, but "
                        + "the stored end was " + first.getEndsAt());
        assertEquals(EXPECTED_DURATION, Duration.between(first.getStartsAt(), first.getEndsAt()),
                "the earlier ambiguous end boundary must leave the first occurrence with a 90-minute "
                        + "duration, but it was " + Duration.between(first.getStartsAt(), first.getEndsAt()));
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
     * persistence context that served the write, ordered by start instant.
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
        if (reservations == null || reservations.isEmpty()) {
            return "stored={}";
        }
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
     * Deterministic clock fixture: both DST windows (March/October 2026) must be in the future for
     * the write path, independent of the machine clock.
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
