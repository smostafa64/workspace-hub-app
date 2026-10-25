package com.example.workspace.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private String actor;
    @Column(nullable = false) private String action;
    @Column(nullable = false) private String target;
    @Column(nullable = false) private Instant occurredAt;
    protected AuditEvent() { }
    public AuditEvent(String actor, String action, String target, Instant occurredAt) {
        this.actor = actor; this.action = action; this.target = target; this.occurredAt = occurredAt;
    }
    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public Instant getOccurredAt() { return occurredAt; }
}
