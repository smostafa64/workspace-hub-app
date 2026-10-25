package com.example.workspace.data;

import com.example.workspace.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findTop100ByOrderByOccurredAtDesc();
}
