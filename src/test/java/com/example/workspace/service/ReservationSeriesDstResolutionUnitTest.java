package com.example.workspace.service;

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
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Application-unit verification of the frozen Europe/Berlin DST boundary resolutions for the
 * recurring-series write rules, observed at the documented service boundary
 * {@link ReservationService#createSeries(ReservationSeriesInput, String)} (SCN-006, SCN-026,
 * REQ-002, C1).
 *
 * <p>The request bodies are the documented wire payloads, materialised through the same Jackson
 * binding the application uses for the request body, so neither the DTO constructor nor the
 * fixture shape is invented. The repositories are isolated Mockito collaborators (an active, free
 * Room 101, no conflicts, save echoes the entity) and the clock is fixed at 2026-03-01T00:00:00Z,
 * so every fixture window is in the future.</p>
 *
 * <p>Frozen expectations exercised here:</p>
 * <ul>
 *   <li>SCN-006: the ambiguous local start 2026-10-25T02:30 Europe/Berlin resolves to the earlier
 *       UTC instant 2026-10-25T00:30:00Z, not 2026-10-25T01:30:00Z.</li>
 *   <li>SCN-026: the nonexistent local end 2026-03-29T02:30 shifts forward by the gap to 03:30
 *       local = 2026-03-29T01:30:00Z; the ambiguous local end 2026-10-25T02:30 resolves to the
 *       earlier instant 2026-10-25T00:30:00Z. Both occurrences stay valid with a 90-minute
 *       duration.</li>
 * </ul>
 */
class ReservationSeriesDstResolutionUnitTest {

    private static final Long ROOM_101_ID = 101L;
    private static final String BERLIN = "Europe/Berlin";
    private static final ZoneId BERLIN_ZONE = ZoneId.of(BERLIN);

    /** Frozen 2026-03-01 test clock: every fixture window below is in the future. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = Jackson2ObjectMapperBuilder.json().build();

    private static final Instant LATER_AMBIGUOUS_INSTANT = Instant.parse("2026-10-25T01:30:00Z");

    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository bookings = mock(ReservationRepository.class);
    private final AuditRepository audits = mock(AuditRepository.class);

    private ReservationService service;

    @BeforeEach
    void setUp() {
        reset(resources, bookings, audits);
        service = new ReservationService(resources, bookings, audits, CLOCK);

        BookableResource room101 =
                new BookableResource("R101", "Room 101", ResourceType.ROOM, "HQ", 1, 8, true, false);
        ReflectionTestUtils.setField(room101, "id", ROOM_101_ID);
        when(resources.findLockedById(ROOM_101_ID)).thenReturn(Optional.of(room101));
        when(resources.findById(ROOM_101_ID)).thenReturn(Optional.of(room101));
        when(bookings.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * SCN-006#A1 - The ambiguous local start 2026-10-25T02:30 Europe/Berlin resolves to the earlier
     * UTC instant 2026-10-25T00:30:00Z, not the later 2026-10-25T01:30:00Z.
     */
    @Test
    void scn006A1AmbiguousFallBackStartResolvesToTheEarlierInstant0030Z() {
        ReservationSeriesView series = createSeries(
                "2026-10-25T02:30:00+02:00",
                "2026-10-25T03:30:00+01:00",
                2);

        assertEquals(2, series.reservations().size(),
                "the two-week series must create both requested occurrences, but it returned "
                        + describe(series));

        ReservationView first = firstByStart(series);
        Instant expected = Instant.parse("2026-10-25T00:30:00Z");
        assertEquals(expected, first.startsAt(),
                "the ambiguous local 2026-10-25T02:30 Europe/Berlin must resolve to the earlier UTC "
                        + "instant 2026-10-25T00:30:00Z, but the first occurrence starts at "
                        + first.startsAt());
        assertNotEquals(LATER_AMBIGUOUS_INSTANT, first.startsAt(),
                "the ambiguous local start must not resolve to the later 2026-10-25T01:30:00Z instant, "
                        + "but the first occurrence starts at " + first.startsAt());

        ZonedDateTime localStart = first.startsAt().atZone(BERLIN_ZONE);
        assertEquals(LocalDate.parse("2026-10-25"), localStart.toLocalDate(),
                "the resolved occurrence must stay on 2026-10-25 locally, but resolved to " + localStart);
        assertEquals(LocalTime.of(2, 30), localStart.toLocalTime(),
                "the resolved occurrence must read the requested 02:30 local time, but resolved to "
                        + localStart);
    }

