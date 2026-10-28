package com.example.workspace.integration;

import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-layer verification that the real series write path persists PENDING occurrences for
 * an approval-required room (SCN-022, REQ-006).
 *
 * <p>The three-occurrence weekly series is created through the documented HTTP entry point
 * (POST /api/reservation-series through the full Spring MVC + security + validation + service +
 * JPA stack). The status under test is then read from the committed rows in a fresh transaction,
 * not from the HTTP response echo and not from a mocked collaborator, so the assertion is about
 * the stored status that the approval workflow depends on.</p>
 *
 * <p>The series starts 2030-04-21 10:00 Europe/Berlin, i.e. in the future relative to the fixed
 * test clock (2026-03-01T00:00Z). The fixture room is uniquely coded and requires approval.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesApprovalPendingIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";
    private static final String BERLIN = "Europe/Berlin";

    private static final String SERIES_START = "2030-04-21T10:00:00+02:00";
    private static final String SERIES_END = "2030-04-21T10:30:00+02:00";
    private static final int OCCURRENCES = 3;

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-022#A1 (integration) - Each persisted occurrence record of the approval-required room
     * carries status PENDING in the committed store.
     */
    @Test
    void scn022A1ApprovalRequiredRoomPersistsEveryOccurrenceAsPending() throws Exception {
        Long resourceId = createApprovalRequiredRoom("Room 201", 2);

        mockMvc.perform(seriesRequest(resourceId, SERIES_START, SERIES_END, OCCURRENCES))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = committedReservations(resourceId);
        assertEquals(OCCURRENCES, stored.size(),
                "the accepted three-occurrence series must persist three rows, but " + describe(stored));

        UUID seriesId = stored.get(0).getSeriesId();
        assertNotNull(seriesId,
                "the persisted occurrences must belong to the requested series, but the first row carries "
                        + "no series identifier");
        for (Reservation reservation : stored) {
            assertEquals(seriesId, reservation.getSeriesId(),
                    "the three persisted rows must be the occurrences of the one requested series");
        }

        for (Reservation reservation : stored) {
            assertEquals(ReservationStatus.PENDING, reservation.getStatus(),
                    "an occurrence persisted for an approval-required room must be stored as PENDING, "
                            + "but row " + reservation.getId() + " starting " + reservation.getStartsAt()
                            + " was stored as " + reservation.getStatus());
        }
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
                  "timezone": "%s",
                  "frequency": "WEEKLY",
                  "occurrences": %d
                }
                """.formatted(resourceId, startsAt, endsAt, BERLIN, occurrences);
        return post(SERIES_ENDPOINT)
                .with(user("alice").roles("EMPLOYEE", "MANAGER", "ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private Long createApprovalRequiredRoom(String name, int floorNumber) {
        String code = "R" + floorNumber + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        ResourceView room = resourceService.create(new ResourceInput(
                code, name, ResourceType.ROOM, "HQ", floorNumber, 8, true, true));
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
                .sorted(Comparator.comparing(Reservation::getStartsAt).thenComparing(Reservation::getId))
                .toList();
    }

    private static String describe(List<Reservation> reservations) {
        return reservations.stream()
                .map(reservation -> "[id=" + reservation.getId()
                        + ", startsAt=" + reservation.getStartsAt()
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
     * Deterministic clock fixture: the write window (2030-04-21) must be in the future for the
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
