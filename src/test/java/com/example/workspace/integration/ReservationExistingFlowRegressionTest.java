package com.example.workspace.integration;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.BookingInput;
import com.example.workspace.api.ReservationView;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression-layer obligations of job WBATCH-008: SCN-012#A3, SCN-014#A1, SCN-019#A1,
 * SCN-019#A2 and SCN-019#A3.
 *
 * <p>Boundary: the full Spring Boot application context is loaded (no slice, no mocks). The
 * production {@link com.example.workspace.service.ReservationService} bean is invoked through its
 * Spring proxy, i.e. the same application path the existing one-off controller delegates to, so
 * the application's real transaction advice, security {@link Authentication} handling and JPA
 * configuration are exercised. Durable state is read back through a separate transaction and a
 * fresh persistence context, so a returned view is never accepted as proof of stored state.</p>
 *
 * <p>Regression fault model: every one-off/cancellation obligation here is executed on the
 * post-feature build <em>after</em> the series feature has been used in the same application
 * context, which detects feature-induced cross-talk (shared conflict queries, series-aware entity
 * mapping, series-aware service branches) that the per-feature series suites would not. The frozen
 * business oracle is used for the expected statuses and for the {@code seriesId} nullability; the
 * HTTP route binding, status serialization and error body shape are the API layer's obligation and
 * are not claimed here.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationExistingFlowRegressionTest extends SeriesIntegrationFixtureSupport {

    /**
     * SCN-012#A3 - a one-off reservation created through the existing application path reports
     * {@code seriesId} as null, while the same context has already created bookings that do carry a
     * series identifier.
     *
     * <p>The reported identifier is cross-checked against the durable row, so the null is proven to
     * be the value the view mapper produced for the stored one-off record rather than a default or
     * detached response object.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-012")
    @Tag("test_type:regression")
    @Tag("fault_model:feature_regression")
    void oneOffReservationReportsNullSeriesIdThroughExistingFlow() {
        BookableResource seriesRoom = createRoom("SCN012-A3-SERIES");
        BookableResource oneOffRoom = createRoom("SCN012-A3-ONEOFF");
        Instant start = ZonedDateTime.of(2030, 5, 7, 9, 0, 0, 0, BERLIN).toInstant();
        Instant end = start.plus(OCCURRENCE_DURATION);

        service.createSeries(weeklySeries(seriesRoom.getId(), "Weekly planning", SERIES_OCCURRENCES), "alice");
        assertEquals(SERIES_OCCURRENCES, rowsFor(seriesRoom.getId()).size(),
                "fixture: the series feature must already be in use in this application context");

        ReservationView oneOff = service.create(
                new BookingInput(oneOffRoom.getId(), "One-off", start, end), "alice");

        assertNotNull(oneOff.id(), "the one-off reservation must have been created and reported");
        assertNull(oneOff.seriesId(),
                "a one-off reservation must report seriesId as null, was " + oneOff.seriesId());
        assertEquals(ReservationStatus.CONFIRMED, oneOff.status(),
                "the one-off reservation must remain a confirmed booking, was " + oneOff.status());

        List<Reservation> rows = rowsFor(oneOffRoom.getId());
        assertEquals(1, rows.size(),
                "the one-off reservation must persist exactly one row, found " + rows.size());
        Reservation stored = rows.get(0);
        assertEquals(oneOff.id(), stored.getId(),
                "control: the reported view must be mapped from the persisted one-off row, so the null "
                        + "seriesId is a mapped value rather than an unpopulated response object");
        assertNull(stored.getSeriesId(),
                "the persisted one-off reservation must store a null seriesId");
        assertEquals(start, stored.getStartsAt(),
                "the stored one-off reservation must keep the submitted start instant");
        assertEquals(end, stored.getEndsAt(),
                "the stored one-off reservation must keep the submitted end instant");
    }

    /**
     * SCN-014#A1 - an individual occurrence of an existing three-occurrence series can be cancelled
     * through the existing cancellation path, and the cancellation addresses exactly that
     * occurrence.
     *
     * <p>The remaining occurrences' persistence is SCN-014#A2 and their active state is SCN-014#A3;
     * neither is claimed here.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-014")
    @Tag("test_type:regression")
    @Tag("fault_model:feature_regression")
    void individualOccurrenceOfExistingSeriesCanBeCancelled() {
        BookableResource room = createRoom("SCN014-A1");
        Instant occurrenceTwoStart = SERIES_START.plusWeeks(1).toInstant();

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", SERIES_OCCURRENCES), "alice");

        List<Reservation> beforeCancellation = rowsFor(room.getId());
        assertEquals(SERIES_OCCURRENCES, beforeCancellation.size(),
                "fixture: the three-occurrence series must be persisted before cancelling one occurrence");
        Reservation occurrenceTwo = beforeCancellation.stream()
                .filter(row -> occurrenceTwoStart.equals(row.getStartsAt()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "fixture: occurrence 2 must be persisted, found " + beforeCancellation));
        assertNotEquals(ReservationStatus.CANCELLED, occurrenceTwo.getStatus(),
                "control: the selected occurrence must still be active before the cancellation");

        ReservationView cancelled = service.cancel(occurrenceTwo.getId(), ownerAuthentication());

        assertNotNull(cancelled,
                "cancelling one occurrence of an existing series must return the cancelled booking");
        assertEquals(occurrenceTwo.getId(), cancelled.id(),
                "the cancellation must address the requested occurrence");
        assertEquals(occurrenceTwoStart, cancelled.startsAt(),
                "the cancellation must return the requested occurrence, not another booking of the series");
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "an individual occurrence of an existing series must be cancellable, was " + cancelled.status());

        Reservation stored = readPersistedRow(occurrenceTwo.getId());
        assertNotNull(stored, "the cancelled occurrence must remain retrievable by its identifier");
        assertEquals(ReservationStatus.CANCELLED, stored.getStatus(),
                "the cancellation must be stored on the targeted occurrence, was " + stored.getStatus());
        assertEquals(occurrenceTwoStart, stored.getStartsAt(),
                "the cancelled row must still be the targeted occurrence");
    }

    /**
     * SCN-019#A1 - the existing one-off reservation behaviours still hold on the post-feature build.
     *
     * <p>This replays the pre-existing one-off expectations (confirmed creation with the submitted
     * fields, overlap rejected as a conflict without a write, cancellation forbidden for another
     * employee, cancellation allowed for the owner) against the real application instead of mocks.
     * The series feature is exercised first in the same test so a feature-induced change to the
     * shared reservation path is detectable.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-019")
    @Tag("test_type:regression")
    @Tag("fault_model:feature_regression")
    void existingOneOffReservationBehavioursRemainUnchanged() {
        BookableResource seriesRoom = createRoom("SCN019-A1-SERIES");
        BookableResource room = createRoom("SCN019-A1");
        Instant start = ZonedDateTime.of(2030, 5, 7, 9, 0, 0, 0, BERLIN).toInstant();
        Instant end = start.plus(OCCURRENCE_DURATION);

        service.createSeries(weeklySeries(seriesRoom.getId(), "Weekly planning", SERIES_OCCURRENCES), "alice");
        assertEquals(SERIES_OCCURRENCES, rowsFor(seriesRoom.getId()).size(),
                "fixture: the series feature must already be in use in this application context");

        ReservationView created = service.create(
                new BookingInput(room.getId(), "One-off", start, end), "alice");
        assertNotNull(created.id(), "the existing one-off flow must still create a reservation");
        assertEquals(ReservationStatus.CONFIRMED, created.status(),
                "a one-off reservation on a room that does not require approval must still be CONFIRMED, was "
                        + created.status());
        assertEquals("alice", created.bookedBy(), "the one-off response must still report the booker");
        assertEquals("One-off", created.title(), "the one-off response must still report the submitted title");
        assertEquals(start, created.startsAt(), "the one-off response must still report the submitted start");
        assertEquals(end, created.endsAt(), "the one-off response must still report the submitted end");

        Reservation stored = readPersistedRow(created.id());
        assertNotNull(stored, "the one-off reservation must still persist a retrievable row");
        assertEquals(ReservationStatus.CONFIRMED, stored.getStatus(),
                "the stored one-off reservation must still be CONFIRMED, was " + stored.getStatus());
        assertEquals(start, stored.getStartsAt(),
                "the stored one-off reservation must still keep the submitted start instant");
        assertEquals(end, stored.getEndsAt(),
                "the stored one-off reservation must still keep the submitted end instant");

        RuntimeException overlap = assertThrows(RuntimeException.class,
                () -> service.create(new BookingInput(room.getId(), "Overlapping",
                        start.plusSeconds(1800), end.plusSeconds(1800)), "carol"),
                "an overlapping one-off request must still be rejected");
        assertConflictRejection(overlap);
        assertEquals(1, rowsFor(room.getId()).size(),
                "the rejected overlapping one-off request must not persist a row");

        ApiException forbidden = assertThrows(ApiException.class,
                () -> service.cancel(created.id(), authentication("bob")),
                "another employee must still be forbidden from cancelling the one-off booking");
        assertEquals(HttpStatus.FORBIDDEN, forbidden.status(),
                "the forbidden cancellation must still carry the 403 status, was " + forbidden.status());
        Reservation afterForbidden = readPersistedRow(created.id());
        assertNotNull(afterForbidden, "the one-off reservation must survive the forbidden cancellation");
        assertEquals(ReservationStatus.CONFIRMED, afterForbidden.getStatus(),
                "the forbidden cancellation must not change the stored status");

        ReservationView cancelled = service.cancel(created.id(), authentication("alice"));
        assertEquals(created.id(), cancelled.id(),
                "the owner must still be able to cancel the one-off booking");
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "the owner cancellation must still return a CANCELLED one-off booking, was " + cancelled.status());
        Reservation afterCancellation = readPersistedRow(created.id());
        assertNotNull(afterCancellation, "the cancelled one-off reservation must remain retrievable");
        assertEquals(ReservationStatus.CANCELLED, afterCancellation.getStatus(),
                "the owner cancellation must still be stored on the one-off reservation");
    }

    /**
     * SCN-019#A2 - a one-off booking for an approval-required room is still created PENDING and can
     * still be cancelled through the existing path on the post-feature build.
     *
     * <p>The approval room and the control room receive the identical request and differ only in the
     * {@code approvalRequired} flag, so the request itself is proven valid and the PENDING outcome is
     * attributable to the approval requirement. The series feature is used first in the same context.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-019")
    @Tag("test_type:regression")
    @Tag("fault_model:feature_regression")
    void oneOffApprovalBookingStaysPendingAndCancellableAfterSeriesUse() {
        BookableResource seriesRoom = createRoom("SCN019-A2-SERIES");
        BookableResource approvalRoom = createRoom("SCN019-A2-APPROVAL", true, true);
        BookableResource controlRoom = createRoom("SCN019-A2-CONTROL", true, false);
        Instant start = ZonedDateTime.of(2030, 5, 7, 9, 0, 0, 0, BERLIN).toInstant();
        Instant end = start.plus(OCCURRENCE_DURATION);

        service.createSeries(weeklySeries(seriesRoom.getId(), "Weekly planning", SERIES_OCCURRENCES), "alice");
        assertEquals(SERIES_OCCURRENCES, rowsFor(seriesRoom.getId()).size(),
                "fixture: the series feature must already be in use in this application context");

        ReservationView created = service.create(
                new BookingInput(approvalRoom.getId(), "One-off sync", start, end), "alice");
        assertNotNull(created.id(),
                "the one-off booking for the approval-required room must still be created");
        assertEquals(ReservationStatus.PENDING, created.status(),
                "a one-off booking for an approval-required room must still be PENDING, was " + created.status());
        assertEquals(start, created.startsAt(), "the approval booking must keep the submitted start");
        assertEquals(end, created.endsAt(), "the approval booking must keep the submitted end");

        Reservation stored = readPersistedRow(created.id());
        assertNotNull(stored, "the pending one-off booking must persist a retrievable row");
        assertEquals(ReservationStatus.PENDING, stored.getStatus(),
                "the stored one-off booking for an approval-required room must still be PENDING, was "
                        + stored.getStatus());
        assertEquals(1, rowsFor(approvalRoom.getId()).size(),
                "the one-off request must still persist exactly one row for the approval-required room");

        ReservationView control = service.create(
                new BookingInput(controlRoom.getId(), "One-off sync", start, end), "alice");
        assertEquals(ReservationStatus.CONFIRMED, control.status(),
                "control fixture: the identical request on a room without approval must still be CONFIRMED, was "
                        + control.status());

        ReservationView cancelled = service.cancel(created.id(), authentication("alice"));
        assertEquals(created.id(), cancelled.id(),
                "the existing cancellation path must still address the pending one-off booking");
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "a one-off booking for an approval-required room must still be cancellable, was "
                        + cancelled.status());

        Reservation afterCancellation = readPersistedRow(created.id());
        assertNotNull(afterCancellation, "the cancelled one-off booking must remain retrievable");
        assertEquals(ReservationStatus.CANCELLED, afterCancellation.getStatus(),
                "the cancellation must still be stored on the one-off booking, was "
                        + afterCancellation.getStatus());
        assertEquals(1, rowsFor(approvalRoom.getId()).size(),
                "cancelling must not duplicate or delete the one-off booking row");
    }

    /**
     * SCN-019#A3 - a one-off booking persists with a null seriesId and remains retrievable by its
     * identifier on the post-feature build.
     *
     * <p>The row is re-read in a new transaction and a fresh persistence context after a series has
     * already been created in the same application context, so the observation is the durable
     * post-feature record rather than the service return value or an entity created by a series.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-019")
    @Tag("test_type:regression")
    @Tag("fault_model:feature_regression")
    void oneOffBookingPersistsNullSeriesIdAndRemainsRetrievableAfterSeriesUse() {
        BookableResource seriesRoom = createRoom("SCN019-A3-SERIES");
        BookableResource room = createRoom("SCN019-A3");
        Instant start = ZonedDateTime.of(2030, 5, 7, 9, 0, 0, 0, BERLIN).toInstant();
        Instant end = start.plus(OCCURRENCE_DURATION);

        service.createSeries(weeklySeries(seriesRoom.getId(), "Weekly planning", SERIES_OCCURRENCES), "alice");
        assertEquals(SERIES_OCCURRENCES, rowsFor(seriesRoom.getId()).size(),
                "fixture: the series feature must already be in use in this application context");

        ReservationView created = service.create(
                new BookingInput(room.getId(), "One-off sync", start, end), "alice");
        assertNotNull(created.id(), "the one-off booking must report the identifier of its persisted row");

        Reservation persisted = readPersistedRow(created.id());
        assertNotNull(persisted,
                "the one-off booking must remain retrievable by its identifier after the commit");
        assertNull(persisted.getSeriesId(),
                "the one-off booking must persist a null seriesId, was " + persisted.getSeriesId());
        assertEquals(room.getId(), persisted.getResource().getId(),
                "the retrieved row must still belong to the room the one-off booking was created for");
        assertEquals("One-off sync", persisted.getTitle(),
                "the retrieved one-off row must keep the submitted title");
        assertEquals(start, persisted.getStartsAt(),
                "the retrieved one-off row must keep the submitted start instant");
        assertEquals(end, persisted.getEndsAt(),
                "the retrieved one-off row must keep the submitted end instant");
        assertEquals("alice", persisted.getBookedBy(),
                "the retrieved one-off row must keep the submitting employee");
        assertEquals(1L, countRowsWithId(created.id()),
                "the one-off booking must be retrievable as exactly one durable row by its identifier");
        assertEquals(1, rowsFor(room.getId()).size(),
                "the one-off booking must not leave additional rows behind for the room");
    }

    private Authentication ownerAuthentication() {
        return authentication("alice");
    }

    private Authentication authentication(String principal) {
        return new UsernamePasswordAuthenticationToken(principal, "", List.of());
    }

    /**
     * Re-reads one reservation in a new transaction, so the returned instance comes from a fresh
     * persistence context rather than from the transaction that inserted it.
     */
    private Reservation readPersistedRow(long reservationId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery(
                                "select r from Reservation r where r.id = :id",
                                Reservation.class)
                        .setParameter("id", reservationId)
                        .getResultList()
                        .stream()
                        .findFirst()
                        .orElse(null));
    }

    private long countRowsWithId(long reservationId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                entityManager.createQuery(
                                "select count(r) from Reservation r where r.id = :id",
                                Long.class)
                        .setParameter("id", reservationId)
                        .getSingleResult());
    }
}
