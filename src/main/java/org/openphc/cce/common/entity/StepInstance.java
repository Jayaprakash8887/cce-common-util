package org.openphc.cce.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.enums.StepStatus;
import org.openphc.cce.common.support.UuidV7Generator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "step_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepInstance {

    @Id
    @UuidGenerator(algorithm = UuidV7Generator.class)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_instance_id", nullable = false)
    private ProtocolInstance protocolInstance;

    @Column(name = "action_id", nullable = false)
    private String actionId;

    @Column(name = "repeat_index", nullable = false)
    @Builder.Default
    private int repeatIndex = 0;

    /** Has the expected event been received? Independent of timeliness — see {@link #slaStatus}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "step_status", nullable = false)
    private StepStatus stepStatus;

    /**
     * Has the SLA been met? Independent of whether the work was recorded — see {@link #stepStatus}.
     *
     * <p><strong>Nullable, and null is meaningful:</strong> timeliness has not been judged yet — no
     * threshold has fallen due and the step has not been completed either, so there is nothing to judge
     * it on. A step with no due date at all stays null for good. Written only by the Step SLA Service,
     * as it applies {@code step_sla_state_transition} rows: at a threshold for a step still outstanding,
     * or on the next sweep after completion, when {@code completed_at} settles the answer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sla_status")
    private SlaStatus slaStatus;

    // SLA thresholds are not stored here. Each one is a step_sla_state_transition row carrying its
    // process_by time — see StepSlaScheduleService, which writes them and reads them back.

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "completed_by_source")
    private String completedBySource;

    /** The {@code matcher_event_log} row whose event matched this step. FK; null until matched. */
    @Column(name = "matched_event_id")
    private UUID matchedEventId;

    @Column(name = "required_behavior")
    private String requiredBehavior;

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
