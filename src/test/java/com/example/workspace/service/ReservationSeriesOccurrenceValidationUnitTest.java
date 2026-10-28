package com.example.workspace.service;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.api.ReservationView;
import com.example.workspace.data.AuditRepository;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Application-unit verification of the per-occurrence validation rules of the recurring-series
 * write path, observed at the documented service boundary
 * {@link ReservationService#createSeries(ReservationSeriesInput, String)} (SCN-007, SCN-008,
 * REQ-003, REQ-011).
 *
 * <p>Requests are materialised from the documented wire payload through the same Jackson binding
 * the application uses for the request body, so no DTO constructor shape is invented. The
 * repositories are isolated Mockito collaborators: {@code ResourceRepository} supplies the fixture
 * room, {@code ReservationRepository.save} is the unit persistence boundary and echoes the saved
 * entity, and no conflict exists. Two fixed clocks are used: 2026-03-01T00:00:00Z for the future
 * duration-boundary windows and 2026-03-10T00:00:00Z for the past-start series.</p>
 *
 * <p>Frozen expectations exercised here:</p>
 * <ul>
 *   <li>SCN-007: durations of exactly 15 minutes and exactly 8 hours are inclusive and accepted;
 *       14 minutes and 8 hours 1 minute each fail duration validation.</li>
 *   <li>SCN-008: an inactive resource fails every occurrence series-wide with no persistence; an
 *       occurrence whose start is not in the future is skipped while the later future occurrence
 *       of the same series is persisted.</li>
 * </ul>
 */
class ReservationSeriesOccurrenceValidationUnitTest {

    private static final Long ROOM_101_ID = 101L;
    private static final Long INACTIVE_ROOM_ID = 999L;
    private static final String BERLIN = "Europe/Berlin";

    private static final Clock FUTURE_CLOCK =
            Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
    /**
     * Request-time clock for the past-start series: after the first and the second weekly
     * occurrence, before the third.
     */
    private static final Clock PAST_SERIES_CLOCK =
            Clock.fixed(Instant.parse("2026-03-10T00:00:00Z"), ZoneOffset.UTC);

    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

