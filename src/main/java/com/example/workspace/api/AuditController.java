package com.example.workspace.api;

import com.example.workspace.data.AuditRepository;
import com.example.workspace.domain.AuditEvent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditRepository audits;
    public AuditController(AuditRepository audits) { this.audits = audits; }
    @GetMapping @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public List<AuditEvent> latest() { return audits.findTop100ByOrderByOccurredAtDesc(); }
}
