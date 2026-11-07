package com.example.workspace.api;

import com.example.workspace.service.ReservationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit-layer verification of the frozen malformed-series-payload obligation at the request
 * binding boundary.
 *
 * <p>The real {@link ReservationController} and the real {@link ApiErrorHandler} run in process in
 * a standalone MockMvc setup. The Jackson {@link ObjectMapper} is configured with the same
 * date-time handling declared by the application ({@code write-dates-as-timestamps: false} and
 * {@code adjust-dates-to-context-time-zone: false}) and the standard JSR-310 module, so the
 * observed deserialization behaviour is the production binding behaviour. The downstream
 * {@link ReservationService} is replaced by a real subclass test double that only records whether
 * {@code createSeries} was invoked, because the fault under test occurs before the service is
 * called.</p>
 *
 * <p>Each negative test submits an otherwise identical request whose timestamps carry an ISO UTC
 * offset and asserts it is accepted, then submits the same body with only the offset removed and
 * asserts a 400 client error. The recorded {@code createSeries} invocation count proves the
 * rejected request never reached the reservation-creating service, therefore creating no
 * reservation rows.</p>
 */
class ReservationSeriesMalformedPayloadUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final AtomicInteger CREATE_SERIES_CALLS = new AtomicInteger();

    /**
     * Real subclass test double: no Mockito instrumentation is required, and the only behaviour
     * replaced is the downstream reservation creation, which must be reached exactly once by the
     * valid control request and never by the rejected one.
     */
    private final ReservationService service =
            new ReservationService(null, null, null, Clock.systemUTC()) {
                @Override
                public ReservationSeriesView createSeries(ReservationSeriesInput input, String actor) {
                    CREATE_SERIES_CALLS.incrementAndGet();
                    return null;
                }
            };

    private MockMvc mockMvc;
    private ZonedDateTime validStart;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE,
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        mockMvc = MockMvcBuilders.standaloneSetup(new ReservationController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();

        CREATE_SERIES_CALLS.set(0);
        // A genuinely future, otherwise valid local start; the exact date does not matter because
        // the downstream service is the recorded test-double boundary and only request binding is
        // under test.
        validStart = ZonedDateTime.now(BERLIN).plusDays(30).withNano(0).withSecond(0);
    }

    // ---------------------------------------------------------------------
    // SCN-011#A1 - offset-less startsAt
    // ---------------------------------------------------------------------
    @Test
    void scn011StartsAtWithoutUtcOffsetIsRejectedAndCreatesNoRows() throws Exception {
        String validStartsAt = validStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String validEndsAt = validStart.plusHours(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // Valid control: the identical body whose only difference is the offset on startsAt is
        // bound and reaches the service exactly once.
        mockMvc.perform(post("/api/reservation-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(principal())
                        .content(body(validStartsAt, validEndsAt)))
                .andExpect(status().isCreated());
        assertEquals(1, CREATE_SERIES_CALLS.get(),
                "control: the offset-bearing startsAt request must reach createSeries exactly once");

        // Invalid partition: the same request with the offset stripped from startsAt.
        String offsetLessStartsAt =
                validStart.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        mockMvc.perform(post("/api/reservation-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(principal())
                        .content(body(offsetLessStartsAt, validEndsAt)))
                .andExpect(status().isBadRequest());

        // The rejected request must not reach the reservation-creating service, so it creates no
        // reservation rows: the invocation count is still the single control call.
        assertEquals(1, CREATE_SERIES_CALLS.get(),
                "offset-less startsAt must be rejected before createSeries, creating no rows");
    }

    // ---------------------------------------------------------------------
    // SCN-011#A1 - offset-less endsAt
    // ---------------------------------------------------------------------
    @Test
    void scn011EndsAtWithoutUtcOffsetIsRejectedAndCreatesNoRows() throws Exception {
        String validStartsAt = validStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String validEndsAt = validStart.plusHours(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // Valid control: the identical body whose only difference is the offset on endsAt is
        // bound and reaches the service exactly once.
        mockMvc.perform(post("/api/reservation-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(principal())
                        .content(body(validStartsAt, validEndsAt)))
                .andExpect(status().isCreated());
        assertEquals(1, CREATE_SERIES_CALLS.get(),
                "control: the offset-bearing endsAt request must reach createSeries exactly once");

        // Invalid partition: the same request with the offset stripped from endsAt.
        String offsetLessEndsAt =
                validStart.plusHours(1).toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        mockMvc.perform(post("/api/reservation-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(principal())
                        .content(body(validStartsAt, offsetLessEndsAt)))
                .andExpect(status().isBadRequest());

        // The rejected request must not reach the reservation-creating service, so it creates no
        // reservation rows: the invocation count is still the single control call.
        assertEquals(1, CREATE_SERIES_CALLS.get(),
                "offset-less endsAt must be rejected before createSeries, creating no rows");
    }

    private static Authentication principal() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }

    private static String body(String startsAt, String endsAt) {
        return "{\"resourceId\":1,\"title\":\"QA malformed payload series\","
                + "\"startsAt\":\"" + startsAt + "\","
                + "\"endsAt\":\"" + endsAt + "\","
                + "\"timezone\":\"Europe/Berlin\","
                + "\"frequency\":\"WEEKLY\","
                + "\"occurrences\":2}";
    }
}
