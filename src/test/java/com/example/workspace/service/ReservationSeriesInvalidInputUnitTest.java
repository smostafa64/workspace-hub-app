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
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit-layer proofs for SCN-006#A1, SCN-006#A2, SCN-009#A1 and SCN-010#A1 of the frozen
 * reservation-series contract (REQ-002, REQ-005).
 *
 * <p>Boundary: no Spring context, no HTTP server, no datasource. The application service
 * {@link ReservationService#createSeries(ReservationSeriesInput, String)} is driven directly with
 * mocked repository ports, so what executes is the in-process occurrences/duration validation and
 * the occurrence expansion. The observation is (a) the validation failure the service raises and
 * (b) the reservation entities it actually hands to the persistence port. For SCN-006#A2 the
 * observation is (a) the documented JSON payload failing to bind to the numeric occurrences field
 * through the same Jackson configuration used by the production request binding, and (b) an empty
 * persistence recorder, because no {@link ReservationSeriesInput} can exist for the service to act
 * on.
 *
 * <p>Every invalid partition is paired with a nearby valid control that differs only in the
 * property under test (occurrences 2 instead of 0, the numeric token 2 instead of the string
 * "twelve", a 16-minute occurrence instead of 14 minutes, a 7h59m occurrence instead of 9 hours),
 * so a pass cannot come from an unrelated guard such as the future-date, active-resource or
 * conflict rule. The controls use strictly interior values, never the 15-minute or 8-hour bounds
 * themselves (AMB-002 leaves their inclusivity unresolved) and never an instant inside a DST gap or
 * overlap (AMB-001).
 *
 * <p>The exact error body/wording is not frozen and is not asserted. SCN-009#A1 and SCN-010#A1
 * freeze only "rejected"; those two tests assert the documented client-error rejection class rather
 * than pinning an exact status code. SCN-006#A1 freezes status 400 and asserts
 * {@link HttpStatus#BAD_REQUEST} exactly.
 */
class ReservationSeriesInvalidInputUnitTest {

    private static final ZoneId REQUESTED_ZONE = ZoneId.of("Europe/Berlin");
    private static final long RESOURCE_ID = 1L;

    /** Frozen instant before every submitted occurrence, so the future-date rule never fires. */
    private static final String SERIES_START = "2031-09-01T09:00:00+02:00";

    /** Weekly 2031-09-01/09-08/09-15 at 09:00 local; all strictly after the fixed clock. */
    private static final int OCCURRENCES = 3;

    /** Documents the intended writable field shape for the negative partition name. */
    private static final String END_ONE_HOUR_LATER = "2031-09-01T10:00:00+02:00";

    private final Clock clock = Clock.fixed(Instant.parse("2031-06-01T00:00:00Z"), ZoneOffset.UTC);

    /** Mirrors the production Jackson binding used for the documented JSON payload (REQ-001). */
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Reservations the service offered to the persistence port, recorded by the mock's answer. */
    private final List<Reservation> persistedReservations = new ArrayList<>();

    private ReservationService service;
    private ReservationRepository bookings;
    private BookableResource room;

    @BeforeEach
    void setUp() {
        persistedReservations.clear();
        room = new BookableResource("ALPHA", "Conference Alpha", ResourceType.ROOM, "HQ", 2, 10, true, false);
        ReflectionTestUtils.setField(room, "id", RESOURCE_ID);
        bookings = reservationRepository();
        service = new ReservationService(resourceRepository(), bookings, mock(AuditRepository.class), clock);
    }

    /**
     * SCN-006#A1: occurrences=0 is outside the documented 2-12 range (REQ-002), so the service must
     * reject it with the application's 400 rejection and must not offer any reservation to the
     * persistence port. The control repeats the identical payload with occurrences=2 (the lower
     * documented bound) and requires that it is accepted and creates exactly two reservations.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-006-occurrences-zero")
    void zeroOccurrencesIsRejectedWithBadRequestAndPersistsNothing() throws Exception {
        ReservationSeriesInput zeroOccurrences = seriesInput(END_ONE_HOUR_LATER, 0);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.createSeries(zeroOccurrences, "alice"),
                "occurrences=0 is outside the documented 2-12 range (REQ-002) and must be rejected, "
                        + "but createSeries returned normally");
        assertEquals(HttpStatus.BAD_REQUEST, failure.status(),
                "occurrences=0 must be rejected with status 400 (SCN-006#A1), but the rejection "
                        + "carried status " + failure.status());
        assertNothingPersisted("occurrences=0");

        ReservationSeriesInput lowerBoundControl = seriesInput(END_ONE_HOUR_LATER, 2);
        ReservationSeriesView accepted = service.createSeries(lowerBoundControl, "alice");
        assertNotNull(accepted.reservations(),
                "the accepted occurrences=2 control must return its reservation views");
        assertEquals(2, accepted.reservations().size(),
                "the occurrences=2 control (identical payload, only the occurrences value differs) "
                        + "must be accepted and expand to 2 reservations, but returned "
                        + accepted.reservations().size() + " (starts: " + describeStarts() + ")");
        assertEquals(2, persistedReservations.size(),
                "the occurrences=2 control must create exactly 2 reservations, but "
                        + persistedReservations.size() + " were offered to the repository");
    }

    /**
     * SCN-006#A2: a non-numeric occurrences token cannot bind to the numeric occurrences field of
     * the documented JSON payload, so the request is rejected before any series is constructed and
     * zero reservations are persisted. The control is byte-identical except that occurrences is the
     * numeric token 2; it binds and the service then creates exactly two reservations, proving the
     * rejection is attributable to the token shape rather than to any other field.
     */
    @Test
    @Tag("observation_boundary=documented_json_payload_binding")
    @Tag("input_partition_id=SCN-006-occurrences-non-numeric-token")
    void nonNumericOccurrencesPayloadFailsBindingAndPersistsNothing() throws Exception {
        String nonNumericJson = payload(END_ONE_HOUR_LATER, "\"twelve\"");

        JsonMappingException failure = assertThrows(JsonMappingException.class,
                () -> objectMapper.readValue(nonNumericJson, ReservationSeriesInput.class),
                "a non-numeric occurrences value must be rejected by the documented payload "
                        + "binding instead of being coerced into a number");
        assertTrue(namesOccurrences(failure),
                "the binding failure must be caused by the occurrences field, but the reported path "
                        + "was " + failure.getPath() + " with message: " + failure.getOriginalMessage());
        assertNothingPersisted("non-numeric occurrences value");

        ReservationSeriesInput numericControl =
                objectMapper.readValue(payload(END_ONE_HOUR_LATER, "2"), ReservationSeriesInput.class);
        ReservationSeriesView accepted = service.createSeries(numericControl, "alice");
        assertNotNull(accepted.reservations(),
                "the accepted numeric-occurrences control must return its reservation views");
        assertEquals(2, accepted.reservations().size(),
                "the numeric occurrences=2 control (identical payload, only the token shape differs) "
                        + "must be accepted and expand to 2 reservations, but returned "
                        + accepted.reservations().size() + " (starts: " + describeStarts() + ")");
        assertEquals(2, persistedReservations.size(),
                "the numeric occurrences=2 control must create exactly 2 reservations, but "
                        + persistedReservations.size() + " were offered to the repository");
    }

    /**
     * SCN-009#A1: an occurrence spanning 14 minutes violates the documented 15-minute lower duration
     * bound (REQ-005) under either reading of an unresolved inclusive/exclusive bound (AMB-002), so
     * the whole series must be rejected and no occurrence may be persisted. The control spans 16
     * minutes (strictly inside the bound under either reading) and must be accepted, creating all
     * three requested reservations.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-009-duration-14-minutes")
    void occurrenceShorterThanFifteenMinutesIsRejectedAndPersistsNothing() throws Exception {
        ReservationSeriesInput fourteenMinutes =
                seriesInput("2031-09-01T09:14:00+02:00", OCCURRENCES);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.createSeries(fourteenMinutes, "alice"),
                "a 14-minute occurrence is shorter than the documented 15-minute minimum (REQ-005) "
                        + "and the series must be rejected, but createSeries returned normally");
        assertTrue(failure.status().is4xxClientError(),
                "the duration violation must be reported as a client error, but the rejection "
                        + "carried status " + failure.status());
        assertNothingPersisted("14-minute occurrence");

        ReservationSeriesInput sixteenMinuteControl =
                seriesInput("2031-09-01T09:16:00+02:00", OCCURRENCES);
        ReservationSeriesView accepted = service.createSeries(sixteenMinuteControl, "alice");
        assertNotNull(accepted.reservations(),
                "the accepted 16-minute control must return its reservation views");
        assertEquals(OCCURRENCES, accepted.reservations().size(),
                "the 16-minute control (identical payload, only the duration differs) must be "
                        + "accepted and expand to " + OCCURRENCES + " reservations, but returned "
                        + accepted.reservations().size() + " (starts: " + describeStarts() + ")");
        assertEquals(OCCURRENCES, persistedReservations.size(),
                "the 16-minute control must create exactly " + OCCURRENCES + " reservations, but "
                        + persistedReservations.size() + " were offered to the repository");
    }

    /**
     * SCN-010#A1: an occurrence spanning 9 hours violates the documented 8-hour upper duration bound
     * (REQ-005) under either reading of an unresolved inclusive/exclusive bound (AMB-002), so the
     * whole series must be rejected and no occurrence may be persisted. The control spans 7h59m
     * (strictly inside the bound under either reading) and must be accepted, creating all three
     * requested reservations.
     */
    @Test
    @Tag("observation_boundary=service_unit_mocked_repositories")
    @Tag("input_partition_id=SCN-010-duration-9-hours")
    void occurrenceLongerThanEightHoursIsRejectedAndPersistsNothing() throws Exception {
        ReservationSeriesInput nineHours = seriesInput("2031-09-01T18:00:00+02:00", OCCURRENCES);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.createSeries(nineHours, "alice"),
                "a 9-hour occurrence is longer than the documented 8-hour maximum (REQ-005) and "
                        + "the series must be rejected, but createSeries returned normally");
        assertTrue(failure.status().is4xxClientError(),
                "the duration violation must be reported as a client error, but the rejection "
                        + "carried status " + failure.status());
        assertNothingPersisted("9-hour occurrence");

        ReservationSeriesInput justUnderEightHours =
                seriesInput("2031-09-01T16:59:00+02:00", OCCURRENCES);
        ReservationSeriesView accepted = service.createSeries(justUnderEightHours, "alice");
        assertNotNull(accepted.reservations(),
                "the accepted 7h59m control must return its reservation views");
        assertEquals(OCCURRENCES, accepted.reservations().size(),
                "the 7h59m control (identical payload, only the duration differs) must be accepted "
                        + "and expand to " + OCCURRENCES + " reservations, but returned "
                        + accepted.reservations().size() + " (starts: " + describeStarts() + ")");
        assertEquals(OCCURRENCES, persistedReservations.size(),
                "the 7h59m control must create exactly " + OCCURRENCES + " reservations, but "
                        + persistedReservations.size() + " were offered to the repository");
    }

    /** Asserts the rejected attempt left the persistence port completely untouched. */
    private void assertNothingPersisted(String partition) {
        assertEquals(List.of(), new ArrayList<>(persistedReservations),
                "the rejected " + partition + " series must create zero reservations, but the "
                        + "service offered " + persistedReservations.size() + " reservation(s) to the "
                        + "persistence port: " + describeStarts());
        verify(bookings, never()).save(any(Reservation.class));
    }

    /** True when Jackson blames the occurrences field for the binding failure. */
    private boolean namesOccurrences(JsonMappingException failure) {
        for (JsonMappingException.Reference reference : failure.getPath()) {
            if ("occurrences".equals(reference.getFieldName())) {
                return true;
            }
        }
        String message = failure.getOriginalMessage();
        return message != null && message.contains("occurrences");
    }

    /** Builds the documented series payload through the same JSON binding the API uses (REQ-001). */
    private String payload(String endsAt, String occurrencesToken) {
        return "{\"resourceId\":" + RESOURCE_ID
                + ",\"title\":\"Weekly planning\""
                + ",\"startsAt\":\"" + SERIES_START + "\""
                + ",\"endsAt\":\"" + endsAt + "\""
                + ",\"timezone\":\"" + REQUESTED_ZONE.getId() + "\""
                + ",\"frequency\":\"WEEKLY\""
                + ",\"occurrences\":" + occurrencesToken + "}";
    }

    private ReservationSeriesInput seriesInput(String endsAt, int occurrences) throws Exception {
        return objectMapper.readValue(payload(endsAt, Integer.toString(occurrences)),
                ReservationSeriesInput.class);
    }

    /**
     * Resource port: every Optional-returning lookup resolves to the active meeting room without an
     * approval requirement, so the scenario preconditions hold.
     */
    private ResourceRepository resourceRepository() {
        return mock(ResourceRepository.class, invocation -> {
            if (Optional.class.equals(invocation.getMethod().getReturnType())) {
                return Optional.of(room);
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

    private String describeStarts() {
        List<String> starts = new ArrayList<>();
        for (Reservation reservation : persistedReservations) {
            starts.add(String.valueOf(reservation.getStartsAt()));
        }
        return starts.toString();
    }
}
