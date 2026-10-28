package com.example.workspace.persistence;

import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Database-layer evidence for the recurring-series scenarios SCN-012, SCN-013, SCN-015 and SCN-019.
 *
 * <p>Each test drives the approved series write path (POST /api/reservation-series) and then
 * observes the committed store through a fresh JPA transaction. Rows are read from the database,
 * not from the HTTP response body or a mocked collaborator, so the assertions prove what the
 * release schema actually persisted.</p>
 *
 * <p>The deterministic clock keeps the March-June 2026 acceptance window in the future for the
 * write path independently of the machine clock.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesDatabaseLayerTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";

    /**
     * Start instants of the three occurrences produced by a weekly series that starts
     * 2026-03-22T09:00 Europe/Berlin. 2026-03-29 is the Europe/Berlin DST switch, so the
     * second and third local 09:00 correspond to 07:00Z (the seeded database test relies on
     * the same mapping).
     */
    private static final List<Instant> SCN019_OCCURRENCE_STARTS = List.of(
            Instant.parse("2026-03-22T08:00:00Z"),
            Instant.parse("2026-03-29T07:00:00Z"),
            Instant.parse("2026-04-05T07:00:00Z"));

    @Autowired private MockMvc mockMvc;
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ResourceService resourceService;
    @Autowired private ReservationService reservationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------
    // SCN-012 - the series identifier is stored on every occurrence row
    // ------------------------------------------------------------------

    /**
     * SCN-012#A1 - every created occurrence row of the series carries the same non-null seriesId.
     */
    @Test
    void scn012A1EveryStoredOccurrenceOfTheSeriesCarriesTheSameNonNullSeriesId() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00",
                        3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "the accepted three-occurrence series must persist three rows");

        UUID storedSeriesId = stored.get(0).getSeriesId();
        assertNotNull(storedSeriesId, "each persisted series occurrence must carry a non-null seriesId");
        for (Reservation reservation : stored) {
            assertEquals(storedSeriesId, reservation.getSeriesId(),
                    "every persisted occurrence of the series must carry the same seriesId value");
        }
    }

    /**
     * SCN-012#A2 - a reservation read returns the same seriesId that the created occurrences carry.
     */
    @Test
    void scn012A2ReservationReadFromTheDatabaseReturnsTheStoredSeriesId() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00",
                        3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "the accepted three-occurrence series must persist three rows");

        UUID seriesIdOnOccurrences = stored.get(0).getSeriesId();
        assertNotNull(seriesIdOnOccurrences, "the persisted occurrences must carry a non-null seriesId");

        Long readId = stored.get(0).getId();
        Reservation readBack = readReservationInFreshTransaction(readId);
        assertNotNull(readBack.getSeriesId(),
                "a reservation read must expose the seriesId stored on the occurrence row");
        assertEquals(seriesIdOnOccurrences, readBack.getSeriesId(),
                "a reservation read must return the same seriesId that the created occurrences carry");
    }

    // ------------------------------------------------------------------
    // SCN-013 - approval-required room persists PENDING occurrences
    // ------------------------------------------------------------------

    /**
     * SCN-013#A1 - every occurrence created for the approval-required room has status PENDING.
     */
    @Test
    void scn013A1ApprovalRequiredRoomStoresEveryOccurrenceAsPending() throws Exception {
        Long resourceId = createRoom("Room 201", 2, true);

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-04-12T10:00:00+02:00",
                        "2026-04-12T10:30:00+02:00",
                        3))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = storedReservations(resourceId);
        assertEquals(3, stored.size(), "the accepted three-occurrence series must persist three rows");
        for (Reservation reservation : stored) {
            assertEquals(ReservationStatus.PENDING, reservation.getStatus(),
                    "an occurrence of an approval-required room must be stored as PENDING");
        }
    }

    // ------------------------------------------------------------------
    // SCN-015 - two concurrent overlapping series cannot both be persisted
    // ------------------------------------------------------------------

    /**
     * SCN-015#A2 - reservation rows persisted by the two concurrent overlapping series belong to
     * at most one of the two series.
     *
     * <p>Both requests are identical, three-occurrence weekly series against the same free room and
     * are released simultaneously through two real servlet invocations, so the database layer sees
     * whichever transaction discipline the application actually applies.</p>
     */
    @Test
    void scn015A2ConcurrentOverlappingSeriesLeaveRowsOfAtMostOneSeries() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);
        int competitors = 2;

        ExecutorService pool = Executors.newFixedThreadPool(competitors);
        CountDownLatch ready = new CountDownLatch(competitors);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        List<String> aborted = new ArrayList<>();
        int completedResponses = 0;
        try {
            for (int i = 0; i < competitors; i++) {
                futures.add(pool.submit(() -> {
                    MockMvc concurrentMvc = concurrentMockMvc();
                    ready.countDown();
                    if (!startGate.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the concurrent start gate was not released");
                    }
                    MvcResult result = concurrentMvc.perform(seriesRequest(resourceId,
                                    "2026-05-10T09:00:00+02:00",
                                    "2026-05-10T09:30:00+02:00",
                                    3))
                            .andReturn();
                    return result.getResponse().getStatus();
                }));
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS), "both concurrent callers must reach the start gate");
            startGate.countDown();

            for (Future<Integer> future : futures) {
                try {
                    int status = future.get(60, TimeUnit.SECONDS);
                    completedResponses++;
                    assertTrue(status > 0, "a concurrent series request must produce an HTTP response");
                } catch (ExecutionException failure) {
                    // The losing caller's response is not specified by the contract (AMB-006); the
                    // database read below remains the authority for what was committed.
                    aborted.add(String.valueOf(failure.getCause()));
                }
            }
        } finally {
            pool.shutdownNow();
        }

        List<Reservation> stored = storedReservations(resourceId);
        Set<UUID> distinctSeriesIds = stored.stream()
                .map(Reservation::getSeriesId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        assertTrue(distinctSeriesIds.size() <= 1,
                "persisted reservation rows must belong to at most one of the two concurrent series, "
                        + "but rows for " + distinctSeriesIds + " were stored (statuses observed: "
                        + completedResponses + " response(s), aborted attempts: " + aborted + ")");
        assertTrue(stored.isEmpty() || stored.size() == 3,
                "the write path must persist a whole three-occurrence series or nothing, but stored "
                        + stored.size() + " row(s)");
        assertTrue(completedResponses >= 1,
                "at least one concurrent request must complete with an HTTP response to observe the race; "
                        + "aborted attempts: " + aborted);
        assertTrue(stored.stream().allMatch(reservation -> reservation.getSeriesId() != null),
                "every row persisted by the two series requests must belong to a series");
    }

    // ------------------------------------------------------------------
    // SCN-019 - a rejected series commits no partial row
    // ------------------------------------------------------------------

    /**
     * SCN-019#A1 - after the 409, no new reservation exists at any of the three occurrence start
     * instants.
     */
    @Test
    void scn019A1AfterConflictNoReservationExistsAtAnyOccurrenceStartInstant() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);
        seedStrictCollisionOnSecondOccurrence(resourceId);

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00",
                        3))
                .andExpect(status().isConflict());

        List<Reservation> stored = storedReservations(resourceId);
        for (Instant occurrenceStart : SCN019_OCCURRENCE_STARTS) {
            assertTrue(stored.stream().noneMatch(reservation -> occurrenceStart.equals(reservation.getStartsAt())),
                    "after the 409 no reservation may exist at the requested occurrence start " + occurrenceStart);
        }
    }

    /**
     * SCN-019#A2 - after the 409 the room's reservation set is identical to its pre-request set,
     * with no partially written occurrence.
     */
    @Test
    void scn019A2AfterConflictTheRoomReservationSetIsIdenticalToThePreRequestSet() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);
        seedStrictCollisionOnSecondOccurrence(resourceId);

        List<StoredRow> before = snapshot(storedReservations(resourceId));
        assertEquals(1, before.size(), "the fixture must contain exactly the pre-existing reservation");

        mockMvc.perform(seriesRequest(resourceId,
                        "2026-03-22T09:00:00+01:00",
                        "2026-03-22T09:30:00+01:00",
                        3))
                .andExpect(status().isConflict());

        List<StoredRow> after = snapshot(storedReservations(resourceId));
        assertEquals(before, after,
                "after the 409 the room's reservation set must be identical to its pre-request set, "
                        + "with no partially written occurrence");
    }

    // ------------------------------------------------------------------
    // Fixtures and database helpers
    // ------------------------------------------------------------------

    /**
     * SCN-019 precondition: Room 101 already holds a reservation strictly inside the second
     * requested occurrence (2026-03-29 09:00-09:30 Europe/Berlin = 07:00-07:30Z).
     */
    private void seedStrictCollisionOnSecondOccurrence(Long resourceId) {
        reservationService.create(new BookingInput(resourceId, "Existing reservation",
                Instant.parse("2026-03-29T07:05:00Z"), Instant.parse("2026-03-29T07:25:00Z")), "seed");
    }

    private Long createRoom(String name, int floorNumber, boolean approvalRequired) {
        String code = "R" + floorNumber + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        ResourceView room = resourceService.create(new ResourceInput(
                code, name, ResourceType.ROOM, "HQ", floorNumber, 8, true, approvalRequired));
        return room.id();
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

    private MockMvc concurrentMockMvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private List<Reservation> storedReservations(Long resourceId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        List<Reservation> stored = template.execute(status -> entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId", Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList());
        return stored == null ? List.of() : stored;
    }

    private Reservation readReservationInFreshTransaction(Long id) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Reservation read = template.execute(status -> entityManager.find(Reservation.class, id));
        assertNotNull(read, "the reservation must be readable from the database");
        return read;
    }

    private static List<StoredRow> snapshot(List<Reservation> reservations) {
        return reservations.stream()
                .map(StoredRow::of)
                .sorted(Comparator.comparing(StoredRow::id))
                .toList();
    }

    private record StoredRow(Long id, Instant startsAt, Instant endsAt, ReservationStatus status,
                             String bookedBy, UUID seriesId) {
        static StoredRow of(Reservation reservation) {
            return new StoredRow(reservation.getId(), reservation.getStartsAt(), reservation.getEndsAt(),
                    reservation.getStatus(), reservation.getBookedBy(), reservation.getSeriesId());
        }
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
