package com.example.workspace.service;

import com.example.workspace.api.*;
import com.example.workspace.data.*;
import com.example.workspace.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

abstract class SeriesDatabaseContract {
    @Autowired ReservationService service;
    @Autowired ResourceRepository resources;
    @Autowired ReservationRepository reservations;
    @Autowired AuditRepository audits;
    @Autowired JdbcTemplate jdbc;
    private BookableResource room;
    private final Instant start = Instant.parse("2030-01-06T10:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean @Primary Clock deterministicQaClock() {
            return Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @BeforeEach void isolate() {
        room = resources.saveAndFlush(new BookableResource("QA-" + UUID.randomUUID().toString().substring(0, 20),
                "Independent QA", ResourceType.ROOM, "QA", 1, 5, true, false));
    }

    @AfterEach void cleanup() {
        reservations.deleteAll(reservations.findAll().stream().filter(item -> item.getResource().getId().equals(room.getId())).toList());
        resources.deleteById(room.getId());
    }

    private ReservationSeriesInput series() {
        return new ReservationSeriesInput(room.getId(), "Atomic QA", start.atOffset(ZoneOffset.UTC),
                start.plusSeconds(3600).atOffset(ZoneOffset.UTC), "UTC", "WEEKLY", 3);
    }

    private BookingInput single(int week) {
        Instant from = start.plus(Duration.ofDays(7L * week));
        return new BookingInput(room.getId(), "Existing QA", from, from.plusSeconds(3600));
    }

    private List<Reservation> rows() {
        return reservations.findAll().stream().filter(item -> item.getResource().getId().equals(room.getId())).toList();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void conflictOnSecondOrThirdOccurrenceLeavesNoSeriesRowsOrAuditEvents(int week) {
        ReservationView existing = service.create(single(week), "bob");
        long auditCount = audits.count();
        ReservationConflictException failure = assertThrows(ReservationConflictException.class, () -> service.createSeries(series(), "alice"));
        assertEquals(List.of(week + 1), failure.getConflicts().stream().map(ReservationConflictItem::occurrenceIndex).toList());
        assertEquals(List.of(existing.id()), rows().stream().map(Reservation::getId).toList());
        assertNull(rows().getFirst().getSeriesId());
        assertEquals(auditCount, audits.count());
    }

    @Test void multipleConflictsReportAllDatesAndLeaveDatabaseUnchanged() {
        service.create(single(1), "bob");
        service.create(single(2), "bob");
        ReservationConflictException failure = assertThrows(ReservationConflictException.class, () -> service.createSeries(series(), "alice"));
        assertEquals(List.of(2, 3), failure.getConflicts().stream().map(ReservationConflictItem::occurrenceIndex).toList());
        assertEquals(List.of(start.plus(Duration.ofDays(7)), start.plus(Duration.ofDays(14))),
                failure.getConflicts().stream().map(item -> item.startsAt().toInstant()).toList());
        assertEquals(2, rows().size());
        assertTrue(rows().stream().allMatch(item -> item.getSeriesId() == null));
    }

    @Test void databaseFailureDuringSecondInsertRollsBackFirstInsertAndItsAudit() {
        long before = audits.count();
        jdbc.execute("ALTER TABLE reservations ADD CONSTRAINT qa_second_insert CHECK (resource_id <> " + room.getId()
                + " OR starts_at <> TIMESTAMP WITH TIME ZONE '2030-01-13 10:00:00+00:00')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.createSeries(series(), "alice"));
            assertTrue(rows().isEmpty());
            assertEquals(before, audits.count());
        } finally {
            jdbc.execute("ALTER TABLE reservations DROP CONSTRAINT qa_second_insert");
        }
    }

    @Test void cancellationPreservesMembershipAndOtherOccurrences() {
        ReservationSeriesView created = service.createSeries(series(), "alice");
        Long selected = created.reservations().get(1).id();
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                () -> service.cancel(selected, new UsernamePasswordAuthenticationToken("bob", "", List.of()))).status());
        assertEquals(ReservationStatus.CANCELLED, service.cancel(selected,
                new UsernamePasswordAuthenticationToken("alice", "", List.of())).status());
        assertEquals(3, rows().size());
        rows().forEach(item -> {
            assertEquals(created.seriesId(), item.getSeriesId());
            assertEquals(item.getId().equals(selected) ? ReservationStatus.CANCELLED : ReservationStatus.CONFIRMED, item.getStatus());
        });
        assertTrue(service.isAvailable(room.getId(), single(1).startsAt(), single(1).endsAt()));
        assertFalse(service.isAvailable(room.getId(), start, start.plusSeconds(3600)));
    }

    @Test void approvalRequiredSeriesBlocksCompetitionBeforeAndAfterIndividualApproval() {
        room.update(room.getName(), ResourceType.ROOM, "QA", 1, 5, true, true);
        resources.saveAndFlush(room);
        ReservationSeriesView created = service.createSeries(series(), "alice");
        assertTrue(created.reservations().stream().allMatch(item -> item.status() == ReservationStatus.PENDING));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.create(single(0), "bob")).status());
        assertEquals(ReservationStatus.CONFIRMED, service.decide(created.reservations().getFirst().id(), true, "manager").status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.create(single(0), "bob")).status());
        assertEquals(1, rows().stream().filter(item -> item.getStatus() == ReservationStatus.CONFIRMED).count());
        assertEquals(2, rows().stream().filter(item -> item.getStatus() == ReservationStatus.PENDING).count());
    }

    @RepeatedTest(10) void simultaneousSeriesCannotBothSucceed() throws Exception { compete(true, true); }
    @RepeatedTest(10) void simultaneousSeriesAndSingleCannotBothSucceed() throws Exception { compete(true, false); }
    @RepeatedTest(10) void simultaneousSinglesCannotBothSucceed() throws Exception { compete(false, false); }

    private void compete(boolean firstSeries, boolean secondSeries) throws Exception {
        CyclicBarrier ready = new CyclicBarrier(3);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> attempt(ready, firstSeries, "alice"));
            Future<Integer> second = executor.submit(() -> attempt(ready, secondSeries, "bob"));
            ready.await(10, TimeUnit.SECONDS);
            List<Integer> statuses = new ArrayList<>(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            Collections.sort(statuses);
            assertEquals(List.of(201, 409), statuses);
            List<Reservation> stored = rows();
            assertEquals(1, stored.stream().filter(item -> item.getStartsAt().equals(start)).count());
            assertTrue(stored.size() == 1 || stored.size() == 3);
            if (stored.size() == 3) {
                assertNotNull(stored.getFirst().getSeriesId());
                assertEquals(1, stored.stream().map(Reservation::getSeriesId).distinct().count());
            }
            for (Reservation left : stored) {
                for (Reservation right : stored) {
                    if (!left.getId().equals(right.getId())) {
                        assertFalse(left.getStartsAt().isBefore(right.getEndsAt()) && left.getEndsAt().isAfter(right.getStartsAt()));
                    }
                }
            }
        }
    }

    private int attempt(CyclicBarrier ready, boolean recurring, String actor) throws Exception {
        ready.await(10, TimeUnit.SECONDS);
        try {
            if (recurring) service.createSeries(series(), actor);
            else service.create(single(0), actor);
            return 201;
        } catch (ReservationConflictException conflict) {
            return 409;
        } catch (ApiException failure) {
            return failure.status().value();
        }
    }
}
