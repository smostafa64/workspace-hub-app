package com.example.workspace.service;

import com.example.workspace.api.*;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceServiceTest {
    private final ResourceRepository repo = mock(ResourceRepository.class);
    private final ResourceService service = new ResourceService(repo);

    @Test void duplicateCodeIsRejected() {
        ResourceInput input = new ResourceInput("ALPHA", "Conference", ResourceType.ROOM, "HQ", 1, 8, true, false);
        when(repo.findByCode("ALPHA")).thenReturn(Optional.of(
                new BookableResource("ALPHA", "Existing", ResourceType.ROOM, "HQ", 1, 8, true, false)));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.create(input)).status());
        verify(repo, never()).save(any());
    }
    @Test void resourceCodeCannotChangeDuringUpdate() {
        when(repo.findById(1L)).thenReturn(Optional.of(
                new BookableResource("ALPHA", "Existing", ResourceType.ROOM, "HQ", 1, 8, true, false)));
        ResourceInput input = new ResourceInput("NEW-CODE", "Changed", ResourceType.ROOM, "HQ", 1, 8, true, false);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.update(1L, input)).status());
    }
}
