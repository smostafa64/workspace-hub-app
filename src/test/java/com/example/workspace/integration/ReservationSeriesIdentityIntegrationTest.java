package com.example.workspace.integration;

import com.example.workspace.api.BookingInput;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration-layer obligations SCN-012#A2 and SCN-012#A4 (job WBATCH-006).
 *
 * <p>Boundary: the full Spring Boot application context is loaded and the production
 * {@link com.example.workspace.service.ReservationService} bean is driven through its Spring proxy
 * (real transaction manager, AOP proxy and connection pool). Rows are read back through JPQL in a
 * separate persistence context, so the observation is the committed reservation record rather than
 * the service return value. The response-shape obligation SCN-012#A1 is not claimed here.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationSeriesIdentityIntegrationTest extends SeriesIntegrationFixtureSupport {

    /**
     * SCN-012#A2 - each series booking row read back through the persistence layer stores the same
     * non-null seriesId value.
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-012")
    void everySeriesBookingRowStoresTheSameSeriesId() {
        BookableResource room = createRoom("SCN012-A2");

        service.createSeries(weeklySeries(room.getId(), "Weekly planning", SERIES_OCCURRENCES), "alice");

        List<Reservation> rows = rowsFor(room.getId());
        assertEquals(SERIES_OCCURRENCES, rows.size(),
                "the fixture requires one persisted row per requested occurrence, found " + rows.size());
        for (Reservation row : rows) {
            assertNotNull(row.getSeriesId(),
                    "every persisted series booking row must store a seriesId: " + row);
        }
        Set<UUID> distinctStoredSeriesIds = rows.stream()
                .map(Reservation::getSeriesId)
                .collect(Collectors.toSet());
        assertEquals(1, distinctStoredSeriesIds.size(),
                "all persisted rows of one series must store the same seriesId, found " + distinctStoredSeriesIds);
    }

    /**
     * SCN-012#A4 - the persisted record of each booking created by a series carries the series
     * identifier, and a one-off reservation created through the existing application path remains a
     * valid record with a null seriesId.
     *
     * <p>The one-off is persisted in a separate room so the series rows and the one-off row cannot
     * stand in for each other.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-012")
    void seriesRowsCarrySeriesIdWhileOneOffRowsRemainValidWithNullSeriesId() {
        BookableResource seriesRoom = createRoom("SCN012-A4-SERIES");
        BookableResource oneOffRoom = createRoom("SCN012-A4-ONEOFF");
        ZonedDateTime oneOffLocalStart = ZonedDateTime.of(2030, 5, 7, 9, 0, 0, 0, BERLIN);
        Instant oneOffStart = oneOffLocalStart.toInstant();
        Instant oneOffEnd = oneOffStart.plus(OCCURRENCE_DURATION);

        service.createSeries(weeklySeries(seriesRoom.getId(), "Weekly planning", SERIES_OCCURRENCES), "alice");
        service.create(new BookingInput(oneOffRoom.getId(), "One-off", oneOffStart, oneOffEnd), "alice");

        List<Reservation> seriesRows = rowsFor(seriesRoom.getId());
        assertEquals(SERIES_OCCURRENCES, seriesRows.size(),
                "the fixture requires one persisted row per occurrence of the series, found " + seriesRows.size());
        UUID seriesId = seriesRows.get(0).getSeriesId();
        assertNotNull(seriesId, "a persisted series booking record must carry a series identifier");
        for (Reservation row : seriesRows) {
            assertEquals(seriesId, row.getSeriesId(),
                    "every persisted series booking record must carry the series identifier");
        }
        assertEquals(0L, rowCount(oneOffRoom.getId(), "Weekly planning"),
                "series bookings must not leak into the one-off room");

        List<Reservation> oneOffRows = rowsFor(oneOffRoom.getId());
        assertEquals(1, oneOffRows.size(),
                "the one-off reservation must persist exactly one row, found " + oneOffRows.size());
        Reservation oneOff = oneOffRows.get(0);
        assertNull(oneOff.getSeriesId(),
                "a one-off reservation must remain a valid record with a null seriesId");
        assertEquals("One-off", oneOff.getTitle(),
                "the one-off record must keep its submitted title");
        assertEquals(oneOffStart, oneOff.getStartsAt(),
                "the one-off record must keep its submitted start instant");
        assertEquals(oneOffEnd, oneOff.getEndsAt(),
                "the one-off record must keep its submitted end instant");
        assertEquals(ReservationStatus.CONFIRMED, oneOff.getStatus(),
                "the one-off record must remain a valid confirmed reservation");
    }
}
