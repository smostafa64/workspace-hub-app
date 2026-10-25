package com.example.workspace.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "bookable_resources", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class BookableResource {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ResourceType type;
    @Column(nullable = false) private String building;
    private Integer floorNumber;
    @Column(nullable = false) private int capacity;
    @Column(nullable = false) private boolean active;
    @Column(nullable = false) private boolean approvalRequired;

    protected BookableResource() { }
    public BookableResource(String code, String name, ResourceType type, String building, Integer floorNumber,
                            int capacity, boolean active, boolean approvalRequired) {
        this.code = code; this.name = name; this.type = type; this.building = building;
        this.floorNumber = floorNumber; this.capacity = capacity; this.active = active;
        this.approvalRequired = approvalRequired;
    }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public ResourceType getType() { return type; }
    public String getBuilding() { return building; }
    public Integer getFloorNumber() { return floorNumber; }
    public int getCapacity() { return capacity; }
    public boolean isActive() { return active; }
    public boolean isApprovalRequired() { return approvalRequired; }
    public void update(String name, ResourceType type, String building, Integer floorNumber, int capacity,
                       boolean active, boolean approvalRequired) {
        this.name = name; this.type = type; this.building = building;
        this.floorNumber = floorNumber; this.capacity = capacity;
        this.active = active; this.approvalRequired = approvalRequired;
    }
}
