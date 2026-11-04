package com.example.workspace.integration;

import com.example.workspace.api.ApiException;
import com.example.workspace.api.ReservationSeriesInput;
import com.example.workspace.data.ReservationRepository;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.Reservation;
import com.example.workspace.domain.ResourceType;
import com.example.workspace.service.ReservationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared fixtures for the integration-layer reservation-series tests (job WBATCH-006).
 *
 * <p>Every concrete test class loads the real Spring Boot application context and drives the
 * production {@link ReservationService} through its Spring proxy, i.e. through the application's
 * own transaction infrastructure and connection pool. Durable state is read back through JPQL in a
 * separate persistence context, so committed and rolled-back rows are observed independently of
 * the service return value.</p>
 *
 * <p>Rooms created by a test are registered before any assertion so {@link #removeCreatedFixtures()}
 * always removes them, keeping the cached application context isolated between tests.</p>
 */
abstract class SeriesIntegrationFixtureSupport {

    protected static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    protected static final Instant FIXED_NOW = SeriesTestClockConfig.FIXED_NOW;
    protected static final Duration OCCURRENCE_DURATION = Duration.ofMinutes(60);
    protected static final ZonedDateTime SERIES_START = ZonedDateTime.of(2030, 5, 6, 10, 0, 0, 0, BERLIN);
    protected static final int SERIES_OCCURRENCES = 3;

    @Autowired
    protected ReservationService service;

    @Autowired
    protected ResourceRepository resourceRepository;

    @Autowired
    protected ReservationRepository reservationRepository;

    @PersistenceContext
    protected EntityManager entityManager;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    private final List<Long> createdResourceIds = new ArrayList<>();

    @AfterEach
    void removeCreatedFixtures() {
        for (Long resourceId : createdResourceIds) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                List<Reservation> reservations = entityManager.createQuery(
                                "select r from Reservation r where r.resource.id = :resourceId",
                                Reservation.class)
                        .setParameter("resourceId", resourceId)
                        .getResultList();
                for (Reservation reservation : reservations) {
                    entityManager.remove(reservation);
                }
                BookableResource room = entityManager.find(BookableResource.class, resourceId);
                if (room != null) {
                    entityManager.remove(room);
                }
            });
        }
        createdResourceIds.clear();
    }

    protected BookableResource createRoom(String codePrefix) {
        return createRoom(codePrefix, true, false);
    }

    protected BookableResource createRoom(String codePrefix, boolean active, boolean approvalRequired) {
        BookableResource room = resourceRepository.save(new BookableResource(
                codePrefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                "Room " + codePrefix, ResourceType.ROOM, "HQ", 1, 8, active, approvalRequired));
        createdResourceIds.add(room.getId());
        return room;
    }

    protected ReservationSeriesInput weeklySeries(Long resourceId, String title, int occurrences) {
        return weeklySeries(resourceId, title, SERIES_START, occurrences);
    }

    protected ReservationSeriesInput weeklySeries(Long resourceId, String title, ZonedDateTime firstStart,
                                                  int occurrences) {
        ZonedDateTime firstEnd = firstStart.plus(OCCURRENCE_DURATION);
        return new ReservationSeriesInput(resourceId, title,
                firstStart.toOffsetDateTime(), firstEnd.toOffsetDateTime(),
                BERLIN.getId(), "WEEKLY", occurrences);
    }

    protected List<Reservation> rowsFor(long resourceId) {
        return entityManager.createQuery(
                        "select r from Reservation r where r.resource.id = :resourceId order by r.startsAt",
                        Reservation.class)
                .setParameter("resourceId", resourceId)
                .getResultList();
    }

    protected long rowCount(long resourceId, String title) {
        return entityManager.createQuery(
                        "select count(r) from Reservation r where r.resource.id = :resourceId and r.title = :title",
                        Long.class)
                .setParameter("resourceId", resourceId)
                .setParameter("title", title)
                .getSingleResult();
    }

    /**
     * The approved rejections require conflict semantics (HTTP 409), not an unrelated validation
     * or infrastructure failure. When the application surfaces an {@link ApiException} its status
     * is checked directly; if the application raises its dedicated conflict exception instead, the
     * exception type itself must identify a conflict. The controller's HTTP serialization of that
     * rejection is the API layer's obligation and is not claimed here.
     */
    protected void assertConflictRejection(RuntimeException failure) {
        if (failure instanceof ApiException apiException) {
            assertEquals(HttpStatus.CONFLICT, apiException.status(),
                    "the series rejection must carry the 409 conflict status, was " + apiException.status());
            return;
        }
        String simpleName = failure.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        assertTrue(simpleName.contains("conflict"),
                "the series rejection must be a conflict rejection, not an unrelated failure; was "
                        + failure.getClass().getName() + ": " + failure.getMessage());
    }
}
