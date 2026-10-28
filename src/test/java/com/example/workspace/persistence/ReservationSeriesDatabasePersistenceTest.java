package com.example.workspace.persistence;

import com.example.workspace.api.ResourceInput;
import com.example.workspace.api.ResourceView;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ResourceService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Database-layer evidence for SCN-020, SCN-021 and SCN-022.
 *
 * <p>Each test drives the approved series write path (POST /api/reservation-series) and then
 * observes the committed store through a fresh JPA transaction, not the HTTP response echo or a
 * mocked collaborator. The partial-persistence arrangement (SCN-020) uses the Europe/Berlin
 * spring-forward transition: a series requested with the wall-clock window 01:50-03:00 is 70
 * minutes long on 2030-03-17 and 2030-04-14 but only 10 minutes on 2030-03-31, where local hour
 * 02:00-03:00 does not exist. The middle requested occurrence therefore fails the 15-minute
 * minimum duration while the first and third stay valid, which is the frozen SCN-020
 * precondition.</p>
 *
 * <p>All dates lie in 2030 so the requests stay in the future for the write path.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationSeriesDatabasePersistenceTest {

    private static final String SERIES_ENDPOINT = "/api/reservation-series";
    private static final String BERLIN = "Europe/Berlin";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** SCN-020: 70 / 10 / 70 minutes across the spring-forward transition (2030-03-31). */
    private static final String PARTIAL_START = "2030-03-17T01:50:00+01:00";
    private static final String PARTIAL_END = "2030-03-17T03:00:00+01:00";
    private static final Instant FIRST_OCCURRENCE_START = Instant.parse("2030-03-17T00:50:00Z");
    private static final Instant FIRST_OCCURRENCE_END = Instant.parse("2030-03-17T02:00:00Z");
    private static final Instant FAILED_OCCURRENCE_START = Instant.parse("2030-03-31T00:50:00Z");
    private static final Instant THIRD_OCCURRENCE_START = Instant.parse("2030-04-13T23:50:00Z");
    private static final Instant THIRD_OCCURRENCE_END = Instant.parse("2030-04-14T01:00:00Z");

    /** SCN-021: an otherwise valid series request; only the probed field is varied. */
    private static final String VALID_START = "2030-05-06T10:00:00+02:00";
    private static final String VALID_END = "2030-05-06T11:00:00+02:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private ResourceService resourceService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    // ------------------------------------------------------------------
    // SCN-020 - partial persistence with a gap and a 400 body
    // ------------------------------------------------------------------

    /**
     * SCN-020#A1 - the persisted rows are exactly the two valid occurrences; the failed middle
     * occurrence leaves a gap and no row exists at its position.
     */
    @Test
    void scn020A1TwoValidOccurrencesPersistWithAGapAtTheFailedOccurrence() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, PARTIAL_START, PARTIAL_END, BERLIN, "WEEKLY", "3"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "a series whose middle occurrence fails duration validation must be answered with 400");

        List<Reservation> stored = committedReservations(resourceId);
        assertEquals(2, stored.size(),
                "exactly the two valid occurrences may persist after the partial failure, but "
                        + describe(stored));

        Set<Instant> storedStarts = stored.stream()
                .map(Reservation::getStartsAt)
                .collect(Collectors.toSet());
        assertEquals(Set.of(FIRST_OCCURRENCE_START, THIRD_OCCURRENCE_START), storedStarts,
                "the stored occurrences must be the first and the third requested occurrence "
                        + "(the failed middle occurrence must be missing from the stored series), but "
                        + describe(stored));

        Set<Instant> storedEnds = stored.stream()
                .map(Reservation::getEndsAt)
                .collect(Collectors.toSet());
        assertEquals(Set.of(FIRST_OCCURRENCE_END, THIRD_OCCURRENCE_END), storedEnds,
                "the stored occurrences must keep the valid 70-minute duration of the accepted "
                        + "occurrences, but " + describe(stored));

        assertTrue(stored.stream().noneMatch(
                        reservation -> FAILED_OCCURRENCE_START.equals(reservation.getStartsAt())),
                "no row may exist at the position of the failed middle occurrence "
                        + FAILED_OCCURRENCE_START + ", but " + describe(stored));
    }

    /**
     * SCN-020#A3 - when no occurrence is valid the request is rejected with 400 and no
     * reservation is persisted.
     */
    @Test
    void scn020A3WhenNoOccurrenceIsValidNoReservationPersists() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        // A 10-minute wall-clock duration is below the 15-minute minimum for every occurrence,
        // so no occurrence of the series can be valid.
        MvcResult result = mockMvc.perform(seriesPost(resourceId,
                        "2030-05-06T10:00:00+02:00",
                        "2030-05-06T10:10:00+02:00",
                        BERLIN, "WEEKLY", "3"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "a series in which no occurrence is valid must be answered with 400");

        List<Reservation> stored = committedReservations(resourceId);
        assertTrue(stored.isEmpty(),
                "when no occurrence is valid nothing may persist (and the reported persisted "
                        + "list is empty), but " + describe(stored));
    }

    /**
     * SCN-020#A4 - the seriesId reported in the 400 body equals the series identifier stored on
     * the persisted occurrences.
     *
     * <p>The JSON field names of the partial-result body are not frozen (AMB-001), so identity is
     * verified on the documented content: the 400 body carries the shared series identifier of
     * the stored occurrences, and no other UUID appears in it.</p>
     */
    @Test
    void scn020A4ReportedSeriesIdEqualsTheSeriesIdStoredOnPersistedOccurrences() throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, PARTIAL_START, PARTIAL_END, BERLIN, "WEEKLY", "3"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus(),
                "the partially persisted series must be answered with 400");

        List<Reservation> stored = committedReservations(resourceId);
        assertEquals(2, stored.size(),
                "the partial outcome must have stored exactly the two valid occurrences, but "
                        + describe(stored));

        UUID storedSeriesId = stored.get(0).getSeriesId();
        assertNotNull(storedSeriesId,
                "the persisted occurrences must carry the series identifier that is reported back");
        for (Reservation reservation : stored) {
            assertEquals(storedSeriesId, reservation.getSeriesId(),
                    "every persisted occurrence of the series must carry the same series identifier");
        }

        String body = responseBody(result);
        Set<String> reportedUuids = uuidsIn(body);
        assertEquals(Set.of(storedSeriesId.toString().toLowerCase(Locale.ROOT)), reportedUuids,
                "the 400 body must report the stored series identifier " + storedSeriesId
                        + " and no other UUID, but the body was: " + body);
    }

    // ------------------------------------------------------------------
    // SCN-021 - C4 and C5 rejections write no rows
    // ------------------------------------------------------------------

    /**
     * SCN-021#A3 - a request rejected with 400 under C5 (missing offset, invalid timezone or a
     * non-WEEKLY frequency) leaves the room's reservation count unchanged.
     */
    @Test
    void scn021A3C5RejectionsLeaveTheRoomReservationCountUnchanged() throws Exception {
        assertRejectedWithoutAnyWrite("date-times lacking a UTC offset",
                "2030-05-06T10:00:00", "2030-05-06T11:00:00", BERLIN, "WEEKLY", "3");
        assertRejectedWithoutAnyWrite("fixed-offset timezone +01:00",
                VALID_START, VALID_END, "+01:00", "WEEKLY", "3");
        assertRejectedWithoutAnyWrite("timezone abbreviation CET",
                VALID_START, VALID_END, "CET", "WEEKLY", "3");
        assertRejectedWithoutAnyWrite("non-WEEKLY frequency DAILY",
                VALID_START, VALID_END, BERLIN, "DAILY", "3");
    }

    /**
     * SCN-021#A4 - a request rejected with 400 because occurrences is fractional, out of range or
     * not an integer leaves the room's reservation count unchanged.
     */
    @Test
    void scn021A4InvalidOccurrenceCountsLeaveTheRoomReservationCountUnchanged() throws Exception {
        assertRejectedWithoutAnyWrite("fractional occurrences 2.5",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "2.5");
        assertRejectedWithoutAnyWrite("occurrences 13 above the 2-12 range",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "13");
        assertRejectedWithoutAnyWrite("numeric-string occurrences \"3\"",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "\"3\"");
        assertRejectedWithoutAnyWrite("boolean occurrences true",
                VALID_START, VALID_END, BERLIN, "WEEKLY", "true");
    }

    // ------------------------------------------------------------------
    // SCN-022 - PENDING status is persisted for approval-required rooms
    // ------------------------------------------------------------------

    /**
     * SCN-022#A1 - every persisted occurrence of an approval-required room carries status PENDING
     * in the store.
     */
    @Test
    void scn022A1ApprovalRequiredRoomPersistsEveryOccurrenceAsPending() throws Exception {
        Long resourceId = createRoom("Room 201", 2, true);

        mockMvc.perform(seriesPost(resourceId,
                        "2030-04-21T10:00:00+02:00",
                        "2030-04-21T10:30:00+02:00",
                        BERLIN, "WEEKLY", "3"))
                .andExpect(status().is2xxSuccessful());

        List<Reservation> stored = committedReservations(resourceId);
        assertEquals(3, stored.size(),
                "the accepted three-occurrence series must persist three rows, but "
                        + describe(stored));
        for (Reservation reservation : stored) {
            assertEquals(ReservationStatus.PENDING, reservation.getStatus(),
                    "an occurrence persisted for an approval-required room must be stored as "
                            + "PENDING, but row " + describeOne(reservation) + " was stored");
        }
    }

    // ------------------------------------------------------------------
    // Fixtures and database helpers
    // ------------------------------------------------------------------

    /**
     * SCN-021 helper: one isolated free room, a recorded reservation count, the rejected request
     * and a re-read of the reservation count from the store.
     */
    private void assertRejectedWithoutAnyWrite(String label, String startsAt, String endsAt,
                                               String timezone, String frequency,
                                               String occurrencesJson) throws Exception {
        Long resourceId = createRoom("Room 101", 1, false);

        List<Reservation> before = committedReservations(resourceId);
        assertEquals(0, before.size(),
                "fixture room must start free for the '" + label + "' case, but "
                        + describe(before));

        MvcResult result = mockMvc.perform(
                        seriesPost(resourceId, startsAt, endsAt, timezone, frequency, occurrencesJson))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus(),
                "a request rejected under C4/C5 must be answered with 400 for the '" + label
                        + "' case, but the body was: "
                        + responseBody(result));

        List<Reservation> after = committedReservations(resourceId);
        assertEquals(before.size(), after.size(),
                "the room's reservation count must be unchanged after the '" + label
                        + "' rejection, but " + describe(after));
        assertTrue(after.isEmpty(),
                "a rejected request must not write any reservation row for the '" + label
                        + "' case, but " + describe(after));
    }

    private Long createRoom(String name, int floorNumber, boolean approvalRequired) {
        String code = "R" + floorNumber + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        ResourceView room = resourceService.create(new ResourceInput(
                code, name, ResourceType.ROOM, "HQ", floorNumber, 8, true, approvalRequired));
        return room.id();
    }

    private MockHttpServletRequestBuilder seriesPost(Long resourceId, String startsAt, String endsAt,
                                                     String timezone, String frequency,
                                                     String occurrencesJson) {
        String body = """
                {
                  "resourceId": %d,
                  "title": "Weekly planning",
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "timezone": "%s",
                  "frequency": "%s",
                  "occurrences": %s
                }
                """.formatted(resourceId, startsAt, endsAt, timezone, frequency, occurrencesJson);
        return post(SERIES_ENDPOINT)
                .with(user("alice").roles("EMPLOYEE", "MANAGER", "ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private List<Reservation> committedReservations(Long resourceId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        List<Reservation> stored = template.execute(status -> entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId", Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList());
        return stored == null ? List.of() : stored;
    }

    private static String responseBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException unsupportedEncoding) {
            throw new IllegalStateException(
                    "UTF-8 must be supported to read the response body", unsupportedEncoding);
        }
    }

    private static Set<String> uuidsIn(String body) {
        Set<String> uuids = new HashSet<>();
        Matcher matcher = UUID_PATTERN.matcher(body);
        while (matcher.find()) {
            uuids.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return uuids;
    }

    private static String describe(List<Reservation> reservations) {
        return reservations.stream()
                .map(ReservationSeriesDatabasePersistenceTest::describeOne)
                .collect(Collectors.joining(", ", "stored={", "}"));
    }

    private static String describeOne(Reservation reservation) {
        return "[id=" + reservation.getId()
                + ", startsAt=" + reservation.getStartsAt()
                + ", endsAt=" + reservation.getEndsAt()
                + ", status=" + reservation.getStatus()
                + ", seriesId=" + reservation.getSeriesId() + "]";
    }
}
