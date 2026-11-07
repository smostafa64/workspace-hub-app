package com.example.workspace.api;

import com.example.workspace.service.ReservationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit-layer verification for the reservation-series request binding of {@code startsAt} and
 * {@code endsAt}.
 *
 * <p>The real {@link ReservationController} and the real {@link ApiErrorHandler} run in-process in
 * a standalone MockMvc setup. The Jackson {@link ObjectMapper} is configured with the same
 * date-time handling declared by the application ({@code write-dates-as-timestamps: false} and
 * {@code adjust-dates-to-context-time-zone: false}) and the standard JSR-310 module, so the
 * observed deserialization behaviour is the production binding behaviour. Only the downstream
 * {@link ReservationService} is mocked, because the fault under test occurs before the service is
 * called. No business rule is reimplemented.</p>
 *
 * <p>Each negative test first submits an otherwise identical request whose timestamps carry an ISO
 * UTC offset and asserts it is accepted, then submits the same body with only the offset removed
 * and asserts a 400 client error. The valid control also proves the mapper preserved the intended
 * offset and instant. The service invocation count is checked so the rejected request is proven
 * not to reach the reservation-creating service, therefore creating no reservation rows.</p>
 */
class ReservationSeriesRequestParsingUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final ReservationService service = mock(ReservationService.class);
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

        reset(service);
        // A genuinely future, otherwise valid local start; the exact date does not matter because
        // the downstream service is the mocked boundary and only binding is under test.
        validStart = ZonedDateTime.now(BERLIN).plusDays(30).withNano(0).withSecond(0);
    }

    // ---------------------------------------------------------------------
    // SCN-005#A1 (startsAt)
    // ---------------------------------------------------------------------
    @Test
    void scn005StartsAtWithoutOffsetIsRejectedAndCreatesNoRows() throws Exception {
        String validStartsAt = validStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String validEndsAt = validStart.plusHours(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // Valid control: identical body whose only difference is the offset on startsAt is bound
        // and reaches the service exactly once.
        mockMvc.perform(post("/api/reservation-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(principal())
                        .content(validBody(validStartsAt, validEndsAt)))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReservationSeriesInput> captor =
                ArgumentCaptor.forClass(ReservationSeriesInput.class);
        verify(service, times(1)).createSeries(captor.capture(), eq("alice"));

        ReservationSeriesInput parsed = captor.getValue();
        assertNotNull(parsed.startsAt(),
                "control: the valid startsAt must deserialize to a non-null OffsetDateTime");
        assertEquals(OffsetDateTime.parse(validStartsAt), parsed.startsAt(),
                "control: the mapper must preserve the supplied startsAt offset and local time");
        assertEquals(validStart.toInstant(), parsed.startsAt().toInstant(),
                "control: the mapper must preserve the supplied startsAt instant");
        assertEquals(validStart.getOffset(), parsed.startsAt().getOffset(),
                "control: the mapper must preserve the supplied startsAt UTC offset");
        assertNotNull(parsed.endsAt(),
                "control: the valid endsAt must deserialize to a non-null OffsetDateTime");
        assertEquals(OffsetDateTime.parse(validEndsAt), parsed.endsAt(),
                "control: the mapper must preserve the supplied endsAt offset and local time");

        // Invalid partition: the same request with the offset stripped from startsAt.
        String offsetLessStartsAt =
                validStart.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        mockMvc.perform(post("/api/reservation-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(principal())
                        .content(validBody(offsetLessStartsAt, validEndsAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(service, times(1)).createSeries(any(ReservationSeriesInput.class), anyString());
    }

    // ---------------------------------------------------------------------
    // SCN-005#A1 (endsAt)
    // ---------------------------------------------------------------------
    @Test
    void scn005EndsAtWithoutOffsetIsRejectedAndCreatesNoRows() throws Exception {
        String validStartsAt = validStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String validEndsAt = validStart.plusHours(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        // Valid control: identical body whose only difference is the offset on endsAt is bound and
        // reaches the service exactly once.
        mockMvc.perform(post("/api/reservation-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(principal())
                        .content(validBody(validStartsAt, validEndsAt)))
                .andExpect(status().isCreated());

        ArgumentCaptor<ReservationSeriesInput> captor =
                ArgumentCaptor.forClass(ReservationSeriesInput.class);
        verify(service, times(1)).createSeries(captor.capture(), eq("alice"));

        ReservationSeriesInput parsed = captor.getValue();
        assertNotNull(parsed.endsAt(),
                "control: the valid endsAt must deserialize to a non-null OffsetDateTime");
        assertEquals(OffsetDateTime.parse(validEndsAt), parsed.endsAt(),
                "control: the mapper must preserve the supplied endsAt offset and local time");
        assertEquals(validStart.plusHours(1).toInstant(), parsed.endsAt().toInstant(),
                "control: the mapper must preserve the supplied endsAt instant");
        assertEquals(validStart.plusHours(1).getOffset(), parsed.endsAt().getOffset(),
                "control: the mapper must preserve the supplied endsAt UTC offset");

        // Invalid partition: the same request with the offset stripped from endsAt.
        String offsetLessEndsAt =
                validStart.plusHours(1).toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        mockMvc.perform(post("/api/reservation-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .principal(principal())
                        .content(validBody(validStartsAt, offsetLessEndsAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(service, times(1)).createSeries(any(ReservationSeriesInput.class), anyString());
    }

    private static Authentication principal() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }

    private static String validBody(String startsAt, String endsAt) {
        return "{\"resourceId\":1,\"title\":\"QA series\","
                + "\"startsAt\":\"" + startsAt + "\","
                + "\"endsAt\":\"" + endsAt + "\","
                + "\"timezone\":\"Europe/Berlin\","
                + "\"frequency\":\"WEEKLY\","
                + "\"occurrences\":2}";
    }
}
