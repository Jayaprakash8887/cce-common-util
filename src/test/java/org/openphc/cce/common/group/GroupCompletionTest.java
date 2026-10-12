package org.openphc.cce.common.group;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GroupCompletionTest {

    @Test
    void completeOnlyWhenEveryMandatoryMemberIsDone() {
        Set<String> done = Set.of("vitals");

        assertFalse(GroupCompletion.isComplete(null, List.of("vitals", "hba1c"), done::contains));
        assertTrue(GroupCompletion.isComplete("all", List.of("vitals"), done::contains));
    }

    @Test
    void nothingToWaitOnIsComplete() {
        assertTrue(GroupCompletion.isComplete(null, List.of(), id -> false));
    }

    @Test
    void aSelectionBehaviorWithNoCompletionRuleIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> GroupCompletion.isComplete("exactly-one", List.of("a"), id -> true));
    }
}
