package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.data.AuditRepository;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * Unit-layer proofs for SCN-011#A1 and SCN-012#A1 of the frozen reservation-series contract
 * (REQ-005): a past-dated first occurrence and an inactive resource must both reject the whole
 * series and leave zero reservations behind.
 *
 * <p>Boundary: no Spring context, no HTTP, no datasource, no network. The application service
 * {@link ReservationService#createSeries(ReservationSeriesInput, String)} is driven directly with
 * mocked persistence ports, so what executes is the in-process validation/expansion and the single
 * write attempt the service makes. The observation is (a) the rejection the service raises and
 * (b) the reservation entities it actually offers to the reservation repository port — the recorder
 * captures both single and batch saves, and a separate invocation scan covers every {@code save*}
 * method the port exposes, so a batch write cannot hide from the "zero reservations" assertion.
 *
 * <p>Both invalid partitions are paired with a nearby valid control that differs only in the
 * property under test: SCN-011 moves the series start from a past instant to the identical weekly
 * payload after the injected fixed clock; SCN-012 flips only the resource's {@code active} flag on
 * an otherwise byte-identical payload. A pass therefore cannot come from an unrelated guard
 * (occurrences range, duration bounds, approval rule or conflict rule). The controls use one-hour
 * occurrences, strictly interior to the 15-minute/8-hour window (AMB-002), and 09:00 local, which is
 * never inside a DST gap or overlap (AMB-001).
 *
 * <p>The clock is a fixed, injected instant rather than the wall clock, and the past start is
 * asserted to be before it, so the "past" partition is deterministic regardless of the machine
 * timezone and never expires.
 */
class ReservationSeriesRejectionUnitTest {

    private static final ZoneId REQUESTED_ZONE = ZoneId.of("Europe/Berlin");
    private static final long RESOURCE_ID = 1L;

    /** Injected fixed instant: the separation point between the "past" and "future" partitions. */
    private static final Instant FIXED_NOW = Instant.parse("2031-06-01T00:00:00Z");

    /** 2031-01-06T08:00Z — months before {@link #FIXED_NOW}, past in every timezone reading. */
    private static final String PAST_START = "2031-01-06T09:00:00+01:00";
    private static final String PAST_END = "2031-01-06T10:00:00+01:00";

    /** Identical weekly payload whose first occurrence is strictly after {@link #FIXED_NOW}. */
    private static final String FUTURE_START = "2031-09-01T09:00:00+02:00";
    private static final String FUTURE_END = "2031-09-01T10:00:00+02:00";

    private static final int OCCURRENCES = 3;

    private final Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    /** Mirrors the production Jackson binding used for the documented JSON payload (REQ-001). */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Reservations the service offered to the persistence port, recorded by the mock's answer. */
    private final List<Reservation> persistedReservations = new ArrayList<>();

    private ReservationService service;
    private ReservationRepository bookings;

    /** Reassigned per scenario/control; the resource port reads it at invocation time. */
    private BookableResource resource;

    @BeforeEach
    void setUp() {
        persistedReservations.clear();
        bookings = reservationRepository();
        service = new ReservationService(resourceRepository(), bookings, mock(AuditRepository.class), clock);
        resource = activeRoom();
    }

    /**
     * SCN-011#A1: a weekly series whose first occurrence starts before the injected current instant
     * violates the future-date rule (REQ-005) and the whole series must be rejected with zero
     * reservations. The control repeats the identical payload with only the start moved after the
     * fixed clock and must be accepted, creating all three reservations at the instants carried by
     * the payload offsets.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-011-past-dated-first-occurrence")
    void pastDatedSeriesIsRejectedAndCreatesZeroReservations() throws Exception {
        ReservationSeriesInput pastSeries = seriesInput(PAST_START, PAST_END, OCCURRENCES);

        assertTrue(OffsetDateTime.parse(PAST_START).toInstant().isBefore(clock.instant()),
                "fixture guard: the submitted first occurrence (" + PAST_START + ") must be strictly "
                        + "before the fixed current instant (" + clock.instant() + ") for the "
                        + "past-date partition to be exercised");

        ApiException failure = assertThrows(ApiException.class,
                () -> service.createSeries(pastSeries, "alice"),
                "a series starting at " + PAST_START + ", before the current instant " + clock.instant()
                        + ", must be rejected (REQ-005 future-date rule), but createSeries returned normally");
        assertTrue(failure.status().is4xxClientError(),
                "the past-dated series must be rejected as a client error, but the rejection carried "
                        + "status " + failure.status());
        assertZeroReservationWrites("past-dated series");

        ReservationSeriesInput futureControl = seriesInput(FUTURE_START, FUTURE_END, OCCURRENCES);
        ReservationSeriesView accepted = service.createSeries(futureControl, "alice");
        assertNotNull(accepted.reservations(),
                "the accepted future-dated control must return its reservation views");
        assertEquals(OCCURRENCES, accepted.reservations().size(),
                "the future-dated control (identical payload, only the start moved after the fixed "
                        + "clock) must be accepted with " + OCCURRENCES + " reservations, but returned "
                        + accepted.reservations().size());
        assertEquals(OCCURRENCES, persistedReservations.size(),
                "the future-dated control must create exactly " + OCCURRENCES + " reservations, but "
                        + persistedReservations.size() + " were offered to the repository");

        // Mapper fidelity: the parsed payload's offsets must reach persistence unchanged.
        assertEquals(expectedWeeklyInstants("2031-09-01T09:00:00+02:00"),
                persistedStartInstants(),
                "the persisted control occurrences must be the weekly wall-clock instants implied by "
                        + "the payload offsets (" + FUTURE_START + " = 2031-09-01T07:00:00Z and two "
                        + "further weekly occurrences), so a mapper that dropped the offset would be "
                        + "visible here");
    }

    /**
     * SCN-012#A1: a series submitted for an inactive resource violates the active-resource rule
     * (REQ-005) and the whole series must be rejected with zero reservations. The control repeats the
     * same payload for the same resource with only {@code active} flipped to {@code true} and must be
     * accepted, creating all three reservations.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-012-inactive-resource")
    void inactiveResourceSeriesIsRejectedAndCreatesZeroReservations() throws Exception {
        resource = inactiveRoom();
        assertFalse(resource.isActive(), "SCN-012 fixture guard: the looked-up resource must be inactive");

        ReservationSeriesInput series = seriesInput(FUTURE_START, FUTURE_END, OCCURRENCES);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.createSeries(series, "alice"),
                "a series for the inactive resource " + resource.getCode() + " must be rejected "
                        + "(REQ-005 active-resource rule), but createSeries returned normally");
        assertTrue(failure.status().is4xxClientError(),
                "the inactive-resource series must be rejected as a client error, but the rejection "
                        + "carried status " + failure.status());
        assertZeroReservationWrites("inactive-resource series");

        resource = activeRoom();
        assertTrue(resource.isActive(),
                "SCN-012 control guard: the control resource must be active");

        ReservationSeriesView accepted = service.createSeries(series, "alice");
        assertNotNull(accepted.reservations(),
                "the accepted active-resource control must return its reservation views");
        assertEquals(OCCURRENCES, accepted.reservations().size(),
                "the active-resource control (identical payload and resource identity, only the active "
                        + "flag differs) must be accepted with " + OCCURRENCES + " reservations, but "
                        + "returned " + accepted.reservations().size());
        assertEquals(OCCURRENCES, persistedReservations.size(),
                "the active-resource control must create exactly " + OCCURRENCES + " reservations, but "
                        + persistedReservations.size() + " were offered to the repository");
    }

    /**
     * Asserts the rejected attempt left the reservation persistence port write-free. Both the entity
     * recorder (single and batch answers) and every {@code save*} invocation on the mock are checked
     * independently, so an implementation that batches writes cannot satisfy the assertion vacuously.
     */
    private void assertZeroReservationWrites(String partition) {
        assertEquals(List.of(), new ArrayList<>(persistedReservations),
                "the rejected " + partition + " must create zero reservations, but the service offered "
                        + persistedReservations.size() + " reservation(s) to the persistence port");

        List<String> saveCalls = new ArrayList<>();
        for (Invocation invocation : mockingDetails(bookings).getInvocations()) {
            if (invocation.getMethod().getName().startsWith("save")) {
                StringBuilder call = new StringBuilder(invocation.getMethod().getName()).append('(');
                Object[] arguments = invocation.getArguments();
                for (int i = 0; i < arguments.length; i++) {
                    if (i > 0) {
                        call.append(", ");
                    }
                    call.append(arguments[i]);
                }
                saveCalls.add(call.append(')').toString());
            }
        }
        assertEquals(List.of(), saveCalls,
                "the rejected " + partition + " must not attempt any persistence save, but these calls "
                        + "reached the reservation port: " + saveCalls);
    }

    /** Instants of the recorded entities, inserted order, as a set to stay order-independent. */
    private Set<Instant> persistedStartInstants() {
        Set<Instant> instants = new LinkedHashSet<>();
        for (Reservation reservation : persistedReservations) {
            instants.add(reservation.getStartsAt());
        }
        return instants;
    }

    /** The three weekly instants implied by the payload offset (weekly wall-clock expansion). */
    private Set<Instant> expectedWeeklyInstants(String firstStart) {
        OffsetDateTime first = OffsetDateTime.parse(firstStart);
        Set<Instant> instants = new LinkedHashSet<>();
        for (int week = 0; week < OCCURRENCES; week++) {
            instants.add(first.plusWeeks(week).toInstant());
        }
        return instants;
    }

    private BookableResource activeRoom() {
        BookableResource room = new BookableResource("ALPHA", "Conference Alpha", ResourceType.ROOM,
                "HQ", 2, 10, true, false);
        ReflectionTestUtils.setField(room, "id", RESOURCE_ID);
        return room;
    }

    private BookableResource inactiveRoom() {
        BookableResource room = new BookableResource("OMEGA", "Retired Omega", ResourceType.ROOM,
                "HQ", 2, 10, false, false);
        ReflectionTestUtils.setField(room, "id", RESOURCE_ID);
        return room;
    }

    /** Builds the documented series payload through the same JSON binding the API uses (REQ-001). */
    private ReservationSeriesInput seriesInput(String startsAt, String endsAt, int occurrences)
            throws Exception {
        String json = "{\"resourceId\":" + RESOURCE_ID
                + ",\"title\":\"Weekly planning\""
                + ",\"startsAt\":\"" + startsAt + "\""
                + ",\"endsAt\":\"" + endsAt + "\""
                + ",\"timezone\":\"" + REQUESTED_ZONE.getId() + "\""
                + ",\"frequency\":\"WEEKLY\""
                + ",\"occurrences\":" + occurrences + "}";
        return objectMapper.readValue(json, ReservationSeriesInput.class);
    }

    /**
     * Resource port: every Optional-returning lookup resolves to the resource field at invocation
     * time, so the scenario's active/inactive precondition is what the service actually sees.
     */
    private ResourceRepository resourceRepository() {
        return mock(ResourceRepository.class, invocation -> {
            if (Optional.class.equals(invocation.getMethod().getReturnType())) {
                return Optional.of(resource);
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }

    /**
     * Reservation port: records every reservation the service persists (single or batch save) and
     * reports no conflicting reservations, keeping the recurring window conflict-free.
     */
    private ReservationRepository reservationRepository() {
        return mock(ReservationRepository.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            Object[] arguments = invocation.getArguments();
            if (methodName.startsWith("save") && arguments.length == 1) {
                if (arguments[0] instanceof Reservation) {
                    Reservation single = (Reservation) arguments[0];
                    persistedReservations.add(single);
                    return single;
                }
                if (arguments[0] instanceof Iterable) {
                    List<Reservation> batch = new ArrayList<>();
                    for (Object element : (Iterable<?>) arguments[0]) {
                        if (element instanceof Reservation) {
                            Reservation reservation = (Reservation) element;
                            persistedReservations.add(reservation);
                            batch.add(reservation);
                        }
                    }
                    return batch;
                }
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
    }
}
