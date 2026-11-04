package com.example.workspace.integration;

import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ReservationStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration-layer obligation SCN-013#A2 (job WBATCH-006): all occurrences of a series for an
 * approval-required room persist the PENDING approval state in the database.
 *
 * <p>Boundary: the real Spring Boot application context is loaded and the production
 * {@link com.example.workspace.service.ReservationService} bean is driven through its Spring proxy.
 * The durable rows are read back through JPQL in a separate persistence context. The API-layer
 * obligations SCN-013#A1 and SCN-013#A3 are not claimed here.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import(SeriesTestClockConfig.class)
class ReservationSeriesApprovalIntegrationTest extends SeriesIntegrationFixtureSupport {

    /**
     * SCN-013#A2 - every occurrence of a three-occurrence series on a room configured to require
     * approval is stored with the PENDING approval state.
     *
     * <p>The same request is also applied to a free room that does not require approval; that
     * control only proves the request is accepted (three rows persisted) so the PENDING rows cannot
     * come from a rejected or partially created series. Its status values are not asserted, because
     * expected statuses for the non-approval room belong to other frozen assertions.</p>
     */
    @Test
    @Tag("observation_boundary:integration")
    @Tag("scenario:SCN-013")
    void allSeriesOccurrencesPersistPendingApprovalStateInTheDatabase() {
        BookableResource approvalRoom = createRoom("SCN013-A2-APPROVAL", true, true);
        BookableResource controlRoom = createRoom("SCN013-A2-CONTROL", true, false);
        String seriesTitle = "Weekly planning";

        service.createSeries(weeklySeries(approvalRoom.getId(), seriesTitle, SERIES_OCCURRENCES), "alice");
        service.createSeries(weeklySeries(controlRoom.getId(), seriesTitle, SERIES_OCCURRENCES), "alice");

        assertEquals(SERIES_OCCURRENCES, rowsFor(controlRoom.getId()).size(),
                "control fixture: the identical series request must be accepted on a free room");

        List<Reservation> rows = rowsFor(approvalRoom.getId());
        assertEquals(SERIES_OCCURRENCES, rows.size(),
                "every occurrence of the series must be persisted for the approval-required room, found "
                        + rows.size());

        for (Reservation row : rows) {
            assertEquals(ReservationStatus.PENDING, row.getStatus(),
                    "the stored occurrence starting " + row.getStartsAt()
                            + " must persist the PENDING approval state, was " + row.getStatus());
        }

        Long pendingRows = entityManager.createQuery(
                        "select count(r) from Reservation r where r.resource.id = :resourceId and r.status = :status",
                        Long.class)
                .setParameter("resourceId", approvalRoom.getId())
                .setParameter("status", ReservationStatus.PENDING)
                .getSingleResult();
        assertEquals((long) SERIES_OCCURRENCES, pendingRows.longValue(),
                "all persisted occurrences of the approval-required series must be PENDING, found "
                        + pendingRows + " PENDING row(s)");
    }
}
