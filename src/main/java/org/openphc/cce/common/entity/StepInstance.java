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

    /**
     * The deadline this step's work was expected to be recorded by, as the protocol definition sets it.
     *
     * <p>The functional deadline, and what {@link SlaStatus#MET} is measured against: the Step SLA
     * Service settles a step as on time when {@link #completedAt} falls before this. A breach is not
     * asked of it — {@link SlaStatus#OVERDUE} and {@link SlaStatus#MISSED} are decided by the
     * {@code process_by} of the transition row that detects them, which is what a schedule is for.
     * Written once by the Matcher Service when it creates the step, in the same transaction as the
     * step's {@code step_sla_state_transition} rows, and never updated — a deadline that moved would
     * silently redate every judgement already made against it.
     *
     * <p>Distinct from the {@code process_by} of the step's {@code DUE_DATE_REACHED} row, which the two
     * carry the same value as. That row schedules <em>when the sweep looks at this step</em>; a retry
     * defers it through {@code next_attempt_at}, and it exists to be claimed, marked processed and
     * counted against. This column says what the step was <em>due</em>. Keeping them apart is what stops
     * a change to the sweep's scheduling from changing what "on time" means.
     *
     * <p>Nullable: a step created from its own trigger has no deadline to be judged against, and stays
     * null along with its {@code sla_status}.
     */
    @Column(name = "due_date")
    private OffsetDateTime dueDate;

    // The missed date is not stored here: it is the process_by of the step's MISSED_DATE_REACHED row,
    // which is both the schedule for writing MISSED off and the threshold that verdict is measured
    // against — see StepSlaScheduleService.

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
