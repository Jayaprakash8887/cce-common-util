package org.openphc.cce.common.group;

import java.util.Collection;
import java.util.function.Predicate;

/**
 * Decides whether a container of steps is complete. Step group occurrences use it today; it is
 * written over member ids and a "done" test, rather than over any table, so protocol completion can
 * use it when that is built.
 */
public final class GroupCompletion {

    private GroupCompletion() {
    }

    /**
     * Whether every member the container's completion waits on is done.
     *
     * <p>A container whose members are all optional has nothing to wait on and is complete as soon as it
     * is checked.
     *
     * @param selectionBehavior  the FHIR {@code selectionBehavior}. Absent and {@code all} both require
     *                           every member; the protocol service refuses any other value at load.
     * @param mandatoryMemberIds the members the container waits on
     * @param done               whether one member is done
     */
    public static boolean isComplete(String selectionBehavior, Collection<String> mandatoryMemberIds,
                                     Predicate<String> done) {
        if (selectionBehavior != null && !"all".equals(selectionBehavior)) {
            throw new IllegalStateException("selectionBehavior '" + selectionBehavior
                    + "' has no completion rule; a protocol using it should have been refused at load");
        }
        return mandatoryMemberIds.stream().allMatch(done);
    }
}
