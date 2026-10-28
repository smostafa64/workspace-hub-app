package com.example.workspace.integration;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
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
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Integration-layer verification that bookings which merely touch (end == start) are accepted in
 * both directions (SCN-025, REQ-004, C6).
 *
 * <p>Each test seeds the frozen precondition (a one-off reservation 09:00-10:00 Europe/Berlin on
 * 2026-11-03 through the documented single-reservation write binding
 * {@link ReservationService#create(BookingInput, String)}), POSTs a two-occurrence weekly series
 * through the documented HTTP entry point (POST /api/reservation-series through the full Spring MVC
 * + security + validation + service + JPA stack), and then reads the room's committed rows in a
 * fresh transaction. The absence of a 409 is observed on the real response, and the coexistence of
 * the adjacent bookings is observed in the store, not in the response echo.</p>
 *
 * <p>2026-11-03 is after the Europe/Berlin DST end (2026-10-25), i.e. CET +01:00: 09:00-10:00 local
 * is 08:00Z-09:00Z. The forward-adjacent series starts 10:00 local (09:00Z); the backward-adjacent
 * series ends 09:00 local (08:00Z). All instants lie in the future relative to the fixed test clock
 * (2026-03-01T00:00Z).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesAdjacencyIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";

    /** Frozen precondition: the one-off booking 09:00-10:00 Europe/Berlin on 2026-11-03. */
    private static final Instant ONE_OFF_START = Instant.parse("2026-11-03T08:00:00Z");
    private static final Instant ONE_OFF_END = Instant.parse("2026-11-03T09:00:00Z");

    /** Forward-adjacent series 10:00-11:00 Europe/Berlin on 2026-11-03 (09:00Z-10:00Z). */
    private static final String FORWARD_SERIES_START = "2026-11-03T10:00:00+01:00";
    private static final String FORWARD_SERIES_END = "2026-11-03T11:00:00+01:00";
    private static final Instant FORWARD_FIRST_START = Instant.parse("2026-11-03T09:00:00Z");

    /** Backward-adjacent series 08:00-09:00 Europe/Berlin on 2026-11-03 (07:00Z-08:00Z). */
    private static final String BACKWARD_SERIES_START = "2026-11-03T08:00:00+01:00";
    private static final String BACKWARD_SERIES_END = "2026-11-03T09:00:00+01:00";
    private static final Instant BACKWARD_FIRST_START = Instant.parse("2026-11-03T07:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-025#A1 (integration) - A series whose first occurrence starts exactly when the existing
     * one-off ends is created without a 409, and both bookings coexist in storage.
     */
    @Test
    void scn025A1SeriesStartingWhenOneOffEndsIsCreatedAndBothCoexist() throws Exception {
        Long resourceId = createRoom("INT-025A1");
        seedOneOff(resourceId);

        MvcResult result = mockMvc.perform(
                        seriesRequest(resourceId, FORWARD_SERIES_START, FORWARD_SERIES_END, 2))
                .andReturn();

        String payload = body(result);
        assertNotEquals(409, result.getResponse().getStatus(),
                "a series whose occurrence starts exactly when the existing one-off ends (end == start) "
                        + "does not conflict and must not be rejected with 409, but the body was: " + payload);
        assertTrue(result.getResponse().getStatus() >= 200 && result.getResponse().getStatus() < 300,
                "the adjacent series must be created, but the status was "
                        + result.getResponse().getStatus() + " with body: " + payload);

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(),
                "the existing one-off and the two occurrences of the adjacent series must coexist in "
                        + "storage, but " + describe(stored));

        Reservation oneOff = reservationAt(stored, ONE_OFF_START);
        Reservation adjacent = reservationAt(stored, FORWARD_FIRST_START);
        assertEquals(ONE_OFF_END, adjacent.getStartsAt(),
                "the adjacent series occurrence must start exactly when the one-off ends");
        assertEquals(oneOff.getEndsAt(), adjacent.getStartsAt(),
                "the touching bookings must meet exactly at 09:00 local (end == start), but the one-off "
                        + "ends at " + oneOff.getEndsAt() + " and the series occurrence starts at "
                        + adjacent.getStartsAt());
        assertNotNull(adjacent.getSeriesId(),
                "the stored adjacent occurrence must carry the series identifier of the accepted series, "
                        + "but stored " + describe(stored));
        assertTrue(oneOff.getSeriesId() == null,
                "the pre-existing one-off booking must remain a one-off row without a series identifier, "
                        + "but stored " + describe(stored));
    }

    /**
     * SCN-025#A2 (integration) - A series whose first occurrence ends exactly when the existing
     * one-off starts is created without a 409, and both bookings coexist in storage.
     */
    @Test
    void scn025A2SeriesEndingWhenOneOffStartsIsCreatedAndBothCoexist() throws Exception {
        Long resourceId = createRoom("INT-025A2");
        seedOneOff(resourceId);

        MvcResult result = mockMvc.perform(
                        seriesRequest(resourceId, BACKWARD_SERIES_START, BACKWARD_SERIES_END, 2))
                .andReturn();

        String payload = body(result);
        assertNotEquals(409, result.getResponse().getStatus(),
                "a series whose occurrence ends exactly when the existing one-off starts (end == start) "
                        + "does not conflict and must not be rejected with 409, but the body was: " + payload);
        assertTrue(result.getResponse().getStatus() >= 200 && result.getResponse().getStatus() < 300,
                "the adjacent series must be created, but the status was "
                        + result.getResponse().getStatus() + " with body: " + payload);

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(),
                "the existing one-off and the two occurrences of the adjacent series must coexist in "
                        + "storage, but " + describe(stored));

        Reservation oneOff = reservationAt(stored, ONE_OFF_START);
        Reservation adjacent = reservationAt(stored, BACKWARD_FIRST_START);
        assertEquals(oneOff.getStartsAt(), adjacent.getEndsAt(),
                "the adjacent series occurrence must end exactly when the one-off starts (end == start), "
                        + "but the series occurrence ends at " + adjacent.getEndsAt() + " and the one-off "
                        + "starts at " + oneOff.getStartsAt());
        assertNotNull(adjacent.getSeriesId(),
                "the stored adjacent occurrence must carry the series identifier of the accepted series, "
                        + "but stored " + describe(stored));
        assertTrue(oneOff.getSeriesId() == null,
                "the pre-existing one-off booking must remain a one-off row without a series identifier, "
                        + "but stored " + describe(stored));
    }

    // ------------------------------------------------------------------
    // Fixtures and read helpers
    // ------------------------------------------------------------------

    /** Frozen SCN-025 precondition: a one-off reservation 09:00-10:00 on 2026-11-03. */
    private void seedOneOff(Long resourceId) {
        reservationService.create(new BookingInput(resourceId, "Existing one-off",
                ONE_OFF_START, ONE_OFF_END), "seed");
        List<Reservation> seeded = storedReservations(resourceId);
        assertEquals(1, seeded.size(),
                "the fixture room must hold exactly the one-off reservation before the series request, "
                        + "but " + describe(seeded));
        assertEquals(ONE_OFF_START, seeded.get(0).getStartsAt(),
                "the seeded one-off reservation must start at 09:00 local on 2026-11-03, but "
                        + describe(seeded));
        assertEquals(ONE_OFF_END, seeded.get(0).getEndsAt(),
                "the seeded one-off reservation must end at 10:00 local on 2026-11-03, but "
                        + describe(seeded));
    }

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

    private static Reservation reservationAt(List<Reservation> stored, Instant startsAt) {
        return stored.stream()
                .filter(reservation -> startsAt.equals(reservation.getStartsAt()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no reservation is stored at " + startsAt + ", stored " + describe(stored)));
    }

    /**
     * Reads the committed rows for one fixture resource in their own transaction, i.e. outside the
     * persistence context that served the writes, ordered by start instant.
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
                        + ", endsAt=" + reservation.getEndsAt()
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
     * Deterministic clock fixture: the frozen acceptance window (November 2026) must be in the
     * future for the write path, independent of the machine clock.
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
