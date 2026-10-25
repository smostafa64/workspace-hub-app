package com.example.workspace.api;

import com.example.workspace.service.ResourceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {
    private final ResourceService service;
    public ResourceController(ResourceService service) { this.service = service; }
    @GetMapping public List<ResourceView> list() { return service.list(); }
    @PostMapping @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResourceView> create(@Valid @RequestBody ResourceInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(input));
    }
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
    public ResourceView update(@PathVariable Long id, @Valid @RequestBody ResourceInput input) {
        return service.update(id, input);
    }
}
