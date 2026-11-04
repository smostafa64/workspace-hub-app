package com.example.workspace.integration;

import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.api.ReservationSeriesView;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration-layer obligation SCN-017#A1 (job WBATCH-007): resubmitting a byte-identical series
 * request is rejected as an overlap conflict and adds no additional bookings.
 *
 * <p>Boundary: the full Spring Boot application context is loaded (no slice, no mocks) and the
 * production {@link com.example.workspace.service.ReservationService} bean is driven through its
 * Spring proxy, so the application's own transaction advice, connection pool and conflict query
 * configuration are the ones exercised. Committed rows are read back through JPQL in a separate
 * persistence context, so the "no additional booking" observation is made on the durable table and
 * not on the service return value. The HTTP serialization of the conflict is the API layer's
 * obligation and is not claimed here.</p>
 *
 * <p>A control room receives the identical series values while still unbooked; it proves the
 * request values are valid, so the rejection on the contested room can only be caused by the
 * already-persisted bookings.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationSeriesResubmissionIntegrationTest extends SeriesIntegrationFixtureSupport {

    /**
     * SCN-017#A1 - after a successful series creation, resubmitting the identical series request is
     * rejected as an overlap conflict and leaves the room with exactly the original bookings.
     *
     * <p>The resubmitted request is rebuilt from exactly the same constructor arguments as the
     * first submission (same room, title, first start/end, timezone, frequency and occurrence
     * count), i.e. byte-identical content. The row ids, seriesIds and start instants are captured
     * before the resubmission and must be unchanged afterwards, which rules out replacement as well
     * as duplication.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-017")
    @Tag("fault_model:idempotency")
    void identicalSeriesResubmissionIsRejectedWithConflictAndAddsNoBookings() {
        BookableResource room = createRoom("SCN017-A1");
        BookableResource controlRoom = createRoom("SCN017-A1-CTRL");
        String seriesTitle = "Weekly planning";

        ReservationSeriesInput firstSubmission = weeklySeries(room.getId(), seriesTitle, SERIES_OCCURRENCES);
        service.createSeries(firstSubmission, "alice");

        List<Reservation> afterFirstSubmission = rowsFor(room.getId());
        assertEquals(SERIES_OCCURRENCES, afterFirstSubmission.size(),
                "fixture: the first submission must persist one booking per occurrence, found "
                        + afterFirstSubmission.size());
        Set<Long> rowIdsBeforeResubmission = afterFirstSubmission.stream()
                .map(Reservation::getId)
                .collect(Collectors.toSet());
        Set<UUID> seriesIdsBeforeResubmission = afterFirstSubmission.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toSet());
        Set<Instant> startsBeforeResubmission = afterFirstSubmission.stream()
                .map(Reservation::getStartsAt)
                .collect(Collectors.toSet());

        ReservationSeriesView control = service.createSeries(
                weeklySeries(controlRoom.getId(), seriesTitle, SERIES_OCCURRENCES), "alice");
        assertEquals(SERIES_OCCURRENCES, control.reservations().size(),
                "control fixture: the identical series values against an unbooked room must be accepted");
        assertEquals(SERIES_OCCURRENCES, rowsFor(controlRoom.getId()).size(),
                "control fixture: the accepted series must persist one booking per occurrence");

        ReservationSeriesInput resubmission = weeklySeries(room.getId(), seriesTitle, SERIES_OCCURRENCES);

        RuntimeException rejection = assertThrows(RuntimeException.class,
                () -> service.createSeries(resubmission, "alice"),
                "resubmitting the byte-identical series request must be rejected");
        assertConflictRejection(rejection);

        List<Reservation> afterResubmission = rowsFor(room.getId());
        assertEquals(SERIES_OCCURRENCES, afterResubmission.size(),
                "the rejected resubmission must not add any booking, found " + afterResubmission.size());
        assertEquals(rowIdsBeforeResubmission,
                afterResubmission.stream().map(Reservation::getId).collect(Collectors.toSet()),
                "the rejected resubmission must not replace or duplicate the existing booking rows");
        assertEquals(seriesIdsBeforeResubmission,
                afterResubmission.stream().map(Reservation::getSeriesId).collect(Collectors.toSet()),
                "the existing bookings must retain the original series identity");
        assertEquals(startsBeforeResubmission,
                afterResubmission.stream().map(Reservation::getStartsAt).collect(Collectors.toSet()),
                "no duplicate occurrence interval may be added by the rejected resubmission");
        assertEquals((long) SERIES_OCCURRENCES, rowCount(room.getId(), seriesTitle),
                "the room must still hold exactly the original bookings under the series title");
    }
}
