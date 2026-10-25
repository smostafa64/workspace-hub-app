package com.example.workspace.api;

import com.example.workspace.domain.BookableResource;
import com.example.workspace.domain.ResourceType;

public record ResourceView(Long id, String code, String name, ResourceType type, String building,
                           Integer floorNumber, int capacity, boolean active, boolean approvalRequired) {
    public static ResourceView of(BookableResource r) {
        return new ResourceView(r.getId(), r.getCode(), r.getName(), r.getType(), r.getBuilding(),
                                r.getFloorNumber(), r.getCapacity(), r.isActive(), r.isApprovalRequired());
    }
}
