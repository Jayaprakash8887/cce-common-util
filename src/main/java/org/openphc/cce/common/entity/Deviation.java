package org.openphc.cce.common.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import org.openphc.cce.common.enums.DeviationType;
import org.openphc.cce.common.support.UuidV7Generator;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "deviation", uniqueConstraints = @UniqueConstraint(
        name = "deviation_step_type_key",
        columnNames = {"step_instance_id", "deviation_type"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Deviation {

    @Id
    @UuidGenerator(algorithm = UuidV7Generator.class)
    private UUID id;

    // No protocol_instance_id: it is reachable as stepInstance.protocolInstance, and storing it as
    // well left two columns to keep in step for no integrity gain.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "step_instance_id", nullable = false)
    private StepInstance stepInstance;

    @Enumerated(EnumType.STRING)
    @Column(name = "deviation_type", nullable = false)
    private DeviationType deviationType;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "intelligence_event_id")
    private UUID intelligenceEventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode metadata;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (detectedAt == null) detectedAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
