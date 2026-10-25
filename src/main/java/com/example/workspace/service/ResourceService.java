package com.example.workspace.service;

import com.example.workspace.api.*;
import com.example.workspace.data.ResourceRepository;
import com.example.workspace.domain.BookableResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ResourceService {
    private final ResourceRepository resources;
    public ResourceService(ResourceRepository resources) { this.resources = resources; }

    @Transactional(readOnly = true)
    public List<ResourceView> list() {
        return resources.findAll().stream().map(ResourceView::of).toList();
    }
    @Transactional
    public ResourceView create(ResourceInput input) {
        if (resources.findByCode(input.code()).isPresent())
            throw new ApiException(HttpStatus.CONFLICT, "Resource code already exists");
        BookableResource resource = new BookableResource(input.code(), input.name(), input.type(),
                input.building(), input.floorNumber(), input.capacity(), input.active(), input.approvalRequired());
        return ResourceView.of(resources.save(resource));
    }
    @Transactional
    public ResourceView update(Long id, ResourceInput input) {
        BookableResource resource = resources.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Resource not found"));
        if (!resource.getCode().equals(input.code()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "Resource code cannot be changed");
        resource.update(input.name(), input.type(), input.building(), input.floorNumber(),
                input.capacity(), input.active(), input.approvalRequired());
        return ResourceView.of(resource);
    }
}
