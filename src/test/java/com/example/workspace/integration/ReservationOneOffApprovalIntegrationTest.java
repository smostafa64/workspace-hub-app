package com.example.workspace.integration;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration-layer obligation SCN-019#A2 (job WBATCH-007): a one-off booking for an
 * approval-required room is still created {@code PENDING} and can still be cancelled through the
 * existing application path.
 *
 * <p>Boundary: the full Spring Boot application context is loaded (no slice, no mocks) and the
 * production {@link com.example.workspace.service.ReservationService} bean is driven through its
 * Spring proxy for both the creation and the cancellation, so the application's real transaction
 * advice, security {@link Authentication} handling and persistence configuration are exercised.
 * The resulting state is read back through JPQL in a separate persistence context, so the
 * {@code PENDING -> CANCELLED} transition is observed on the durable row and not only on the
 * service return value. The HTTP status codes of the existing endpoint are the API layer's
 * obligation and are not claimed here.</p>
 *
 * <p>The control room differs from the approval room only in its {@code approvalRequired} flag and
 * receives the same one-off request, proving the request itself is valid.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationOneOffApprovalIntegrationTest extends SeriesIntegrationFixtureSupport {

    /**
     * SCN-019#A2 - the one-off booking created on the approval-required room is returned and stored
     * as {@code PENDING}, and the existing cancellation path moves that same booking to
     * {@code CANCELLED} without duplicating or deleting the row.
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-019")
    @Tag("fault_model:state_transition")
    void oneOffBookingOnApprovalRequiredRoomIsCreatedPendingAndCanBeCancelled() {
        BookableResource approvalRoom = createRoom("SCN019-A2-APPROVAL", true, true);
        BookableResource controlRoom = createRoom("SCN019-A2-CONTROL", true, false);
        Instant start = ZonedDateTime.of(2030, 5, 7, 9, 0, 0, 0, BERLIN).toInstant();
        Instant end = start.plus(OCCURRENCE_DURATION);
        String title = "One-off sync";

        ReservationView created = service.create(
                new BookingInput(approvalRoom.getId(), title, start, end), "alice");
        assertNotNull(created.id(),
                "the one-off booking for the approval-required room must be created");
        assertEquals(ReservationStatus.PENDING, created.status(),
                "a one-off booking for an approval-required room must be returned as PENDING, was "
                        + created.status());

        List<Reservation> afterCreation = rowsFor(approvalRoom.getId());
        assertEquals(1, afterCreation.size(),
                "the one-off booking must persist exactly one row, found " + afterCreation.size());
        Reservation stored = afterCreation.get(0);
        assertEquals(created.id(), stored.getId(),
                "the persisted row must be the booking that was returned");
        assertEquals(ReservationStatus.PENDING, stored.getStatus(),
                "the persisted one-off booking for an approval-required room must be PENDING, was "
                        + stored.getStatus());
        assertEquals(start, stored.getStartsAt(),
                "the persisted one-off booking must keep the submitted start instant");
        assertEquals(end, stored.getEndsAt(),
                "the persisted one-off booking must keep the submitted end instant");

        service.create(new BookingInput(controlRoom.getId(), title, start, end), "alice");
        assertEquals(1, rowsFor(controlRoom.getId()).size(),
                "control fixture: the identical one-off request must be accepted on a room that does not "
                        + "require approval");

        ReservationView cancelled = service.cancel(created.id(), ownerAuthentication());
        assertEquals(created.id(), cancelled.id(),
                "the cancellation must address the existing one-off booking");
        assertEquals(ReservationStatus.CANCELLED, cancelled.status(),
                "the existing cancellation path must cancel the one-off booking, was " + cancelled.status());

        List<Reservation> afterCancellation = rowsFor(approvalRoom.getId());
        assertEquals(1, afterCancellation.size(),
                "cancelling must not duplicate or delete the one-off booking row, found "
                        + afterCancellation.size());
        assertEquals(created.id(), afterCancellation.get(0).getId(),
                "the cancelled one-off booking must remain identifiable by its original id");
        assertEquals(ReservationStatus.CANCELLED, afterCancellation.get(0).getStatus(),
                "the persisted one-off booking must be stored as CANCELLED after cancellation, was "
                        + afterCancellation.get(0).getStatus());
    }

    private Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken("alice", "", List.of());
    }
}
