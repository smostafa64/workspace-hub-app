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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Integration-layer verification that a series request rejected before any booking (C4 and C5)
 * leaves the room's reservation set untouched (SCN-021, REQ-001, REQ-009, REQ-010).
 *
 * <p>Each case drives the real HTTP entry point (POST /api/reservation-series through the full
 * Spring MVC + security + validation + service + JPA stack), then re-reads the fixture room's
 * committed rows in a fresh transaction. The reservation count of the room is therefore observed
 * in the store, not in the response body: a rejection that nonetheless wrote a row would be
 * visible as a changed count.</p>
 *
 * <p>Every case uses its own uniquely coded free room and a future window (2030-05-06, i.e. after
 * the fixed test clock at 2026-03-01T00:00Z), so cases cannot affect each other.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesRejectionIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";
    private static final String BERLIN = "Europe/Berlin";

    /** An otherwise valid series request; only the probed field is varied. */
    private static final String VALID_START = "2030-05-06T10:00:00+02:00";
    private static final String VALID_END = "2030-05-06T11:00:00+02:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-021#A1 (integration) - occurrences=2.5 returns 400 and the room's reservation count is
     * unchanged, as observed in the committed store.
     */
    @Test
    void scn021A1FractionalOccurrencesAreRejectedWithUnchangedReservationCount() throws Exception {
        Long resourceId = createRoom("Room 101", 1);

        List<Reservation> before = committedReservations(resourceId);
        assertEquals(0, before.size(),
                "the fixture room must start free, but it holds " + describe(before));

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, VALID_START, VALID_END, BERLIN, "WEEKLY", "2.5"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "occurrences=2.5 is fractional and must be rejected with 400, but the body was: "
                        + body(result));

        List<Reservation> after = committedReservations(resourceId);
        assertEquals(before.size(), after.size(),
                "the room's reservation count must be unchanged after the occurrences=2.5 rejection, "
                        + "but " + describe(after));
        assertTrue(after.isEmpty(),
                "a request rejected because occurrences=2.5 is fractional must not write a reservation row, "
                        + "but " + describe(after));
    }

    /**
     * SCN-021#A2 (integration) - timezone=+01:00 returns 400 and no reservation row is written.
     */
    @Test
    void scn021A2FixedOffsetTimezoneIsRejectedWithoutWritingARow() throws Exception {
        Long resourceId = createRoom("Room 101", 1);

        List<Reservation> before = committedReservations(resourceId);
        assertEquals(0, before.size(),
                "the fixture room must start free, but it holds " + describe(before));

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, VALID_START, VALID_END, "+01:00", "WEEKLY", "3"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "a fixed-offset timezone is not an IANA area identifier and must be rejected with 400, "
                        + "but the body was: " + body(result));

        List<Reservation> after = committedReservations(resourceId);
        assertTrue(after.isEmpty(),
                "a request rejected because timezone=+01:00 is invalid must not write any reservation "
                        + "row, but " + describe(after));
        assertEquals(before.size(), after.size(),
                "the room's reservation count must be unchanged after the timezone=+01:00 rejection");
    }

    /**
     * SCN-021#A3 (integration) - for a request rejected with 400 under C5 (missing offset, invalid
     * timezone or a non-WEEKLY frequency) the room's reservation count is unchanged.
     */
    @Test
    void scn021A3C5RejectionsLeaveTheRoomReservationCountUnchanged() throws Exception {
        assertRejectedWithoutWrite("date-times lacking a UTC offset",
                "2030-05-06T10:00:00", "2030-05-06T11:00:00", BERLIN, "WEEKLY", "3");
        assertRejectedWithoutWrite("fixed-offset timezone +01:00",
                VALID_START, VALID_END, "+01:00", "WEEKLY", "3");
        assertRejectedWithoutWrite("timezone abbreviation CET",
                VALID_START, VALID_END, "CET", "WEEKLY", "3");
        assertRejectedWithoutWrite("non-WEEKLY frequency DAILY",
                VALID_START, VALID_END, BERLIN, "DAILY", "3");
    }

    /**
     * SCN-021#A4 (integration) - for a request rejected with 400 because occurrences is fractional,
     * out of range or not an integer, the room's reservation count is unchanged.
     */
    @Test
    void scn021A4InvalidOccurrenceCountsLeaveTheRoomReservationCountUnchanged() throws Exception {
        assertRejectedWithoutWrite("fractional occurrences 2.5",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "2.5");
        assertRejectedWithoutWrite("occurrences 13 above the 2-12 range",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "13");
        assertRejectedWithoutWrite("occurrences 1 below the 2-12 range",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "1");
        assertRejectedWithoutWrite("numeric-string occurrences \"3\"",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "\"3\"");
        assertRejectedWithoutWrite("boolean occurrences true",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "true");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * One isolated free room, a recorded reservation count, the rejected request and a re-read of
     * the room's reservation count from the committed store.
     */
    private void assertRejectedWithoutWrite(String label, String startsAt, String endsAt,
                                            String timezone, String frequency,
                                            String occurrencesJson) throws Exception {
        Long resourceId = createRoom("Room 101", 1);

        List<Reservation> before = committedReservations(resourceId);
        assertEquals(0, before.size(),
                "fixture room must start free for the '" + label + "' case, but " + describe(before));

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, startsAt, endsAt, timezone, frequency, occurrencesJson))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "a request rejected under C4/C5 must be answered with 400 for the '" + label
                        + "' case, but the body was: " + body(result));

        List<Reservation> after = committedReservations(resourceId);
        assertEquals(before.size(), after.size(),
                "the room's reservation count must be unchanged after the '" + label + "' rejection, "
                        + "but " + describe(after));
        assertTrue(after.isEmpty(),
                "a rejected request must not write any reservation row for the '" + label + "' case, "
                        + "but " + describe(after));
    }

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

    private Long createRoom(String name, int floorNumber) {
        String code = "R" + floorNumber + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        ResourceView room = resourceService.create(new ResourceInput(
                code, name, ResourceType.ROOM, "HQ", floorNumber, 8, true, false));
        assertNotNull(room.id(), "fixture room must be persisted with an identifier");
        fixtureResourceIds.add(room.id());
        return room.id();
    }

    /**
     * Reads the committed rows for one fixture resource in their own transaction, i.e. outside the
     * persistence context that served the writes.
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
                        + ", status=" + reservation.getStatus() + "]")
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
     * Deterministic clock fixture: the write window (2030-05-06) must be in the future for the
     * validation path, independent of the machine clock.
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