    /**
     * SCN-026#A1 - The nonexistent 2026-03-29 end boundary local 02:30 is shifted forward by the
     * one-hour gap to 03:30, so the first occurrence ends at 2026-03-29T01:30:00Z and the resulting
     * 90-minute duration keeps the occurrence valid.
     */
    @Test
    void scn026A1SpringForwardEndBoundaryResolvesTo0130ZAndStaysValid() {
        ReservationSeriesView series = createSeries(
                "2026-03-29T01:00:00+01:00",
                "2026-03-29T02:30:00+01:00",
                2);

        assertEquals(2, series.reservations().size(),
                "the shifted end boundary must not invalidate or drop an occurrence, so both requested "
                        + "occurrences must be created, but the series returned " + describe(series));

        ReservationView first = firstByStart(series);
        assertEquals(Instant.parse("2026-03-29T00:00:00Z"), first.startsAt(),
                "the first occurrence must start at 01:00 local Europe/Berlin (2026-03-29T00:00:00Z), "
                        + "but it starts at " + first.startsAt());
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), first.endsAt(),
                "the nonexistent local end boundary 02:30 must shift forward to 03:30 local, i.e. the "
                        + "frozen instant 2026-03-29T01:30:00Z, but the first occurrence ends at "
                        + first.endsAt());
        assertEquals(Duration.ofMinutes(90), Duration.between(first.startsAt(), first.endsAt()),
                "the shifted end boundary must leave the first occurrence with a 90-minute duration, "
                        + "but it was " + Duration.between(first.startsAt(), first.endsAt()));

        ZonedDateTime localEnd = first.endsAt().atZone(BERLIN_ZONE);
        assertEquals(LocalTime.of(3, 30), localEnd.toLocalTime(),
                "the shifted end instant must read 03:30 local Europe/Berlin, but it reads " + localEnd);
    }

    /**
     * SCN-026#A2 - The ambiguous 2026-10-25 end boundary local 02:30 resolves to the earlier UTC
     * instant, so the first occurrence ends at 2026-10-25T00:30:00Z rather than 2026-10-25T01:30:00Z.
     */
    @Test
    void scn026A2AutumnAmbiguousEndBoundaryResolvesTo0030ZAndStaysValid() {
        ReservationSeriesView series = createSeries(
                "2026-10-25T01:00:00+02:00",
                "2026-10-25T02:30:00+02:00",
                2);

        assertEquals(2, series.reservations().size(),
                "the ambiguous end boundary must not invalidate or drop an occurrence, so both requested "
                        + "occurrences must be created, but the series returned " + describe(series));

        ReservationView first = firstByStart(series);
        assertEquals(Instant.parse("2026-10-24T23:00:00Z"), first.startsAt(),
                "the first occurrence must start at 01:00 local Europe/Berlin (2026-10-24T23:00:00Z), "
                        + "but it starts at " + first.startsAt());
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), first.endsAt(),
                "the ambiguous local end boundary 02:30 must resolve to the earlier UTC instant "
                        + "2026-10-25T00:30:00Z, but the first occurrence ends at " + first.endsAt());
        assertNotEquals(LATER_AMBIGUOUS_INSTANT, first.endsAt(),
                "the ambiguous end boundary must not resolve to the later 2026-10-25T01:30:00Z instant, "
                        + "but the first occurrence ends at " + first.endsAt());
        assertEquals(Duration.ofMinutes(90), Duration.between(first.startsAt(), first.endsAt()),
                "the earlier ambiguous end boundary must leave the first occurrence with a 90-minute "
                        + "duration, but it was " + Duration.between(first.startsAt(), first.endsAt()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ReservationSeriesView createSeries(String startsAt, String endsAt, int occurrences) {
        ReservationSeriesView series = service.createSeries(
                input(startsAt, endsAt, occurrences), "alice");
        assertNotNull(series, "a valid series request must return the created series");
        return series;
    }

    private static ReservationView firstByStart(ReservationSeriesView series) {
        return series.reservations().stream()
                .min(Comparator.comparing(ReservationView::startsAt))
                .orElseThrow(() -> new AssertionError(
                        "the created series must contain its occurrences, but it returned "
                                + describe(series)));
    }

    /**
     * Materialises the documented series request body through the application's JSON binding, so no
     * DTO constructor shape beyond the frozen wire contract is assumed.
     */
    private static ReservationSeriesInput input(String startsAt, String endsAt, int occurrences) {
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
                """.formatted(ROOM_101_ID, startsAt, endsAt, BERLIN, occurrences);
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
