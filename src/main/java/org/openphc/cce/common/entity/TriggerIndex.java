package org.openphc.cce.common.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "trigger_index")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TriggerIndex {

    @EmbeddedId
    private TriggerIndexId id;
}