    /** Weekly 10:00-11:00 Europe/Berlin series; occurrence 3 (2026-03-15) is still in the future. */
    private static final String PAST_SERIES_START = "2026-03-01T10:00:00+01:00";
    private static final String PAST_SERIES_END = "2026-03-01T11:00:00+01:00";
    private static final Instant FIRST_PAST_START = Instant.parse("2026-03-01T09:00:00Z");
    private static final Instant SECOND_PAST_START = Instant.parse("2026-03-08T09:00:00Z");
    private static final Instant FUTURE_OCCURRENCE_START = Instant.parse("2026-03-15T09:00:00Z");

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);

    private ReservationService service;

    @BeforeEach
    void setUp() {
        reset(resources, bookings, audits);
        service = serviceWith(FUTURE_CLOCK);

        BookableResource room101 =
                new BookableResource("R101", "Room 101", ResourceType.ROOM, "HQ", 1, 8, true, false);
        ReflectionTestUtils.setField(room101, "id", ROOM_101_ID);
        when(resources.findLockedById(ROOM_101_ID)).thenReturn(Optional.of(room101));
        when(resources.findById(ROOM_101_ID)).thenReturn(Optional.of(room101));
        when(bookings.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * SCN-007#A1 - Occurrences lasting exactly 15 minutes and exactly 8 hours pass validation and
     * are created.
     */
    @Test
    void scn007A1ExactFifteenMinuteAndEightHourDurationsAreAccepted() {
        ReservationSeriesView fifteenMinutes = service.createSeries(
                input(ROOM_101_ID, "2030-05-06T10:00:00+02:00", "2030-05-06T10:15:00+02:00", 3),
                "alice");
        assertNotNull(fifteenMinutes, "the 15-minute boundary series must be created");
        assertEquals(3, fifteenMinutes.reservations().size(),
                "the 15-minute boundary series must create all three requested occurrences, but it "
                        + "returned " + describe(fifteenMinutes));
        for (ReservationView reservation : fifteenMinutes.reservations()) {
            assertEquals(Duration.ofMinutes(15),
                    Duration.between(reservation.startsAt(), reservation.endsAt()),
                    "an occurrence of exactly 15 minutes is within the inclusive lower bound and must "
                            + "be accepted, but the occurrence lasted "
                            + Duration.between(reservation.startsAt(), reservation.endsAt()));
        }
        assertEquals(3, savedReservations().size(),
                "all three 15-minute occurrences must be persisted at the unit persistence boundary, "
                        + "but the saved series was " + savedStarts());

        ReservationSeriesView eightHours = service.createSeries(
                input(ROOM_101_ID, "2030-05-07T10:00:00+02:00", "2030-05-07T18:00:00+02:00", 2),
                "alice");
        assertNotNull(eightHours, "the 8-hour boundary series must be created");
        assertEquals(2, eightHours.reservations().size(),
                "the 8-hour boundary series must create both requested occurrences, but it returned "
                        + describe(eightHours));
        for (ReservationView reservation : eightHours.reservations()) {
            assertEquals(Duration.ofHours(8),
                    Duration.between(reservation.startsAt(), reservation.endsAt()),
                    "an occurrence of exactly 8 hours is within the inclusive upper bound and must be "
                            + "accepted, but the occurrence lasted "
                            + Duration.between(reservation.startsAt(), reservation.endsAt()));
        }
        assertEquals(5, savedReservations().size(),
                "both boundary series must be persisted in full (3 + 2 occurrences), but the saved "
                        + "series was " + savedStarts());
    }

    /**
     * SCN-007#A2 - A 14-minute occurrence and an 8-hour-1-minute occurrence each fail duration
     * validation, and no occurrence is persisted.
     */
    @Test
    void scn007A2FourteenMinutesAndEightHoursOneMinuteAreRejected() {
        assertDurationRejected("a 14-minute occurrence",
                "2030-05-06T10:00:00+02:00", "2030-05-06T10:14:00+02:00");
        assertDurationRejected("an 8-hour-1-minute occurrence",
                "2030-05-06T10:00:00+02:00", "2030-05-06T18:01:00+02:00");

        verify(bookings, never()).save(any(Reservation.class));
        assertTrue(savedReservations().isEmpty(),
                "neither an under-15-minute nor an over-8-hour series may persist an occurrence, but "
                        + "the saved series was " + savedStarts());
    }

    /**
     * SCN-008#A1 - Because the series carries a single resourceId, the inactive room makes every
     * occurrence fail non-conflict validation series-wide: the request is rejected with 400, no
     * reservation persists and the persisted list is empty.
     */
    @Test
    void scn008A1InactiveResourceFailsEveryOccurrenceSeriesWide() {
        BookableResource inactive =
                new BookableResource("R999", "Room 999", ResourceType.ROOM, "HQ", 9, 8, false, false);
        ReflectionTestUtils.setField(inactive, "id", INACTIVE_ROOM_ID);
        when(resources.findLockedById(INACTIVE_ROOM_ID)).thenReturn(Optional.of(inactive));
        when(resources.findById(INACTIVE_ROOM_ID)).thenReturn(Optional.of(inactive));

        ApiException error = assertThrows(ApiException.class,
                () -> service.createSeries(input(INACTIVE_ROOM_ID,
                        "2030-05-06T10:00:00+02:00", "2030-05-06T11:00:00+02:00", 3), "alice"),
                "a series whose single resourceId is the inactive room-999 must fail series-wide");
        assertEquals(HttpStatus.BAD_REQUEST, error.status(),
                "an inactive resource is a non-conflict validation failure and must be answered with "
                        + "400, but the status was " + error.status());

        verify(bookings, never()).save(any(Reservation.class));
        assertTrue(savedReservations().isEmpty(),
                "an inactive resource must leave the persisted list empty, but the saved series was "
                        + savedStarts());
    }

    /**
     * SCN-008#A2 - Occurrences whose start is not in the future are skipped while the remaining
     * valid occurrence of the same series is still created.
     */
    @Test
    void scn008A2PastStartOccurrencesAreSkippedWhileTheValidOccurrenceIsCreated() {
        ReservationService pastSeriesService = serviceWith(PAST_SERIES_CLOCK);

        List<Instant> persisted = persistedStartsAfterSeries(pastSeriesService,
                PAST_SERIES_START, PAST_SERIES_END, 3, "the past-start series");

        assertEquals(List.of(FUTURE_OCCURRENCE_START), persisted,
                "the two occurrences starting in the past (" + FIRST_PAST_START + " and "
                        + SECOND_PAST_START + ") must be skipped, leaving only the later future "
                        + "occurrence " + FUTURE_OCCURRENCE_START + ", but the persisted starts were "
                        + persisted);
    }

    /**
     * SCN-008#A3 - A start that is not in the future at request time is skipped while a later
     * occurrence of the same series with a future start is persisted.
     */
    @Test
    void scn008A3PastSecondOccurrenceIsSkippedWhileTheLaterFutureOccurrenceIsPersisted() {
        ReservationService pastSeriesService = serviceWith(PAST_SERIES_CLOCK);

        List<Instant> persisted = persistedStartsAfterSeries(pastSeriesService,
                PAST_SERIES_START, PAST_SERIES_END, 3, "the past-start series");

        assertFalse(persisted.contains(SECOND_PAST_START),
                "the second requested occurrence, starting " + SECOND_PAST_START
                        + ", is already in the past at request time and must be skipped, but it was "
                        + "persisted: " + persisted);
        assertTrue(persisted.contains(FUTURE_OCCURRENCE_START),
                "the later occurrence of the same series, starting " + FUTURE_OCCURRENCE_START
                        + ", is in the future at request time and must be persisted, but the persisted "
                        + "starts were " + persisted);
        assertEquals(1, persisted.size(),
                "only the later future occurrence may be persisted for this past-start series, but the "
                        + "persisted starts were " + persisted);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ReservationService serviceWith(Clock clock) {
        return new ReservationService(resources, bookings, audits, clock);
    }

    private void assertDurationRejected(String label, String startsAt, String endsAt) {
        ApiException error = assertThrows(ApiException.class,
                () -> service.createSeries(input(ROOM_101_ID, startsAt, endsAt, 3), "alice"),
                label + " is outside the inclusive 15-minute to 8-hour duration bounds and must fail "
                        + "duration validation");
        assertEquals(HttpStatus.BAD_REQUEST, error.status(),
                label + " is an invalid occurrence and must be answered with 400, but the status was "
                        + error.status());
    }

    /**
     * Drives the series request and returns the occurrence starts that reached the unit persistence
     * boundary. SCN-008#A2/#A3 constrain which occurrences exist, not the response envelope: the
     * documented non-conflict partial outcome (REQ-011) is a 400, so when the service raises the
     * documented {@link ApiException} its status is validated; a fully successful return is equally
     * consistent with the frozen occurrence-set assertion. Any other exception escapes the test.
     */
    private List<Instant> persistedStartsAfterSeries(ReservationService seriesService,
                                                     String startsAt, String endsAt, int occurrences,
                                                     String label) {
        try {
            seriesService.createSeries(input(ROOM_101_ID, startsAt, endsAt, occurrences), "alice");
        } catch (ApiException documentedOutcome) {
            assertEquals(HttpStatus.BAD_REQUEST, documentedOutcome.status(),
                    label + " reports skipped occurrences; the documented non-conflict outcome for a "
                            + "partial series is 400, but the status was "
                            + documentedOutcome.status());
        }
        return savedReservations().stream()
                .map(Reservation::getStartsAt)
                .sorted()
                .toList();
    }

    /**
     * Reads every entity handed to the unit persistence boundary, in call order, without imposing
     * an invocation count (a skipped series may legitimately have saved nothing).
     */
    private List<Reservation> savedReservations() {
        List<Reservation> saved = new ArrayList<>();
        for (Invocation invocation : mockingDetails(bookings).getInvocations()) {
            if ("save".equals(invocation.getMethod().getName())
                    && invocation.getArguments().length == 1
                    && invocation.getArgument(0) instanceof Reservation reservation) {
                saved.add(reservation);
            }
        }
        return saved;
    }

    private String savedStarts() {
        return savedReservations().stream()
                .map(reservation -> "[" + reservation.getStartsAt() + "]")
                .toList()
                .toString();
    }

    /**
     * Materialises the documented series request body through the application's JSON binding, so no
     * DTO constructor shape beyond the frozen wire contract is assumed.
     */
    private static ReservationSeriesInput input(Long resourceId, String startsAt, String endsAt,
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
        try {
            return JSON.readValue(body, ReservationSeriesInput.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("the documented series body could not be bound: " + body,
                    exception);
        }
    }

    private static String describe(ReservationSeriesView series) {
        return series.reservations().stream()
                .map(reservation -> "[startsAt=" + reservation.startsAt()
                        + ", endsAt=" + reservation.endsAt() + "]")
                .toList()
                .toString();
    }
}
