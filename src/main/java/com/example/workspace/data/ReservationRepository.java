package com.example.workspace.data;

import com.example.workspace.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    List<Reservation> findByBookedByOrderByStartsAtDesc(String bookedBy);
    List<Reservation> findAllByOrderByStartsAtDesc();

    @Query("""
            select case when count(r) > 0 then true else false end
            from Reservation r
            where r.resource.id = :resourceId
            and r.status in (com.example.workspace.domain.ReservationStatus.PENDING,
                             com.example.workspace.domain.ReservationStatus.CONFIRMED)
            and r.startsAt < :endAt and r.endsAt > :startAt
            """)
    boolean existsConflictingReservation(@Param("resourceId") Long resourceId,
                                          @Param("startAt") Instant startAt,
                                          @Param("endAt") Instant endAt);
}
