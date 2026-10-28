package org.openphc.cce.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.openphc.cce.common.enums.SlaStatus;
import org.openphc.cce.common.support.UuidV7Generator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One instance of a step group for one enrollment: the steps of a group that happen together, such
 * as one ANC visit. A repeating group has one row per cycle.
 *
 * <p>Groups form a tree: a nested group's instance points to its parent's instance, and each step
 * row points to its direct group's instance through {@code step_instance.step_group_instance_id}.
 *
 * <p>Only facts are stored. Whether a group instance is "partly done" is calculated from its steps when
 * needed; {@code completed_at} is stored because the Step SLA Service judges a group deadline from it
 * and parses no PlanDefinitions, so it cannot tell which members exist.
 */
@Entity
@Table(name = "step_group_instance", uniqueConstraints = @UniqueConstraint(
        name = "step_group_instance_cycle_key",
        columnNames = {"protocol_instance_id", "group_action_id", "parent_group_instance_id", "cycle_index"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepGroupInstance {

    @Id
    @UuidGenerator(algorithm = UuidV7Generator.class)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "protocol_instance_id", nullable = false)
    private ProtocolInstance protocolInstance;

    /** The enclosing group's instance, or null for a top-level group. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_group_instance_id")
    private StepGroupInstance parentGroupInstance;

    /** The group's {@code action.id} in the PlanDefinition. */
    @Column(name = "group_action_id", nullable = false)
    private String groupActionId;

    /** Which repeat of the group this is, 0-based; 0 for a group that doesn't repeat. */
    @Column(name = "cycle_index", nullable = false)
    @Builder.Default
    private int cycleIndex = 0;

    /**
     * When the instance's entry steps are due, as its predecessor, its parent or its schedule sets it.
     * Never the time of the event that happened to open it. Null when no planned due date can be worked
     * out.
     */
    @Column(name = "due_date")
    private OffsetDateTime dueDate;

    /** The group deadline's verdict. Written only by the Step SLA Service. */
    @Enumerated(EnumType.STRING)
    @Column(name = "sla_status")
    private SlaStatus slaStatus;

    /**
     * Clinical event time, in UTC, of whatever opened the instance. A calendar-day cycle is opened by
     * the first event of its day, so this also gives the cycle's day: its date in the deployment's time
     * zone. The day is not stored separately.
     */
    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt;

    /** Clinical event time, in UTC, the last mandatory member was completed. Written by the Matcher Service. */
    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /**
     * Clinical event time, in UTC, from which nothing more is expected of the instance. Written by the
     * Matcher Service.
     */
    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

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
