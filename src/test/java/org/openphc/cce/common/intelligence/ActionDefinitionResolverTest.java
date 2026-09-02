package org.openphc.cce.common.intelligence;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.common.entity.ActionDefinition;
import org.openphc.cce.common.enums.ActionDefinitionStatus;
import org.openphc.cce.common.repository.ActionDefinitionRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ActionDefinitionResolverTest {

    @Mock
    private ActionDefinitionRepository actionDefinitionRepository;

    private ActionDefinitionResolver resolver() {
        return new ActionDefinitionResolver(actionDefinitionRepository);
    }

    @Test
    void resolvesACanonicalIntoItsDefinition() {
        ActionDefinition def = ActionDefinition.builder()
                .canonicalUrl("http://openphc.org/ActivityDefinition/escalation-alert")
                .version("1.0")
                .status(ActionDefinitionStatus.ACTIVE)
                .build();
        when(actionDefinitionRepository.findByCanonicalUrlAndVersion(
                "http://openphc.org/ActivityDefinition/escalation-alert", "1.0"))
                .thenReturn(Optional.of(def));

        assertSame(def, resolver().resolveByCanonical(
                "http://openphc.org/ActivityDefinition/escalation-alert|1.0"));
    }

    @Test
    void splitsOnTheLastSeparatorSoUrlsMayContainPipes() {
        when(actionDefinitionRepository.findByCanonicalUrlAndVersion("http://a|b/c", "2.0"))
                .thenReturn(Optional.of(ActionDefinition.builder().build()));

        assertNotNull(resolver().resolveByCanonical("http://a|b/c|2.0"));
    }

    @Test
    void unknownCanonicalIsNotFoundRatherThanNull() {
        when(actionDefinitionRepository.findByCanonicalUrlAndVersion(any(), any()))
                .thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(EntityNotFoundException.class,
                () -> resolver().resolveByCanonical("http://openphc.org/missing|1.0"));
        assertTrue(ex.getMessage().contains("http://openphc.org/missing|1.0"));
    }

    @Test
    void aCanonicalWithoutAVersionIsRejectedBeforeQuerying() {
        // Resolving on url alone would silently pick a version, so an unversioned reference is a
        // caller error rather than something to guess at.
        assertThrows(IllegalArgumentException.class,
                () -> resolver().resolveByCanonical("http://openphc.org/ActivityDefinition/alert"));
        verifyNoInteractions(actionDefinitionRepository);
    }

    @Test
    void aTrailingSeparatorIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver().resolveByCanonical("http://openphc.org/alert|"));
        verifyNoInteractions(actionDefinitionRepository);
    }

    @Test
    void aLeadingSeparatorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> resolver().resolveByCanonical("|1.0"));
        verifyNoInteractions(actionDefinitionRepository);
    }
}
