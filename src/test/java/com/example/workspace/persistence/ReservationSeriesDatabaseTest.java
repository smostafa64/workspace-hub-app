package com.example.workspace.persistence;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
import com.example.workspace.service.ResourceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Database-layer verification of the recurring-series write path.
 *
 * <p>Every test arranges its request against the documented series endpoint (POST
 * /api/reservation-series) and then observes the persisted rows through a fresh
 * JPA transaction, i.e. the committed H2 store rather than the HTTP response body
 * or a mocked collaborator.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesDatabaseTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    /**
     * SCN-001#A3 - All three created records carry the same non-null series identifier value.
     */
    @Test
    void scn001A3AllThreeStoredOccurrencesShareOneNonNullSeriesId() throws Exception {
        Long resourceId = createRoom101();

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00",
                        3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "the accepted series must persist exactly three occurrences");

        UUID storedSeriesId = stored.get(0).getSeriesId();
        assertNotNull(storedSeriesId, "the three persisted occurrences must carry a non-null series identifier");
        for (Reservation reservation : stored) {
            assertEquals(storedSeriesId, reservation.getSeriesId(),
                    "every persisted occurrence must carry the same series identifier value");
        }
    }

    /**
     * SCN-009#A2 - After the 409 the room holds zero new bookings from the request,
     * including the non-conflicting first and third occurrences.
     */
    @Test
    void scn009A2ConflictOnSecondOccurrencePersistsNoneOfTheOccurrences() throws Exception {
        Long resourceId = createRoom101();
        // Strictly inside the second requested occurrence (2026-03-29 09:00-09:30 Europe/Berlin = 07:00-07:30Z).
        reservationService.create(new BookingInput(resourceId, "Existing reservation",
                Instant.parse("2026-03-29T07:05:00Z"), Instant.parse("2026-03-29T07:25:00Z")), "seed");

        List<Reservation> baseline = storedReservations(resourceId);
        assertEquals(1, baseline.size(), "fixture must contain exactly the pre-existing reservation");
        assertNoSeriesRows(baseline);

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00",
                        3))
                .andExpect(status().isConflict());

        List<Reservation> afterConflict = storedReservations(resourceId);
        assertEquals(1, afterConflict.size(),
                "the rejected series must create zero new bookings, including the non-conflicting first and third occurrences");
        assertNoSeriesRows(afterConflict);
        assertStoredStart(afterConflict, Instant.parse("2026-03-29T07:05:00Z"));
        assertNoStoredStart(afterConflict, Instant.parse("2026-03-22T08:00:00Z"));
        assertNoStoredStart(afterConflict, Instant.parse("2026-04-05T07:00:00Z"));
    }

    /**
     * SCN-010#A2 - None of the three series occurrences is persisted after the 409.
     */
    @Test
    void scn010A2OneOffOverlapLeavesNoSeriesOccurrenceRowsPersisted() throws Exception {
        Long resourceId = createRoom101();
        // Strictly inside the third requested occurrence (2026-04-05 09:00-09:30 Europe/Berlin = 07:00-07:30Z).
        reservationService.create(new BookingInput(resourceId, "Existing one-off",
                Instant.parse("2026-04-05T07:05:00Z"), Instant.parse("2026-04-05T07:25:00Z")), "seed");

        List<Reservation> baseline = storedReservations(resourceId);
        assertEquals(1, baseline.size(), "fixture must contain exactly the pre-existing one-off booking");
        assertNoSeriesRows(baseline);

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00",
                        3))
                .andExpect(status().isConflict());

        List<Reservation> afterConflict = storedReservations(resourceId);
        assertEquals(1, afterConflict.size(),
                "none of the three series occurrences may be persisted after the 409");
        assertNoSeriesRows(afterConflict);
        assertStoredStart(afterConflict, Instant.parse("2026-04-05T07:05:00Z"));
        assertNoStoredStart(afterConflict, Instant.parse("2026-03-22T08:00:00Z"));
        assertNoStoredStart(afterConflict, Instant.parse("2026-03-29T07:00:00Z"));
        assertNoStoredStart(afterConflict, Instant.parse("2026-04-05T07:00:00Z"));
    }

    /**
     * SCN-011#A2 - The three persisted records share one identical series identifier value.
     */
    @Test
    void scn011A2ThreeStoredRecordsShareOneIdenticalSeriesIdentifier() throws Exception {
        Long resourceId = createRoom101();

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-06-07T09:00:00+02:00",
                        "2026-06-07T09:30:00+02:00",
                        3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "the accepted series must persist exactly three occurrences");

        UUID storedSeriesId = stored.get(0).getSeriesId();
        assertNotNull(storedSeriesId, "the stored series identifier must be non-null");
        for (Reservation reservation : stored) {
            assertEquals(storedSeriesId, reservation.getSeriesId(),
                    "the three persisted records must share one identical series identifier");
        }
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

    private Long createRoom101() {
        String code = "R101-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        ResourceView room = resourceService.create(new ResourceInput(
                code, "Room 101", ResourceType.ROOM, "HQ", 1, 8, true, false));
        return room.id();
    }

    private List<Reservation> storedReservations(Long resourceId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        List<Reservation> stored = template.execute(status -> entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId", Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList());
        return stored == null ? List.of() : stored;
    }

    private static void assertNoSeriesRows(List<Reservation> stored) {
        assertTrue(stored.stream().noneMatch(reservation -> reservation.getSeriesId() != null),
                "no row created by a rejected series request may be persisted");
    }

    private static void assertNoStoredStart(List<Reservation> stored, Instant startsAt) {
        assertTrue(stored.stream().noneMatch(reservation -> startsAt.equals(reservation.getStartsAt())),
                "unexpected persisted occurrence starting at " + startsAt);
    }

    private static void assertStoredStart(List<Reservation> stored, Instant startsAt) {
        assertTrue(stored.stream().anyMatch(reservation -> startsAt.equals(reservation.getStartsAt())),
                "pre-existing reservation starting at " + startsAt + " must remain persisted");
    }

    /**
     * Deterministic clock fixture: the frozen acceptance window (March/April 2026) must be in
     * the future for the write path, independent of the machine clock.
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
