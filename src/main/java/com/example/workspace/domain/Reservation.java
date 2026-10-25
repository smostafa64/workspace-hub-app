package com.example.workspace.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations", indexes = @Index(name = "idx_reservation_resource_time", columnList = "resource_id,starts_at,ends_at"))
public class Reservation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "resource_id", nullable = false) private BookableResource resource;
    @Column(nullable = false) private String bookedBy;
    @Column(nullable = false) private String title;
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(name = "ends_at", nullable = false) private Instant endsAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ReservationStatus status;
    @Column(nullable = false) private Instant createdAt;
    @Column(name = "series_id", nullable = true) private UUID seriesId;

    protected Reservation() { }

    public Reservation(BookableResource resource, String bookedBy, String title,
                       Instant startsAt, Instant endsAt, ReservationStatus status, Instant createdAt) {
        this(resource, bookedBy, title, startsAt, endsAt, status, createdAt, null);
    }

    public Reservation(BookableResource resource, String bookedBy, String title,
                       Instant startsAt, Instant endsAt, ReservationStatus status,
                       Instant createdAt, UUID seriesId) {
        this.resource = resource;
        this.bookedBy = bookedBy;
        this.title = title;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = status;
        this.createdAt = createdAt;
        this.seriesId = seriesId;
    }

    public Long getId() { return id; }
    public BookableResource getResource() { return resource; }
    public String getBookedBy() { return bookedBy; }
    public String getTitle() { return title; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public ReservationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getSeriesId() { return seriesId; }
    public void setStatus(ReservationStatus value) { status = value; }
    public void setSeriesId(UUID value) { seriesId = value; }
}
