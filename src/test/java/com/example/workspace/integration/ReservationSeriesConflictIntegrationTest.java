package com.example.workspace.integration;

import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration-layer obligation SCN-011#A1 (job WBATCH-006): a series whose occurrence overlaps an
 * existing one-off reservation is rejected with conflict (HTTP 409) semantics and creates no
 * bookings.
 *
 * <p>Boundary: the full Spring Boot application context is loaded (no slice, no mocks) and the
 * production {@link com.example.workspace.service.ReservationService} bean is invoked through its
 * Spring proxy, so the application's own transaction manager, AOP proxy and connection pool are the
 * ones under test. The persisted rows are read back through JPQL in a separate persistence context.
 * This differs from the {@code @DataJpaTest} database-layer test of the same scenario, which
 * imports the service into a narrowed JPA test context; here a missing or self-invoked outer
 * transaction (REQ-004) or a mis-wired service bean would be detectable. The controller's HTTP
 * serialization of the conflict is the API layer's obligation and is not claimed here.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationSeriesConflictIntegrationTest extends SeriesIntegrationFixtureSupport {

    /**
     * SCN-011#A1 - the identical series request is first accepted on a free control room, proving
     * the request itself is valid; on the contested room, whose occurrence 1 interval already holds
     * a one-off booking, it must be rejected with conflict semantics and persist zero rows of its
     * own series while leaving the pre-existing booking untouched.
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-011")
    @Tag("fault_model:transaction_rollback")
    void seriesOverlappingExistingOneOffIsRejectedWithConflictAndCreatesNoBookings() {
        BookableResource contestedRoom = createRoom("SCN011-A1-ROOM");
        BookableResource controlRoom = createRoom("SCN011-A1-CONTROL");
        String seriesTitle = "Weekly planning";
        Instant occurrenceOneStart = SERIES_START.toInstant();

        Reservation existingOneOff = reservationRepository.save(new Reservation(
                contestedRoom, "carol", "Existing one-off",
                occurrenceOneStart.plus(Duration.ofMinutes(10)),
                occurrenceOneStart.plus(Duration.ofMinutes(40)),
                ReservationStatus.CONFIRMED, FIXED_NOW));

        ReservationSeriesView control = service.createSeries(
                weeklySeries(controlRoom.getId(), seriesTitle, SERIES_OCCURRENCES), "alice");
        assertEquals(SERIES_OCCURRENCES, control.reservations().size(),
                "control fixture: the identical series must be accepted on a room without the one-off booking");
        assertEquals(SERIES_OCCURRENCES, rowsFor(controlRoom.getId()).size(),
                "control fixture: the accepted series must persist one row per occurrence");

        RuntimeException rejection = assertThrows(RuntimeException.class,
                () -> service.createSeries(
                        weeklySeries(contestedRoom.getId(), seriesTitle, SERIES_OCCURRENCES), "alice"),
                "a series whose occurrence 1 overlaps an existing one-off booking must be rejected");
        assertConflictRejection(rejection);

        List<Reservation> rows = rowsFor(contestedRoom.getId());
        assertEquals(1, rows.size(),
                "the rejected series must not create any booking, found " + rows.size() + " row(s)");
        assertEquals(existingOneOff.getId(), rows.get(0).getId(),
                "the only remaining row must be the pre-existing one-off booking");
        assertEquals("Existing one-off", rows.get(0).getTitle(),
                "the pre-existing one-off booking must not be removed or replaced");
        assertEquals(0L, rowCount(contestedRoom.getId(), seriesTitle),
                "no reservation row from the rejected series may be persisted");

        for (int index = 0; index < SERIES_OCCURRENCES; index++) {
            Instant occurrenceStart = SERIES_START.plusWeeks(index).toInstant();
            String message = "occurrence " + (index + 1) + " must not be persisted after the rejection";
            assertFalse(rows.stream().anyMatch(row -> occurrenceStart.equals(row.getStartsAt())), message);
        }
    }
}
