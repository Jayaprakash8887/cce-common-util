package org.openphc.cce.common.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only status-transition history for {@link StepInstance}.
 * One row is written by the application on each step-status/SLA-status transition
 * (creation and every subsequent change). Rows are only ever INSERTed — never UPDATEd or
 * DELETEd — so the table is a faithful, point-in-time-reconstructible record and a clean
 * CDC source.
 *
 * @see org.openphc.cce.common.history.StateTransitionHistoryWriter
 */
@Entity
@Table(name = "step_instance_history")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepInstanceHistory {

    /**
     * From the {@code step_instance_history_id_seq} sequence behind the BIGSERIAL column, taken 50 at a time so a
     * batch of history rows can be inserted in one JDBC batch — {@code IDENTITY} made Hibernate run
     * each insert on its own to read the key back. The sequence steps by 50 to match (Matcher's
     * {@code V6} migration); Hibernate refuses to start if the two disagree.
     *
     * <p>Ids therefore no longer arrive in insert order across writers: each service instance draws
     * from its own block. Nothing orders history by id — reconstruction orders by {@code changed_at},
     * and ClickHouse uses the id only as the row's unique key.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "step_instance_history_id_seq")
    @SequenceGenerator(name = "step_instance_history_id_seq", sequenceName = "step_instance_history_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "step_instance_id", nullable = false)
    private UUID stepInstanceId;

    /** Recorded as-is from step_instance.step_status (already validated there). */
    @Column(name = "step_status", nullable = false)
    private String stepStatus;

    /** Recorded as-is from step_instance.sla_status (already validated there). */
    @Column(name = "sla_status")
    private String slaStatus;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;
}
