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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
 * Integration-layer verification of the recurring-series write path.
 *
 * <p>Each test drives the real HTTP entry point (POST /api/reservation-series through the
 * full Spring MVC + security + validation + service + JPA stack) inside a booted application
 * context backed by the real embedded datastore, and then observes the committed state
 * independently in a fresh transaction. No collaborator is mocked.</p>
 *
 * <p>Frozen Europe/Berlin 2026 expectations: 09:00 local = 08:00Z on 2026-03-22, 07:00Z on
 * 2026-03-29 and 2026-04-05; 02:30 on 2026-03-29 is nonexistent and resolves to 03:30 local
 * = 01:30Z; 02:30 on 2026-10-25 is ambiguous and resolves to 00:30Z.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalTime NINE_AM = LocalTime.of(9, 0);

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-001#A1 (integration) - The three persisted starts are the frozen UTC instants
     * 2026-03-22T08:00Z, 2026-03-29T07:00Z, 2026-04-05T07:00Z and each reads 09:00 in
     * Europe/Berlin.
     */
    @Test
    void scn001A1PersistedStartsAreFrozenInstantsAndRead0900Berlin() throws Exception {
        Long resourceId = createRoom("INT-001A1");

        mockMvc.perform(seriesRequest(resourceId, "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00", 3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "all three occurrences must be persisted by the accepted series");

        List<Instant> persistedStarts = stored.stream().map(Reservation::getStartsAt).toList();
        assertEquals(
                List.of(
                        Instant.parse("2026-03-22T08:00:00Z"),
                        Instant.parse("2026-03-29T07:00:00Z"),
                        Instant.parse("2026-04-05T07:00:00Z")),
                persistedStarts,
                "the persisted UTC starts must follow the frozen Europe/Berlin offset change");

        for (Reservation reservation : stored) {
            ZonedDateTime localStart = reservation.getStartsAt().atZone(BERLIN);
            assertEquals(NINE_AM, localStart.toLocalTime(),
                    "every persisted occurrence must read 09:00 local in Europe/Berlin, got " + localStart);
        }
    }

    /**
     * SCN-001#A3 (integration) - All three created records carry the same non-null series
     * identifier value, as observed through the persisted application state.
     */
    @Test
    void scn001A3AllThreePersistedRecordsCarrySameNonNullSeriesId() throws Exception {
        Long resourceId = createRoom("INT-001A3");

        mockMvc.perform(seriesRequest(resourceId, "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00", 3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "the accepted series must persist exactly three occurrences");

        UUID firstSeriesId = stored.get(0).getSeriesId();
        assertNotNull(firstSeriesId, "the persisted occurrences must carry a non-null series identifier");
        for (Reservation reservation : stored) {
            assertEquals(firstSeriesId, reservation.getSeriesId(),
                    "every persisted occurrence of the series must share the same series identifier value");
        }
    }

    /**
     * SCN-001#A4 (integration) - The three persisted occurrences are seven days apart in local
     * time, starting at 09:00 Europe/Berlin on 2026-03-22, 2026-03-29 and 2026-04-05.
     */
    @Test
    void scn001A4OccurrencesAreSevenLocalDaysApartAt0900() throws Exception {
        Long resourceId = createRoom("INT-001A4");

        mockMvc.perform(seriesRequest(resourceId, "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00", 3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "the weekly cadence must persist three occurrences");

        List<LocalDate> localDates = new ArrayList<>();
        for (Reservation reservation : stored) {
            ZonedDateTime localStart = reservation.getStartsAt().atZone(BERLIN);
            assertEquals(NINE_AM, localStart.toLocalTime(),
                    "each occurrence must start at 09:00 local time, got " + localStart);
            localDates.add(localStart.toLocalDate());
        }

        assertEquals(
                List.of(LocalDate.parse("2026-03-22"), LocalDate.parse("2026-03-29"),
                        LocalDate.parse("2026-04-05")),
                localDates,
                "the occurrences must fall on the consecutive local Sundays of the frozen window");

        for (int i = 1; i < localDates.size(); i++) {
            assertEquals(7, ChronoUnit.DAYS.between(localDates.get(i - 1), localDates.get(i)),
                    "consecutive occurrences must be exactly seven local days apart");
        }
    }

    /**
     * SCN-005#A2 (integration) - The spring-forward nonexistent occurrence is created and
     * kept rather than rejected or skipped.
     */
    @Test
    void scn005A2ShiftedSpringForwardOccurrenceIsPersisted() throws Exception {
        Long resourceId = createRoom("INT-005A2");

        mockMvc.perform(seriesRequest(resourceId, "2026-03-29T02:30:00+01:00",
                        "2026-03-29T04:00:00+02:00", 2))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(2, stored.size(),
                "neither the shifted occurrence nor the following week's occurrence may be dropped");

        List<Instant> persistedStarts = stored.stream().map(Reservation::getStartsAt).toList();
        assertTrue(persistedStarts.contains(Instant.parse("2026-03-29T01:30:00Z")),
                "the nonexistent local start must be kept as its shifted instant 2026-03-29T01:30:00Z, got "
                        + persistedStarts);
    }

    /**
     * SCN-007#A3 (integration) - An all-valid series with future starts, 60-minute duration and
     * an active room is created in full.
     */
    @Test
    void scn007A3AllValidSixtyMinuteSeriesIsCreatedInFull() throws Exception {
        Long resourceId = createRoom("INT-007A3");

        mockMvc.perform(seriesRequest(resourceId, "2030-04-14T10:00:00+02:00",
                        "2030-04-14T11:00:00+02:00", 3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "an all-valid 60-minute series must be created in full");

        for (Reservation reservation : stored) {
            assertEquals(Duration.ofMinutes(60),
                    Duration.between(reservation.getStartsAt(), reservation.getEndsAt()),
                    "every persisted occurrence of the accepted series must last exactly 60 minutes");
            assertTrue(reservation.getStartsAt().isAfter(Instant.parse("2026-03-01T00:00:00Z")),
                    "the accepted series must use future starts");
        }

        for (int i = 1; i < stored.size(); i++) {
            assertEquals(Duration.ofDays(7), Duration.between(stored.get(i - 1).getStartsAt(),
                            stored.get(i).getStartsAt()),
                    "consecutive occurrences of the weekly series must be stored seven days apart");
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

    private Long createRoom(String prefix) {
        String code = prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        ResourceView room = resourceService.create(new ResourceInput(
                code, "Room 101", ResourceType.ROOM, "HQ", 1, 8, true, false));
        assertNotNull(room.id(), "fixture room must be persisted with an identifier");
        fixtureResourceIds.add(room.id());
        return room.id();
    }

    /**
     * Reads the committed rows for one fixture resource in its own transaction, i.e. outside the
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
                .sorted(Comparator.comparing(Reservation::getStartsAt))
                .toList();
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
