package com.example.workspace.integration;

import com.example.workspace.api.ReservationView;
import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-layer verification that a single occurrence of an existing series can still be
 * cancelled while the remaining occurrences stay booked (SCN-014).
 *
 * <p>The confirmed three-occurrence series is created through the documented HTTP entry point
 * (POST /api/reservation-series through the full Spring MVC + security + validation + service +
 * JPA stack). The cancellation is then driven through the application's existing cancellation
 * write path binding {@link ReservationService#cancel(Long, Authentication)}, and the resulting
 * state is observed independently in a fresh transaction against the committed store. No
 * collaborator is mocked, so the changed state is the real persisted reservation state rather
 * than a response echo or a stub.</p>
 *
 * <p>The series is placed in the future relative to the frozen clock (2030-04-14 10:00
 * Europe/Berlin = 08:00Z), and every fixture room is uniquely coded so tests never share rows.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesCancellationIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";

    /** Weekly series on a room that does not require approval, so every occurrence is CONFIRMED. */
    private static final String SERIES_START = "2030-04-14T10:00:00+02:00";
    private static final String SERIES_END = "2030-04-14T10:30:00+02:00";
    private static final int OCCURRENCES = 3;

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-014#A1 (integration) - Cancelling exactly one occurrence of a confirmed three-occurrence
     * series leaves that occurrence CANCELLED and the other two occurrences still booked
     * (CONFIRMED) in the committed store.
     */
    @Test
    void scn014A1CancellingOneOccurrenceLeavesOtherOccurrencesBooked() throws Exception {
        Long resourceId = createRoom("INT-014A1");

        mockMvc.perform(seriesRequest(resourceId, SERIES_START, SERIES_END, OCCURRENCES))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> created = storedReservations(resourceId);
        assertEquals(OCCURRENCES, created.size(),
                "the accepted series must persist exactly three occurrences, but stored " + created.size());
        for (Reservation reservation : created) {
            assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus(),
                    "the precondition requires a confirmed series, but occurrence "
                            + reservation.getId() + " is " + reservation.getStatus());
        }

        Reservation targeted = created.get(1);
        Long targetedId = targeted.getId();
        assertNotNull(targetedId, "the targeted occurrence must have been persisted with an identifier");
        Instant targetedStart = targeted.getStartsAt();

        ReservationView cancelled = reservationService.cancel(targetedId, alice());
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "cancelling one occurrence must report that occurrence as CANCELLED, but reported "
                        + cancelled.status());

        List<Reservation> after = storedReservations(resourceId);
        assertEquals(OCCURRENCES, after.size(),
                "cancelling one occurrence must not delete any series row, but stored " + after.size());

        Reservation reread = after.stream()
                .filter(reservation -> targetedId.equals(reservation.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the cancelled occurrence must remain persisted, but stored ids are " + ids(after)));
        assertEquals(ReservationStatus.CANCELLED, reread.getStatus(),
                "the targeted occurrence " + targetedId + " must be CANCELLED in the committed store, but is "
                        + reread.getStatus());
        assertEquals(targetedStart, reread.getStartsAt(),
                "the cancelled occurrence must keep its original start instant");

        List<Reservation> otherOccurrences = after.stream()
                .filter(reservation -> !targetedId.equals(reservation.getId()))
                .toList();
        assertEquals(OCCURRENCES - 1, otherOccurrences.size(),
                "the series must still hold the two non-targeted occurrences after cancelling one");
        for (Reservation reservation : otherOccurrences) {
            assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus(),
                    "every other occurrence of the series must remain booked, but occurrence "
                            + reservation.getId() + " is " + reservation.getStatus());
        }

        assertEquals(1, after.stream()
                        .filter(reservation -> reservation.getStatus() == ReservationStatus.CANCELLED)
                        .count(),
                "only the targeted occurrence may change state, but the committed store contains "
                        + after.stream().map(Reservation::getStatus).collect(Collectors.toList()));
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

    private Authentication alice() {
        return new UsernamePasswordAuthenticationToken("alice", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"),
                        new SimpleGrantedAuthority("ROLE_MANAGER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    /**
     * Reads the committed rows for one fixture resource in their own transaction, i.e. outside
     * the persistence context that served the writes, ordered by start instant.
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

    private static List<Long> ids(List<Reservation> reservations) {
        return reservations.stream().map(Reservation::getId).collect(Collectors.toList());
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
