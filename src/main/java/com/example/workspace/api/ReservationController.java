package com.example.workspace.api;

import com.example.workspace.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReservationController {
    private final ReservationService service;
    public ReservationController(ReservationService service) { this.service = service; }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationView> create(@Valid @RequestBody BookingInput input, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(input, auth.getName()));
    }

    @PostMapping("/reservation-series")
    public ResponseEntity<ReservationSeriesView> createSeries(@Valid @RequestBody ReservationSeriesInput input, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSeries(input, auth.getName()));
    }

    @GetMapping("/reservations")
    public List<ReservationView> list(@RequestParam(defaultValue = "false") boolean all, Authentication auth) {
        return service.list(auth, all);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(@PathVariable Long id, Authentication auth) {
        return service.cancel(id, auth);
    }

    @PostMapping("/reservations/{id}/decision") @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ReservationView decision(@PathVariable Long id, @RequestBody Map<String, Boolean> body, Authentication auth) {
        if (body.get("approved") == null) throw new ApiException(HttpStatus.BAD_REQUEST, "approved is required");
        return service.decide(id, body.get("approved"), auth.getName());
    }

    @GetMapping("/resources/{resourceId}/availability")
    public Map<String, Object> availability(@PathVariable Long resourceId,
                                              @RequestParam Instant start, @RequestParam Instant end) {
        return Map.of("resourceId", resourceId, "available", service.isAvailable(resourceId, start, end));
    }
}
