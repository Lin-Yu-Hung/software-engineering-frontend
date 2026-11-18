package com.booking.court;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "courts")
public class Court {
    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourtStatus status;

    @Column(nullable = false)
    private int displayOrder;

    @Version
    private long version;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Court() {
        // Required by JPA; public API responses use CourtResponse instead.
    }

    public Court(String name, String type, CourtStatus status, int displayOrder, OffsetDateTime now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.status = status;
        this.displayOrder = displayOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String type, CourtStatus status, Integer displayOrder, OffsetDateTime now) {
        if (name != null) this.name = name.strip();
        if (type != null) this.type = type.strip();
        if (status != null) this.status = status;
        if (displayOrder != null) this.displayOrder = displayOrder;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getType() { return type; }
    public CourtStatus getStatus() { return status; }
    public int getDisplayOrder() { return displayOrder; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
