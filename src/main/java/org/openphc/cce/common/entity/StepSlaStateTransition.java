package org.openphc.cce.common.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import org.openphc.cce.common.enums.SlaTransitionType;
import org.openphc.cce.common.support.UuidV7Generator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One scheduled SLA transition for a step: when it becomes due, and whether it has been applied.
 *
 * <p>Every threshold a step's SLA can cross gets a row here, written in the same transaction as the
 * step itself — so a step never exists without its schedule. Holding the deadline here rather than
 * denormalized on {@code step_instance} means the evaluating service selects from a set that shrinks as
 * work is processed. And because rows are retained after processing rather than deleted, the table
 * doubles as the durable record of when each threshold was reached and applied.
 *
 * <h2>Ownership</h2>
 * This service <strong>only creates</strong> these rows. Deciding which are due, applying the
 * {@code sla_status} change, recording the resulting {@code OVERDUE} / {@code MISSED} deviation, and
 * setting {@link #isProcessed()} / {@link #getAttempts()} all belong to the service that evaluates them.
 * Nothing here writes those fields, which keeps a single writer per column and lets the evaluator claim
 * rows without racing this service.
 *
 * <p>That split has one consequence worth stating: a step may complete before its row is processed, so
 * the row outlives the state it was scheduled against. Such a row is not meaningless — the evaluator
 * judges a completed step against its {@code step_instance.completed_at} rather than the wall clock, and
 * fires the transition when the step was completed past this row's {@code process_by}. Matcher then
 * records the breach without moving the SLA, which completion already settled against the same
 * threshold. A step completed before {@code process_by} breached nothing and the row is simply consumed.
 */
@Entity
@Table(name = "step_sla_state_transition", uniqueConstraints = @UniqueConstraint(
        name = "step_sla_state_transition_step_type_key",
        columnNames = {"step_instance_id", "transition_type"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepSlaStateTransition {

    @Id
    @UuidGenerator(algorithm = UuidV7Generator.class)
    private UUID id;

    @Column(name = "step_instance_id", nullable = false)
    private UUID stepInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transition_type", nullable = false)
    private SlaTransitionType transitionType;

    /**
     * The SLA status the step must be in for this transition to apply, and the one it moves to.
     *
     * <p>Stored as text rather than as an enum for the same reason the history tables are: the value is
     * copied from the SLA vocabulary at write time, and a constraint or mapping that lagged a future
     * enum change would reject reads of rows that were valid when written.
     */
    @Column(name = "from_status", nullable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false)
    private String toStatus;

    /**
     * Absolute time this transition becomes due — the clinical-time-anchored threshold computed when
     * the step was created. Immutable: it is the audit truth for when the deadline fell.
     */
    @Column(name = "process_by", nullable = false)
    private OffsetDateTime processBy;

    /** Set by the evaluating service once the transition has been applied. Never written here. */
    @Column(name = "is_processed", nullable = false)
    @Builder.Default
    private boolean processed = false;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;

    @Column(name = "processed_by")
    private String processedBy;

    /** Retry bookkeeping owned by the evaluating service. */
    @Column(nullable = false)
    @Builder.Default
    private int attempts = 0;

    /**
     * The gate the evaluator selects on, initialized to {@link #processBy}. Keeping it separate lets a
     * transient failure push a retry out without rewriting {@code process_by}, so history stays intact.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = processBy;
        }
    }
}
