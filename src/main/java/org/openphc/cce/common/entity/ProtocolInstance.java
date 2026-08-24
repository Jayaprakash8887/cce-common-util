package org.openphc.cce.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.openphc.cce.common.enums.ProtocolInstanceStatus;
import org.openphc.cce.common.support.UuidV7Generator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "protocol_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProtocolInstance {

    @Id
    @UuidGenerator(algorithm = UuidV7Generator.class)
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private String patientId;

    // No protocol_canonical: it is protocolDefinition.getCanonical() (url|version), and since
    // (url, version) is unique with a new row per version, the join is stable rather than a snapshot.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_definition_id", nullable = false)
    private ProtocolDefinition protocolDefinition;

    @Column(name = "enrolled_at", nullable = false)
    private OffsetDateTime enrolledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProtocolInstanceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    }
}
