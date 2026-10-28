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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration-layer verification of the atomic series write path and the stored series
 * identifier, covering SCN-009, SCN-010, SCN-011 and SCN-012.
 *
 * <p>Each test drives the real HTTP entry point (POST /api/reservation-series through the full
 * Spring MVC + security + validation + service + JPA stack) inside a booted application context
 * backed by the real embedded datastore. The resulting state is then observed independently in a
 * fresh transaction: the assertions that a rejected request wrote nothing, and that one series
 * identifier was stored, are made against the committed rows, never against the HTTP response
 * echo, the request DTO or a mocked collaborator.</p>
 *
 * <p>Frozen Europe/Berlin 2026 window used throughout: 09:00 local on 2026-03-22 is 08:00Z, and
 * 09:00 local on 2026-03-29 / 2026-04-05 is 07:00Z. The weekly series 2026-06-07 09:00+02:00 maps
 * to 07:00Z.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesAtomicityIntegrationTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";

    /** Requested three-occurrence series used by the conflict scenarios. */
    private static final String SERIES_START = "2026-03-22T09:00:00+01:00";
    private static final String SERIES_END = "2026-03-22T09:30:00+01:00";

    /** The three requested occurrence starts of that series in UTC. */
    private static final Instant FIRST_OCCURRENCE_START = Instant.parse("2026-03-22T08:00:00Z");
    private static final Instant SECOND_OCCURRENCE_START = Instant.parse("2026-03-29T07:00:00Z");
    private static final Instant THIRD_OCCURRENCE_START = Instant.parse("2026-04-05T07:00:00Z");

    /** Pre-existing booking strictly inside the second occurrence (07:00Z-07:30Z on 2026-03-29). */
    private static final Instant SCN009_CONFLICT_START = Instant.parse("2026-03-29T07:05:00Z");
    private static final Instant SCN009_CONFLICT_END = Instant.parse("2026-03-29T07:25:00Z");

    /** Pre-existing booking strictly inside the third occurrence (07:00Z-07:30Z on 2026-04-05). */
    private static final Instant SCN010_CONFLICT_START = Instant.parse("2026-04-05T07:05:00Z");
    private static final Instant SCN010_CONFLICT_END = Instant.parse("2026-04-05T07:25:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> fixtureResourceIds = new ArrayList<>();

    /**
     * SCN-009#A2 (integration) - After the 409 for a series whose second occurrence strictly
     * overlaps an existing reservation, the room holds zero new bookings from the request,
     * including the non-conflicting first and third occurrences.
     */
    @Test
    void scn009A2StrictlyOverlappingSecondOccurrencePersistsNoNewBooking() throws Exception {
        Long resourceId = createRoom("INT-009A2");
        reservationService.create(new BookingInput(resourceId, "Existing reservation",
                SCN009_CONFLICT_START, SCN009_CONFLICT_END), "seed");

        List<StoredRow> before = snapshot(storedReservations(resourceId));
        assertEquals(1, before.size(),
                "the fixture room must hold exactly the pre-existing reservation before the series request, but "
                        + before);
        assertNoSeriesRows(storedReservations(resourceId));

        mockMvc.perform(seriesRequest(resourceId, SERIES_START, SERIES_END, 3))
                .andExpect(status().isConflict());

        List<StoredRow> after = snapshot(storedReservations(resourceId));
        assertEquals(1, after.size(),
                "the rejected series must create zero new bookings, including the non-conflicting first and "
                        + "third occurrences, but the room holds " + after);
        assertEquals(before, after,
                "after the 409 the room's reservation set must be identical to its pre-request set, but "
                        + after + " differs from " + before);

        List<Reservation> storedRows = storedReservations(resourceId);
        assertNoSeriesRows(storedRows);
        assertNoStoredStart(storedRows, FIRST_OCCURRENCE_START);
        assertNoStoredStart(storedRows, SECOND_OCCURRENCE_START);
        assertNoStoredStart(storedRows, THIRD_OCCURRENCE_START);
        assertStoredStart(storedRows, SCN009_CONFLICT_START);
    }

    /**
     * SCN-010#A2 (integration) - After the 409 caused by a one-off booking strictly inside the
     * third requested occurrence, none of the three series occurrences is persisted.
     */
    @Test
    void scn010A2OneOffOverlapPersistsNoneOfTheThreeSeriesOccurrences() throws Exception {
        Long resourceId = createRoom("INT-010A2");
        reservationService.create(new BookingInput(resourceId, "Existing one-off",
                SCN010_CONFLICT_START, SCN010_CONFLICT_END), "seed");

        List<StoredRow> before = snapshot(storedReservations(resourceId));
        assertEquals(1, before.size(),
                "the fixture room must hold exactly the pre-existing one-off booking before the series "
                        + "request, but " + before);
        assertNoSeriesRows(storedReservations(resourceId));

        mockMvc.perform(seriesRequest(resourceId, SERIES_START, SERIES_END, 3))
                .andExpect(status().isConflict());

        List<StoredRow> after = snapshot(storedReservations(resourceId));
        assertEquals(1, after.size(),
                "none of the three series occurrences may be persisted after the 409, but the room holds "
                        + after);
        assertEquals(before, after,
                "after the 409 the room's reservation set must be identical to its pre-request set, but "
                        + after + " differs from " + before);

        List<Reservation> storedRows = storedReservations(resourceId);
        assertNoSeriesRows(storedRows);
        assertNoStoredStart(storedRows, FIRST_OCCURRENCE_START);
        assertNoStoredStart(storedRows, SECOND_OCCURRENCE_START);
        assertNoStoredStart(storedRows, THIRD_OCCURRENCE_START);
        assertStoredStart(storedRows, SCN010_CONFLICT_START);
    }

    /**
     * SCN-011#A2 (integration) - The three persisted records of one accepted series share one
     * identical, non-null series identifier value.
     */
    @Test
    void scn011A2ThreePersistedRecordsShareOneIdenticalSeriesIdentifier() throws Exception {
        Long resourceId = createRoom("INT-011A2");

        mockMvc.perform(seriesRequest(resourceId, "2026-06-07T09:00:00+02:00",
                        "2026-06-07T09:30:00+02:00", 3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(),
                "the accepted three-occurrence series must persist exactly three records, but stored "
                        + stored.size());

        UUID storedSeriesId = stored.get(0).getSeriesId();
        assertNotNull(storedSeriesId,
                "the three persisted records must carry a non-null series identifier, but the first row had none");

        assertTrue(stored.stream().allMatch(reservation -> storedSeriesId.equals(reservation.getSeriesId())),
                "the three persisted records must share one identical series identifier value, but stored "
                        + stored.stream().map(Reservation::getSeriesId).collect(Collectors.toList()));
    }

    /**
     * SCN-012#A2 (integration) - A reservation read returns the same series identifier that the
     * created occurrence rows carry.
     *
     * <p>The read goes through the application's reservation read path
     * ({@link ReservationService#list(Authentication, boolean)} returning the reservation read
     * model with its series identifier) in a fresh transaction, so the series identifier is
     * observed as the read model exposes it, not only as the raw column value.</p>
     */
    @Test
    void scn012A2ReservationReadReturnsTheStoredSeriesIdentifier() throws Exception {
        Long resourceId = createRoom("INT-012A2");

        mockMvc.perform(seriesRequest(resourceId, SERIES_START, SERIES_END, 3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(),
                "the accepted three-occurrence series must persist three rows, but stored " + stored.size());

        UUID seriesIdOnOccurrences = stored.get(0).getSeriesId();
        assertNotNull(seriesIdOnOccurrences,
                "the created occurrence rows must carry a non-null series identifier");

        Long readId = stored.get(0).getId();
        UUID seriesIdFromRead = readReservationSeriesId(readId);
        assertNotNull(seriesIdFromRead,
                "a reservation read must expose the series identifier stored on the occurrence row for "
                        + "reservation " + readId);
        assertEquals(seriesIdOnOccurrences, seriesIdFromRead,
                "a reservation read must return the same series identifier that the created occurrences carry");
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

    /**
     * Performs one reservation read through the application read path in a fresh transaction and
     * returns the series identifier that read model exposes for the requested reservation.
     */
    private UUID readReservationSeriesId(Long reservationId) {
        Authentication alice = new UsernamePasswordAuthenticationToken("alice", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"),
                        new SimpleGrantedAuthority("ROLE_MANAGER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")));
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        var views = template.execute(status -> reservationService.list(alice, true));
        assertNotNull(views, "the reservation read must return a result set");
        var readBack = views.stream()
                .filter(view -> reservationId.equals(view.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the reservation read must return the created reservation "
                        + reservationId + ", but returned " + views));
        return readBack.seriesId();
    }

    private static List<StoredRow> snapshot(List<Reservation> reservations) {
        return reservations.stream()
                .map(StoredRow::of)
                .collect(Collectors.toList());
    }

    private static void assertNoSeriesRows(List<Reservation> stored) {
        assertTrue(stored.stream().noneMatch(reservation -> reservation.getSeriesId() != null),
                "no row created by a rejected series request may be persisted, but stored "
                        + stored.stream().map(Reservation::getSeriesId).collect(Collectors.toList()));
    }

    private static void assertNoStoredStart(List<Reservation> stored, Instant startsAt) {
        assertTrue(stored.stream().noneMatch(reservation -> startsAt.equals(reservation.getStartsAt())),
                "no reservation may be persisted at the requested occurrence start " + startsAt);
    }

    private static void assertStoredStart(List<Reservation> stored, Instant startsAt) {
        assertTrue(stored.stream().anyMatch(reservation -> startsAt.equals(reservation.getStartsAt())),
                "the pre-existing reservation starting at " + startsAt + " must remain persisted");
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
     * Deterministic clock fixture: the frozen acceptance window (March-June 2026) must be in the
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
