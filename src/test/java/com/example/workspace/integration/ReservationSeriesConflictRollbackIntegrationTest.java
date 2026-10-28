package com.example.workspace.integration;

import com.example.workspace.api.BookingInput;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-layer verification of the rollback outcome of a series request that strictly
 * overlaps an existing reservation (SCN-019, REQ-004).
 *
 * <p>Each test seeds one reservation strictly inside the second requested occurrence through the
 * documented single-reservation write binding
 * {@link ReservationService#create(BookingInput, String)}, then POSTs the three-occurrence series
 * through the real HTTP entry point (POST /api/reservation-series through the full Spring MVC +
 * security + validation + service + JPA stack). The room's committed rows are read before and
 * after the 409 in fresh transactions, so the assertions are made against the storage the
 * transaction actually left behind, not against the response echo.</p>
 *
 * <p>Frozen Europe/Berlin 2026 window: the requested weekly series starts 09:00 local on
 * 2026-03-22 (08:00Z); the next occurrences are 09:00 local on 2026-03-29 and 2026-04-05 (07:00Z
 * each, after the DST switch). The pre-existing booking sits strictly inside the second
 * occurrence. All instants are in the future relative to the fixed test clock
 * (2026-03-01T00:00Z).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesConflictRollbackIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";

    private static final String SERIES_START = "2026-03-22T09:00:00+01:00";
    private static final String SERIES_END = "2026-03-22T09:30:00+01:00";
    private static final int OCCURRENCES = 3;

    /** The three requested occurrence starts in UTC. */
    private static final List<Instant> OCCURRENCE_STARTS = List.of(
            Instant.parse("2026-03-22T08:00:00Z"),
            Instant.parse("2026-03-29T07:00:00Z"),
            Instant.parse("2026-04-05T07:00:00Z"));

    /** Pre-existing booking strictly inside the second occurrence (07:00Z-07:30Z on 2026-03-29). */
    private static final Instant SEEDED_START = Instant.parse("2026-03-29T07:05:00Z");
    private static final Instant SEEDED_END = Instant.parse("2026-03-29T07:25:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-019#A1 (integration) - After the 409, no new reservation exists at any of the three
     * occurrence start instants of the requested series.
     */
    @Test
    void scn019A1AfterTheConflictNoReservationExistsAtAnyOccurrenceStart() throws Exception {
        Long resourceId = createRoom("Room 101", 1);
        seedStrictCollisionOnSecondOccurrence(resourceId);

        List<StoredRow> before = snapshot(committedReservations(resourceId));
        assertEquals(1, before.size(),
                "the fixture room must hold exactly the seeded reservation before the series request, "
                        + "but " + before);

        mockMvc.perform(seriesRequest(resourceId, SERIES_START, SERIES_END, OCCURRENCES))
                .andExpect(status().isConflict());

        List<Reservation> after = committedReservations(resourceId);
        assertEquals(1, after.size(),
                "the 409 must not leave any new reservation behind, but the room holds " + describe(after));

        for (Instant occurrenceStart : OCCURRENCE_STARTS) {
            assertTrue(after.stream().noneMatch(
                            reservation -> occurrenceStart.equals(reservation.getStartsAt())),
                    "after the 409 no new reservation may exist at the requested occurrence start "
                            + occurrenceStart + ", but " + describe(after));
        }

        assertTrue(after.stream().anyMatch(reservation -> SEEDED_START.equals(reservation.getStartsAt())),
                "the pre-existing reservation must remain persisted after the 409, but "
                        + describe(after));
    }

    /**
     * SCN-019#A2 (integration) - After the 409, the room's reservation set is identical to its
     * pre-request set, with no partially written occurrence.
     */
    @Test
    void scn019A2AfterTheConflictTheRoomReservationSetIsIdenticalToThePreRequestSet() throws Exception {
        Long resourceId = createRoom("Room 101", 1);
        seedStrictCollisionOnSecondOccurrence(resourceId);

        List<StoredRow> before = snapshot(committedReservations(resourceId));
        assertEquals(1, before.size(),
                "the fixture room must hold exactly the seeded reservation before the series request, "
                        + "but " + before);

        mockMvc.perform(seriesRequest(resourceId, SERIES_START, SERIES_END, OCCURRENCES))
                .andExpect(status().isConflict());

        List<Reservation> afterRows = committedReservations(resourceId);
        List<StoredRow> after = snapshot(afterRows);
        assertEquals(before, after,
                "after the 409 the room's reservation set must be identical to its pre-request set, "
                        + "with no partially written occurrence, but " + after + " differs from " + before);

        assertTrue(afterRows.stream().noneMatch(reservation -> reservation.getSeriesId() != null),
                "no row of the rejected series may be persisted, but the store holds series rows "
                        + describe(afterRows));
    }

    // ------------------------------------------------------------------
    // Fixtures and read helpers
    // ------------------------------------------------------------------

    /**
     * SCN-019 precondition: the room already holds a reservation strictly inside the second
     * requested occurrence (2026-03-29 09:00-09:30 Europe/Berlin = 07:00Z-07:30Z).
     */
    private void seedStrictCollisionOnSecondOccurrence(Long resourceId) {
        reservationService.create(new BookingInput(resourceId, "Existing reservation",
                SEEDED_START, SEEDED_END), "seed");
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
     * persistence context that served the writes, ordered by start instant.
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

    private static List<StoredRow> snapshot(List<Reservation> reservations) {
        return reservations.stream()
                .map(StoredRow::of)
                .sorted(Comparator.comparing(StoredRow::id))
                .collect(Collectors.toList());
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

    private record StoredRow(Long id, Instant startsAt, Instant endsAt, ReservationStatus status,
                             String bookedBy, UUID seriesId) {
        static StoredRow of(Reservation reservation) {
            return new StoredRow(reservation.getId(), reservation.getStartsAt(), reservation.getEndsAt(),
                    reservation.getStatus(), reservation.getBookedBy(), reservation.getSeriesId());
        }
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
     * Deterministic clock fixture: the frozen acceptance window (March/April 2026) must be in the
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
