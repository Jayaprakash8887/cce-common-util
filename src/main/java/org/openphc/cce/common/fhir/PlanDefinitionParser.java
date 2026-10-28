package org.openphc.cce.common.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.*;
import org.openphc.cce.common.enums.PlanDefinitionActionType;
import org.openphc.cce.common.enums.CycleBoundary;
import org.openphc.cce.common.support.RequiredBehavior;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PlanDefinitionParser {

    private final IParser fhirJsonParser;

    public PlanDefinitionParser(FhirContext fhirContext) {
        this.fhirJsonParser = fhirContext.newJsonParser();
        this.fhirJsonParser.setParserErrorHandler(new ca.uhn.fhir.parser.StrictErrorHandler());
    }

    /**
     * Parse PlanDefinition JSON into a FHIR PlanDefinition resource.
     */
    public PlanDefinition parse(String json) {
        return fhirJsonParser.parseResource(PlanDefinition.class, json);
    }

    /**
     * Extract all steps from a PlanDefinition with their metadata.
     * Flattens nested sub-steps into a single list — all steps are treated uniformly.
     *
     * <p>Nesting is organizational only and does NOT create an implicit step dependency; ordering
     * between a parent and its sub-steps (and among sub-steps) must be expressed explicitly via
     * relatedAction. Whether a sub-step waits on its parent is a clinical decision for the protocol
     * author, and plenty of sub-steps (e.g. a referral raised during a visit) are meant to be
     * created by their own trigger without waiting.
     */
    public List<StepMetadata> extractSteps(PlanDefinition planDefinition) {
        return flatten(planDefinition).steps();
    }

    /**
     * Extract every step group: a {@code step}-typed action with {@code groupingBehavior:
     * logical-group}. A group is never a step, so it never appears in {@link #extractSteps}; each step
     * names its nearest enclosing group in {@link StepMetadata#groupId()} instead.
     */
    public List<GroupMetadata> extractGroups(PlanDefinition planDefinition) {
        return flatten(planDefinition).groups();
    }

    /** A PlanDefinition's steps and step groups, from one walk of its actions. */
    record Flattened(List<StepMetadata> steps, List<GroupMetadata> groups) {}

    /** {@link #extractSteps} and {@link #extractGroups} at once, walking the actions a single time. */
    Flattened flatten(PlanDefinition planDefinition) {
        List<StepMetadata> steps = new ArrayList<>();
        List<GroupMetadata> groups = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            validateActionType(action);
            flattenAction(action, null, steps, groups);
        }
        return new Flattened(steps, groups);
    }

    /**
     * Whether an action is a step group. Only {@code logical-group} makes one: {@code visual-group} and
     * {@code sentence-group} are presentation groupings in FHIR, so an action carrying one stays a step
     * whose nested children are flattened as before.
     */
    static boolean isGroup(PlanDefinition.PlanDefinitionActionComponent action) {
        return isStepAction(action) && hasLogicalGrouping(action);
    }

    private static boolean hasLogicalGrouping(PlanDefinition.PlanDefinitionActionComponent action) {
        return action.hasGroupingBehavior()
                && action.getGroupingBehavior() == PlanDefinition.ActionGroupingBehavior.LOGICALGROUP;
    }

    /**
     * How a FHIR {@code relatedAction.relationship} code positions the step that declares it
     * relative to the step it references.
     *
     * <p>{@code relatedAction} is a <strong>relative</strong> pointer: {@code relationship}
     * describes the declaring action's relationship to the referenced one. Which of the two comes
     * first therefore depends on the family:
     *
     * <ul>
     *   <li>{@code AFTER} — {@code lab-results.relatedAction = {actionId: lab-order,
     *       relationship: after-end, offset: 3d}} reads "lab-results happens 3 days after lab-order
     *       ends", so the <em>referenced</em> action is the prerequisite. An absent relationship is
     *       treated as {@code after-end}, the overwhelmingly common intent in authored protocols.</li>
     *   <li>{@code BEFORE} — {@code visit-encounter.relatedAction = {actionId: vitals-recording,
     *       relationship: before}} reads "visit-encounter happens before vitals-recording", so the
     *       <em>declaring</em> action is the prerequisite. Same ordering, stated from the other
     *       end.</li>
     *   <li>{@code UNORDERED} — {@code concurrent-*} says the two happen together and so implies no
     *       ordering. Such an edge drives neither progressive instantiation nor order checks;
     *       {@link #findUnorderedRelationships} reports them at load time so a protocol relying on
     *       one does not fail silently.</li>
     * </ul>
     */
    public enum RelationshipDirection { AFTER, BEFORE, UNORDERED }

    /** Classify a {@code relatedAction.relationship} code. A null relationship means after-end. */
    public static RelationshipDirection classifyRelationship(String relationship) {
        if (relationship == null) {
            return RelationshipDirection.AFTER;
        }
        return switch (relationship) {
            case "after", "after-start", "after-end" -> RelationshipDirection.AFTER;
            case "before", "before-start", "before-end" -> RelationshipDirection.BEFORE;
            default -> RelationshipDirection.UNORDERED;
        };
    }

    /**
     * The directed dependency graph of a protocol, in both directions.
     *
     * @param successors      prerequisite action id → the steps that depend on it, each paired with
     *                        the edge positioning it relative to that prerequisite. Drives progressive
     *                        instantiation, which works forward from a completed step.
     * @param predecessors    dependent action id (step or group) → the ids of the prerequisites it
     *                        waits on, steps and groups alike
     * @param groupSuccessors prerequisite action id → the step groups that depend on it. Kept apart
     *                        from {@code successors} because a group is opened, not created as a step.
     */
    public record DependencyGraph(
            Map<String, List<StepDependency>> successors,
            Map<String, List<String>> predecessors,
            Map<String, List<GroupDependency>> groupSuccessors
    ) {
        public List<StepDependency> successorsOf(String actionId) {
            return successors.getOrDefault(actionId, List.of());
        }

        public List<String> predecessorsOf(String actionId) {
            return predecessors.getOrDefault(actionId, List.of());
        }

        public List<GroupDependency> groupSuccessorsOf(String actionId) {
            return groupSuccessors.getOrDefault(actionId, List.of());
        }
    }

    /** {@link #buildDependencyGraph(List, List)} for a protocol with no step groups. */
    public static DependencyGraph buildDependencyGraph(List<StepMetadata> steps) {
        return buildDependencyGraph(steps, List.of());
    }

    /**
     * Normalize every ordering {@code relatedAction} into a single directed graph, whichever end of
     * the relationship it was written from (see {@link RelationshipDirection}).
     *
     * <p>Each resulting edge is expressed the {@code after-*} way round — the dependent paired with a
     * {@link RelatedStepInfo} naming its prerequisite — so consumers need only handle one form.
     * {@code before-*} carries no start/end distinction that survives the flip (there is no "before the
     * prerequisite started"), so it normalizes to {@code after-end}: the dependent is scheduled from
     * the prerequisite's completion, offset by the same duration.
     *
     * <p>Steps and step groups are both nodes: either can declare an edge, and either can be named by
     * one. A dependent step lands in {@code successors}, a dependent group in {@code groupSuccessors}.
     *
     * <p>Edges naming an action the definition does not declare are skipped — there is nothing to
     * schedule or wait on. {@link #findDanglingRelatedActions} reports them at load time.
     */
    public static DependencyGraph buildDependencyGraph(List<StepMetadata> steps, List<GroupMetadata> groups) {
        Map<String, StepMetadata> stepsById = steps.stream()
                .collect(Collectors.toMap(StepMetadata::id, s -> s, (a, b) -> a));
        Map<String, GroupMetadata> groupsById = groups.stream()
                .collect(Collectors.toMap(GroupMetadata::id, g -> g, (a, b) -> a));

        Map<String, List<StepDependency>> successors = new LinkedHashMap<>();
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        Map<String, List<GroupDependency>> groupSuccessors = new LinkedHashMap<>();

        for (Map.Entry<String, List<RelatedStepInfo>> node : declaredEdges(steps, groups).entrySet()) {
            String declaringId = node.getKey();
            for (RelatedStepInfo edge : node.getValue()) {
                if (edge.actionId() == null || edge.actionId().isBlank()) {
                    continue;
                }

                RelationshipDirection direction = classifyRelationship(edge.relationship());
                if (direction == RelationshipDirection.UNORDERED) {
                    continue; // concurrent-*: no ordering to derive
                }

                boolean declaringIsDependent = direction == RelationshipDirection.AFTER;
                String prerequisiteId = declaringIsDependent ? edge.actionId() : declaringId;
                String dependentId = declaringIsDependent ? declaringId : edge.actionId();
                String relationship =
                        declaringIsDependent && "after-start".equals(edge.relationship())
                                ? "after-start" : "after-end";

                if (!stepsById.containsKey(prerequisiteId) && !groupsById.containsKey(prerequisiteId)) {
                    continue;
                }

                RelatedStepInfo normalized = new RelatedStepInfo(
                        prerequisiteId, relationship, edge.offsetValue(), edge.offsetUnit());

                StepMetadata dependentStep = stepsById.get(dependentId);
                GroupMetadata dependentGroup = groupsById.get(dependentId);
                if (dependentStep != null) {
                    successors.computeIfAbsent(prerequisiteId, k -> new ArrayList<>())
                            .add(new StepDependency(dependentStep, normalized));
                } else if (dependentGroup != null) {
                    groupSuccessors.computeIfAbsent(prerequisiteId, k -> new ArrayList<>())
                            .add(new GroupDependency(dependentGroup, normalized));
                } else {
                    continue;
                }
                predecessors.computeIfAbsent(dependentId, k -> new ArrayList<>()).add(prerequisiteId);
            }
        }

        return new DependencyGraph(successors, predecessors, groupSuccessors);
    }

    /** Look up a step's metadata by action id, or null when the definition has no such step. */
    public static StepMetadata findStep(List<StepMetadata> steps, String actionId) {
        return steps.stream()
                .filter(s -> actionId.equals(s.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * The mandatory ("must") steps that should already have been carried out for the observed
     * progress to be legitimate: for every observed step, its transitive {@code relatedAction}
     * predecessors (ancestors) whose requiredBehavior is "must". Observed steps may appear in the
     * result when one is a predecessor of another — callers that only care about unrecorded work
     * filter by "has no step instance".
     *
     * <p>A step also waits on what its enclosing groups wait on, at every level. ANC visit 2 follows
     * registration, so a BP recorded in visit 2 has registration as a predecessor, though the BP step
     * names none of its own.
     *
     * <p>{@code StepInstanceService} materializes these when they have no step instance at all, so
     * a step that arrived without its prerequisites stops leaving them invisible. Mandatory work
     * still ahead is left to progressive instantiation, which creates it with the due dates its own
     * {@code relatedAction} offsets define.
     *
     * @param groups every step group; empty for a protocol that declares none
     */
    public static Set<String> computeMustPredecessorSteps(Collection<String> observedStepIds,
                                                         List<StepMetadata> steps,
                                                         List<GroupMetadata> groups,
                                                         DependencyGraph graph) {
        Set<String> mustStepIds = mustStepIds(steps);
        if (mustStepIds.isEmpty()) {
            return Set.of();
        }

        // The group directly around each step and each nested group.
        Map<String, String> enclosingGroupIds = new HashMap<>();
        steps.stream().filter(s -> s.groupId() != null).forEach(s -> enclosingGroupIds.put(s.id(), s.groupId()));
        groups.stream().filter(g -> g.parentGroupId() != null)
                .forEach(g -> enclosingGroupIds.put(g.id(), g.parentGroupId()));

        // Walk back from every observed step. A group is walked through, not collected: what counts is
        // what it waits on.
        Set<String> ancestors = new HashSet<>();
        Set<String> visited = new HashSet<>(observedStepIds);
        Deque<String> queue = new ArrayDeque<>(observedStepIds);
        while (!queue.isEmpty()) {
            String actionId = queue.poll();
            String enclosingGroupId = enclosingGroupIds.get(actionId);
            if (enclosingGroupId != null && visited.add(enclosingGroupId)) {
                queue.add(enclosingGroupId);
            }
            for (String prerequisiteId : graph.predecessorsOf(actionId)) {
                ancestors.add(prerequisiteId);
                if (visited.add(prerequisiteId)) {
                    queue.add(prerequisiteId);
                }
            }
        }
        ancestors.retainAll(mustStepIds);
        return ancestors;
    }

    /**
     * Describe every {@code relatedAction} whose relationship implies no ordering
     * ({@code concurrent-*}), so a protocol that expects one to sequence steps is reported at load
     * time instead of quietly producing a disconnected graph. Steps and step groups both declare edges.
     */
    public static List<String> findUnorderedRelationships(List<StepMetadata> steps, List<GroupMetadata> groups) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<RelatedStepInfo>> node : declaredEdges(steps, groups).entrySet()) {
            for (RelatedStepInfo edge : node.getValue()) {
                if (classifyRelationship(edge.relationship()) == RelationshipDirection.UNORDERED) {
                    found.add("action '" + node.getKey() + "' -> '" + edge.actionId()
                            + "' (relationship=" + edge.relationship() + ")");
                }
            }
        }
        return found;
    }

    /**
     * Describe every {@code relatedAction} naming an action the definition does not declare. Such an
     * edge can neither schedule a step nor be waited on, so it is silently inert — reported at load
     * time so an authoring typo does not pass unnoticed. Step groups both declare edges and count as
     * actions an edge may name.
     */
    public static List<String> findDanglingRelatedActions(List<StepMetadata> steps, List<GroupMetadata> groups) {
        Map<String, List<RelatedStepInfo>> edges = declaredEdges(steps, groups);

        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<RelatedStepInfo>> node : edges.entrySet()) {
            for (RelatedStepInfo edge : node.getValue()) {
                if (edge.actionId() != null && !edges.containsKey(edge.actionId())) {
                    found.add("action '" + node.getKey() + "' -> unknown action '"
                            + edge.actionId() + "'");
                }
            }
        }
        return found;
    }

    /** Each step's and group's declared {@code relatedAction} edges, keyed by its action id. */
    private static Map<String, List<RelatedStepInfo>> declaredEdges(List<StepMetadata> steps,
                                                                    List<GroupMetadata> groups) {
        Map<String, List<RelatedStepInfo>> edges = new LinkedHashMap<>();
        steps.forEach(step -> edges.putIfAbsent(step.id(), step.relatedSteps()));
        groups.forEach(group -> edges.putIfAbsent(group.id(), group.relatedSteps()));
        return edges;
    }

    /** The ids of every step whose requiredBehavior is "must". */
    public static Set<String> mustStepIds(List<StepMetadata> steps) {
        return steps.stream()
                .filter(s -> RequiredBehavior.isMandatory(s.requiredBehavior()))
                .map(StepMetadata::id)
                .collect(Collectors.toSet());
    }

    // ── Step groups ──

    /** Look up a group's metadata by action id, or null when the definition has no such group. */
    public static GroupMetadata findGroup(List<GroupMetadata> groups, String actionId) {
        return groups.stream()
                .filter(g -> actionId.equals(g.id()))
                .findFirst()
                .orElse(null);
    }

    /** The steps directly inside a group: those whose nearest enclosing group it is. */
    public static List<StepMetadata> memberSteps(String groupId, List<StepMetadata> steps) {
        return steps.stream().filter(s -> groupId.equals(s.groupId())).toList();
    }

    /** The groups directly inside a group. */
    public static List<GroupMetadata> memberGroups(String groupId, List<GroupMetadata> groups) {
        return groups.stream().filter(g -> groupId.equals(g.parentGroupId())).toList();
    }

    /** The ids of everything inside a group, at any depth: steps and groups. */
    public static Set<String> descendantIds(String groupId, List<StepMetadata> steps,
                                            List<GroupMetadata> groups) {
        Set<String> ids = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(groupId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            memberSteps(current, steps).forEach(s -> ids.add(s.id()));
            for (GroupMetadata member : memberGroups(current, groups)) {
                if (ids.add(member.id())) {
                    queue.add(member.id());
                }
            }
        }
        return ids;
    }

    /**
     * The steps whose repeat opens a new cycle of an {@code entry-step} group: its direct member steps,
     * mandatory or not, with no predecessor anywhere inside the group.
     */
    public static List<StepMetadata> cycleEntrySteps(String groupId, List<StepMetadata> steps,
                                                     List<GroupMetadata> groups, DependencyGraph graph) {
        Set<String> inside = descendantIds(groupId, steps, groups);
        return memberSteps(groupId, steps).stream()
                .filter(s -> graph.predecessorsOf(s.id()).stream().noneMatch(inside::contains))
                .toList();
    }

    /**
     * The members an instance of a group creates when it opens: its mandatory direct members with no
     * predecessor inside the group. The rest arrive through progressive instantiation as their
     * predecessors complete, with the due dates their own {@code relatedAction} offsets give them.
     * Creating them all at once would give, say, a day-3 lab the day-0 vitals' due date.
     *
     * <p>A predecessor counts as inside the group wherever it sits in the group's subtree, so a step
     * waiting on a step in a nested group is not an entry member either.
     */
    public static EntryMembers entryMembers(String groupId, List<StepMetadata> steps,
                                            List<GroupMetadata> groups, DependencyGraph graph) {
        Set<String> inside = descendantIds(groupId, steps, groups);
        java.util.function.Predicate<String> isEntry =
                id -> graph.predecessorsOf(id).stream().noneMatch(inside::contains);

        List<StepMetadata> entrySteps = memberSteps(groupId, steps).stream()
                .filter(s -> RequiredBehavior.isMandatory(s.requiredBehavior()))
                .filter(s -> isEntry.test(s.id()))
                .toList();
        List<GroupMetadata> entryGroups = memberGroups(groupId, groups).stream()
                .filter(GroupMetadata::isMandatory)
                .filter(g -> isEntry.test(g.id()))
                .toList();
        return new EntryMembers(entrySteps, entryGroups);
    }

    /**
     * Describe every optional step that declares a deadline.
     *
     * <p>A deadline exists to be breached: it is what makes a step {@code OVERDUE} and then
     * {@code MISSED}. Nothing is required of an optional step, so there is nothing for it to breach,
     * and a tolerance window on one only schedules work the SLA service must then decline to judge.
     * Reported at load so the protocol is corrected rather than quietly carrying an inert deadline.
     */
    public static List<String> findOptionalStepDeadlines(List<StepMetadata> steps) {
        List<String> found = new ArrayList<>();
        for (StepMetadata step : steps) {
            if (step.toleranceDays() != null && !RequiredBehavior.isMandatory(step.requiredBehavior())) {
                found.add("action '" + step.id() + "' (requiredBehavior="
                        + (step.requiredBehavior() == null ? "<absent>" : step.requiredBehavior())
                        + ", tolerance-days=" + step.toleranceDays() + ")");
            }
        }
        return found;
    }

    /**
     * Build TriggerIndex entries from a PlanDefinition by decomposing each action's
     * trigger data[].codeFilter[] into individual rows.
     * Sub-step triggers are indexed with their own action ID (flat model).
     */
    public List<TriggerIndexEntry> buildTriggerIndexEntries(PlanDefinition planDefinition, UUID protocolDefinitionId) {
        List<TriggerIndexEntry> entries = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            indexActionTriggers(action, protocolDefinitionId, entries);
        }
        return entries;
    }

    /** Index an action's triggers under its own action id, then those of its nested step-type actions. */
    private void indexActionTriggers(PlanDefinition.PlanDefinitionActionComponent action,
                                     UUID protocolDefinitionId, List<TriggerIndexEntry> entries) {
        indexOwnTriggers(action, protocolDefinitionId, entries);
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
            if (isStepAction(nestedAction)) {
                indexActionTriggers(nestedAction, protocolDefinitionId, entries);
            }
        }
    }

    private void indexOwnTriggers(PlanDefinition.PlanDefinitionActionComponent action,
                                  UUID protocolDefinitionId, List<TriggerIndexEntry> entries) {
        // A group is never matched to an event, only its steps are. validateGroups refuses a group with
        // a trigger at load; skipping here keeps one that slipped through from ever being indexed.
        if (isGroup(action)) {
            return;
        }
        String actionId = action.getId();

        for (TriggerDefinition trigger : action.getTrigger()) {
            for (DataRequirement dataReq : trigger.getData()) {
                if (dataReq.getType() == null) continue;
                ResourceType resourceType = parseResourceType(dataReq.getType());

                List<DataRequirement.DataRequirementCodeFilterComponent> codeFilters = dataReq.getCodeFilter();
                if (codeFilters == null || codeFilters.isEmpty()) {
                    entries.add(new TriggerIndexEntry(resourceType, "", "", "", protocolDefinitionId, actionId));
                    continue;
                }

                for (DataRequirement.DataRequirementCodeFilterComponent cf : codeFilters) {
                    String path = cf.getPath() != null ? cf.getPath() : "";
                    for (Coding coding : cf.getCode()) {
                        String system = coding.getSystem() != null ? coding.getSystem() : "";
                        String code = coding.getCode() != null ? coding.getCode()
                                : (coding.getDisplay() != null ? coding.getDisplay() : "");
                        entries.add(new TriggerIndexEntry(resourceType, path, system, code, protocolDefinitionId,
                                actionId));
                    }
                }
            }
        }
    }

    /**
     * Extract condition-only triggers — triggers that have no data[] section, only a condition.
     * These are held in-memory and evaluated via Tier 2 for every inbound event.
     * Sub-step triggers are extracted with their own action ID (flat model).
     */
    public List<ConditionOnlyTriggerInfo> extractConditionOnlyTriggers(PlanDefinition planDefinition) {
        List<ConditionOnlyTriggerInfo> result = new ArrayList<>();

        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            collectConditionOnlyTriggers(action, result);
        }
        return result;
    }

    /**
     * Collect an action's condition-only triggers, then recurse into its nested step-type actions.
     *
     * <p>The recursion matters: {@link #buildTriggerIndexEntries} and {@link #validateActionTriggers}
     * both descend into sub-steps, so a sub-step whose only trigger is a condition passes load-time
     * validation. Without descending here too, it would be registered nowhere and silently never
     * match.
     */
    private void collectConditionOnlyTriggers(PlanDefinition.PlanDefinitionActionComponent action,
                                              List<ConditionOnlyTriggerInfo> result) {
        // As in indexActionTriggers: a group's own triggers are never matched; its members' still are.
        List<TriggerDefinition> ownTriggers = isGroup(action) ? List.of() : action.getTrigger();
        for (TriggerDefinition trigger : ownTriggers) {
            if (hasData(trigger)) continue;

            Expression condition = getCondition(trigger);
            if (condition != null) {
                result.add(new ConditionOnlyTriggerInfo(
                        action.getId(),
                        condition.getLanguage(),
                        condition.getExpression()
                ));
            }
        }

        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
            if (isStepAction(nestedAction)) {
                collectConditionOnlyTriggers(nestedAction, result);
            }
        }
    }

    /**
     * Validate triggers at load time. Rejects any trigger that has no data[] AND no condition, and any
     * {@code codeFilter.path} the Matcher Service cannot extract from an event payload.
     *
     * <p>The path check is the one that has to happen here rather than at match time. An unmatchable
     * path is indexed like any other and then never matched, and since Tier 1 requires every codeFilter
     * of an action to match, it disables that action's trigger rather than loosening it — a protocol
     * that loads cleanly, reports its trigger rows, and silently never enrols anyone. Load is the last
     * moment anyone is looking.
     *
     * @throws IllegalArgumentException if an invalid trigger is found
     */
    public void validateTriggers(PlanDefinition planDefinition) {
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            validateActionTriggers(action);
        }
    }

    /** The extension a repeating group will use to say what starts a new cycle. */
    static final String CYCLE_BOUNDARY_EXTENSION_URL =
            "http://openphc.org/fhir/StructureDefinition/cycle-boundary";

    /**
     * Validate step groups at load time.
     *
     * <p>Permanent rules: a group has no trigger, since it is never matched to an event; it contains at
     * least one step, directly or in a nested group, or it could never complete; only a {@code step}-typed
     * action can be a group; its {@code selectionBehavior} is absent or {@code all}, the only one with a
     * completion rule; an optional group carries no deadline, as an optional step carries none; and nor
     * does a visit-driven ({@code cycle-boundary}) group, whose cycles have no planned due date. The
     * {@code cycle-boundary} extension is only on a group, with {@code calendar-day} or {@code entry-step};
     * a group inside a repeating group repeats only by {@code entry-step}; and an {@code entry-step} group
     * has at least one entry step.
     *
     * <p>Rules that hold until the feature behind them is built: no {@code timing} on a group (scheduled
     * repeats), no {@code tolerance-days} on a group (group deadlines), no {@code fire-event} action on a
     * group (intelligence on groups), and nothing that follows a group (group completion and closure).
     * Each is refused rather than ignored, so an author never believes a setting takes effect when it does
     * not.
     *
     * @throws IllegalArgumentException naming the first action that breaks a rule
     */
    public void validateGroups(PlanDefinition planDefinition) {
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            validateGroupsIn(action, false);
        }
        Flattened flattened = flatten(planDefinition);
        if (flattened.groups().isEmpty()) {
            return;
        }
        DependencyGraph graph = buildDependencyGraph(flattened.steps(), flattened.groups());
        rejectFollowersOfGroups(flattened.groups(), graph);
        rejectEntryStepGroupsWithNoEntryStep(flattened.steps(), flattened.groups(), graph);
    }

    // Until groups can complete or close, whatever follows one would never be created.
    private static void rejectFollowersOfGroups(List<GroupMetadata> groups, DependencyGraph graph) {
        for (GroupMetadata group : groups) {
            String follower = graph.successorsOf(group.id()).stream().map(d -> d.step().id()).findFirst()
                    .or(() -> graph.groupSuccessorsOf(group.id()).stream().map(d -> d.group().id()).findFirst())
                    .orElse(null);
            if (follower != null) {
                throw new IllegalArgumentException(
                        "Action '" + follower + "' follows group '" + group.id() + "'. Group completion is "
                                + "not supported yet, so nothing that follows a group would ever be created; "
                                + "relate it to one of the group's steps instead.");
            }
        }
    }

    private void validateGroupsIn(PlanDefinition.PlanDefinitionActionComponent action,
                                  boolean insideRepeatingGroup) {
        if (isIntelligenceAction(action)) {
            if (hasLogicalGrouping(action)) {
                throw new IllegalArgumentException(
                        "Action '" + action.getId() + "' is a fire-event action with groupingBehavior "
                                + "'logical-group'. Only a step-typed action can be a group; an "
                                + "intelligence action has no members.");
            }
            return;
        }
        boolean group = isGroup(action);
        if (group) {
            validateGroup(action, insideRepeatingGroup);
        } else if (action.getExtensionByUrl(CYCLE_BOUNDARY_EXTENSION_URL) != null) {
            throw new IllegalArgumentException(
                    "Action '" + action.getId() + "' declares the cycle-boundary extension but is not a step "
                            + "group. Only a group (groupingBehavior 'logical-group') repeats in cycles.");
        }
        boolean repeating = group
                && (action.hasTiming() || action.getExtensionByUrl(CYCLE_BOUNDARY_EXTENSION_URL) != null);
        for (PlanDefinition.PlanDefinitionActionComponent nested : action.getAction()) {
            validateGroupsIn(nested, insideRepeatingGroup || repeating);
        }
    }

    private void validateGroup(PlanDefinition.PlanDefinitionActionComponent group, boolean insideRepeatingGroup) {
        String id = group.getId();
        if (group.hasTrigger()) {
            throw new IllegalArgumentException(
                    "Group '" + id + "' has a trigger. A group is never matched to an event, only its "
                            + "steps are; put the trigger on the step it belongs to.");
        }
        if (!containsStep(group)) {
            throw new IllegalArgumentException(
                    "Group '" + id + "' contains no step. A group is complete when its mandatory steps "
                            + "are done, so a group with none could never complete.");
        }
        if (group.hasSelectionBehavior()
                && group.getSelectionBehavior() != PlanDefinition.ActionSelectionBehavior.ALL) {
            throw new IllegalArgumentException(
                    "Group '" + id + "' has selectionBehavior '" + group.getSelectionBehavior().toCode()
                            + "'. Only 'all' (or no selectionBehavior) is supported: a group is complete "
                            + "when all its mandatory members are done.");
        }
        Integer toleranceDays = extractToleranceDays(group);
        String requiredBehavior = group.hasRequiredBehavior() ? group.getRequiredBehavior().toCode() : null;
        if (toleranceDays != null && !RequiredBehavior.isMandatory(requiredBehavior)) {
            throw new IllegalArgumentException(
                    "Group '" + id + "' is optional (requiredBehavior="
                            + (requiredBehavior == null ? "<absent>" : requiredBehavior)
                            + ") and declares tolerance-days. Nothing is required of an optional group, "
                            + "so it has no deadline to miss.");
        }
        if (toleranceDays != null && group.getExtensionByUrl(CYCLE_BOUNDARY_EXTENSION_URL) != null) {
            throw new IllegalArgumentException(
                    "Group '" + id + "' has the cycle-boundary extension and declares tolerance-days. A "
                            + "visit-driven cycle opens when the patient comes back, so it has no planned "
                            + "due date for a deadline to count from; put tolerance-days on its steps.");
        }
        if (toleranceDays != null) {
            throw new IllegalArgumentException(
                    "Group '" + id + "' declares tolerance-days. Group deadlines are not supported yet; "
                            + "put tolerance-days on the group's steps.");
        }
        if (group.hasTiming()) {
            throw new IllegalArgumentException(
                    "Group '" + id + "' declares a timing. Repeating groups are not supported yet.");
        }
        Extension cycleBoundary = group.getExtensionByUrl(CYCLE_BOUNDARY_EXTENSION_URL);
        if (cycleBoundary != null) {
            validateCycleBoundary(id, cycleBoundary, insideRepeatingGroup);
        }
        for (PlanDefinition.PlanDefinitionActionComponent nested : group.getAction()) {
            if (isIntelligenceAction(nested)) {
                throw new IllegalArgumentException(
                        "Group '" + id + "' has the fire-event action '" + nested.getId()
                                + "'. Intelligence actions on a group are not supported yet; attach it "
                                + "to one of the group's steps.");
            }
        }
    }

    private static void validateCycleBoundary(String groupId, Extension extension, boolean insideRepeatingGroup) {
        String code = extension.getValue() instanceof CodeType codeType ? codeType.getCode() : null;
        CycleBoundary boundary = CycleBoundary.fromCode(code).orElseThrow(() -> new IllegalArgumentException(
                "Group '" + groupId + "' has the cycle-boundary extension with "
                        + (code == null ? "no valueCode" : "the code '" + code + "'")
                        + ". Supported codes: calendar-day, entry-step."));
        if (insideRepeatingGroup && boundary != CycleBoundary.ENTRY_STEP) {
            throw new IllegalArgumentException(
                    "Group '" + groupId + "' repeats by " + boundary.code() + " inside another repeating group. "
                            + "Every event of one visit falls on the same day, so a group inside a repeating "
                            + "group can only repeat by entry-step.");
        }
    }

    // An entry-step group opens a new cycle only when an entry step repeats, so it needs one.
    private static void rejectEntryStepGroupsWithNoEntryStep(List<StepMetadata> steps, List<GroupMetadata> groups,
                                                             DependencyGraph graph) {
        for (GroupMetadata group : groups) {
            if (group.cycleBoundary() == CycleBoundary.ENTRY_STEP
                    && cycleEntrySteps(group.id(), steps, groups, graph).isEmpty()) {
                throw new IllegalArgumentException(
                        "Group '" + group.id() + "' repeats by entry-step but has no entry step: every step "
                                + "directly in it waits on another step inside it, so no new cycle could ever "
                                + "start.");
            }
        }
    }

    /** Whether a group holds at least one step, directly or inside a nested group or step. */
    private static boolean containsStep(PlanDefinition.PlanDefinitionActionComponent group) {
        for (PlanDefinition.PlanDefinitionActionComponent nested : group.getAction()) {
            if (isStepAction(nested) && (!isGroup(nested) || containsStep(nested))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validate that every action (including nested sub-steps) declares an explicit
     * type coding of 'step' or 'fire-event'. Mirrors the validation that
     * {@link #extractSteps(PlanDefinition)} performs lazily at event-processing time,
     * so a malformed protocol is rejected at load time rather than poisoning the
     * event pipeline (and DLQ-ing inbound events) on first match.
     *
     * <p>Children of step-type actions are validated recursively; fire-event actions
     * are treated as leaves and their children are not inspected — matching the
     * behaviour of {@code flattenAction}.
     *
     * @throws IllegalArgumentException if any action is missing a type coding or has
     *                                  an unsupported one
     */
    public void validateActionTypes(PlanDefinition planDefinition) {
        for (PlanDefinition.PlanDefinitionActionComponent action : planDefinition.getAction()) {
            validateActionType(action);
            validateNestedActionTypes(action);
        }
    }

    private void validateNestedActionTypes(PlanDefinition.PlanDefinitionActionComponent action) {
        for (PlanDefinition.PlanDefinitionActionComponent nested : action.getAction()) {
            validateActionType(nested);
            if (isStepAction(nested)) {
                validateNestedActionTypes(nested);
            }
        }
    }

    /**
     * Validate that no optional step declares a deadline.
     *
     * <p>Only a mandatory step can be late: an SLA threshold is the point at which work the protocol
     * <em>required</em> has not been recorded. An optional step is never required, so it is never
     * overdue and never missed, and no {@code step_sla_state_transition} row is written for one. A
     * {@code tolerance-days} on such an action therefore says nothing the platform can act on, and
     * accepting it would leave the author believing a deadline is being enforced.
     *
     * <p>{@code requiredBehavior} absent is optional, the same reading progressive instantiation
     * takes — see {@link RequiredBehavior#isMandatory(String)}. So an action that is meant to have a deadline is fixed
     * by declaring {@code "must"}, not by removing the tolerance.
     *
     * @throws IllegalArgumentException if any optional action declares {@code tolerance-days}
     */
    public void validateOptionalStepDeadlines(PlanDefinition planDefinition) {
        List<String> offenders = findOptionalStepDeadlines(extractSteps(planDefinition));
        if (!offenders.isEmpty()) {
            throw new IllegalArgumentException(
                    "Optional actions must not declare a deadline: tolerance-days is only meaningful "
                            + "for requiredBehavior='must' steps, which are the only steps that can go "
                            + "OVERDUE or MISSED. Declare requiredBehavior='must' or remove the "
                            + "extension: " + String.join("; ", offenders));
        }
    }

    /**
     * Validate that all actions (including nested sub-steps) have a non-blank actionId
     * and that all actionIds are unique across the entire PlanDefinition.
     *
     * @throws IllegalArgumentException if an actionId is missing/blank or duplicated
     */
    public void validateActionIds(PlanDefinition planDefinition) {
        List<String> allIds = new ArrayList<>();
        collectActionIds(planDefinition.getAction(), allIds);

        // Check for duplicates
        Set<String> seen = new HashSet<>();
        for (String id : allIds) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException(
                        "Duplicate actionId '" + id + "' found in PlanDefinition. " +
                                "All action IDs must be unique across all levels.");
            }
        }
    }

    private void collectActionIds(List<PlanDefinition.PlanDefinitionActionComponent> actions,
                                  List<String> ids) {
        for (PlanDefinition.PlanDefinitionActionComponent action : actions) {
            String id = action.getId();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(
                        "Action with title '" + (action.hasTitle() ? action.getTitle() : "<untitled>")
                                + "' is missing a required actionId.");
            }
            ids.add(id);
            // Recurse into nested actions (both step and fire-event types have IDs)
            if (action.hasAction()) {
                collectActionIds(action.getAction(), ids);
            }
        }
    }

    private void validateActionTriggers(PlanDefinition.PlanDefinitionActionComponent action) {
        for (TriggerDefinition trigger : action.getTrigger()) {
            if (!hasData(trigger) && getCondition(trigger) == null) {
                throw new IllegalArgumentException(
                        "Action '" + action.getId() + "' has a trigger with no data[] and no condition. " +
                                "At least one of data[] or condition must be present.");
            }
            validateCodeFilterPaths(action, trigger);
        }

        // Recursively validate nested steps
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
            if (isStepAction(nestedAction)) {
                validateActionTriggers(nestedAction);
            }
        }
    }

    private void validateCodeFilterPaths(PlanDefinition.PlanDefinitionActionComponent action,
                                         TriggerDefinition trigger) {
        for (DataRequirement dataReq : trigger.getData()) {
            List<DataRequirement.DataRequirementCodeFilterComponent> codeFilters = dataReq.getCodeFilter();
            if (codeFilters == null) {
                continue;
            }
            for (DataRequirement.DataRequirementCodeFilterComponent cf : codeFilters) {
                String path = cf.getPath();
                if (!TriggerPath.isMatchable(path)) {
                    throw new IllegalArgumentException(
                            "Action '" + action.getId() + "' has a trigger on codeFilter.path '" + path
                                    + "', which is not a single top-level field name (e.g. 'code', 'class', "
                                    + "'serviceType'). Event payloads are read one top-level field at a time, "
                                    + "so the trigger could never match — and because every codeFilter of an "
                                    + "action must match, the whole action would never fire.");
                }
            }
        }
    }

    /**
     * Recursively flatten an action and its nested sub-steps. Each nested step-type action becomes a
     * peer entry; see {@link #extractSteps} for why nesting carries no dependency of its own.
     *
     * <p>A step group ({@link #isGroup}) is recorded as a {@link GroupMetadata}, never a step, and
     * becomes the {@code groupId} of everything flattened beneath it. Plain nesting under a step passes
     * the enclosing group down unchanged, so each step names its nearest group.
     *
     * @param groupId the nearest enclosing group's action id, or null outside any group
     */
    private void flattenAction(PlanDefinition.PlanDefinitionActionComponent action,
                               String groupId,
                               List<StepMetadata> steps,
                               List<GroupMetadata> groups) {
        List<TriggerInfo> triggers = extractTriggerInfos(action);
        List<RelatedStepInfo> relatedSteps = extractRelatedSteps(action);
        TimingInfo timingInfo = extractTimingInfo(action);
        Integer toleranceDays = extractToleranceDays(action);

        // Extract requiredBehavior
        String requiredBehavior = action.hasRequiredBehavior()
                ? action.getRequiredBehavior().toCode()
                : null;

        // Extract intelligence actions from nested fire-event actions
        List<IntelligenceActionInfo> intelligenceActions = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
            if (isIntelligenceAction(nestedAction)) {
                IntelligenceActionInfo intelligenceAction = buildIntelligenceActionInfo(nestedAction);
                if (intelligenceAction != null) {
                    intelligenceActions.add(intelligenceAction);
                }
            } else if (!isStepAction(nestedAction)) {
                throw new IllegalArgumentException(
                        "Nested action '" + nestedAction.getId()
                                + "' must have explicit type coding: 'step' or 'fire-event'");
            }
        }

        boolean group = isGroup(action);
        if (group) {
            groups.add(new GroupMetadata(
                    action.getId(),
                    action.getTitle(),
                    groupId,
                    relatedSteps,
                    timingInfo,
                    toleranceDays,
                    requiredBehavior,
                    action.hasSelectionBehavior() ? action.getSelectionBehavior().toCode() : null,
                    CycleBoundary.fromCode(extractCodeExtension(action, CYCLE_BOUNDARY_EXTENSION_URL))
                            .orElse(null),
                    intelligenceActions
            ));
        } else {
            steps.add(new StepMetadata(
                    action.getId(),
                    action.getTitle(),
                    triggers,
                    relatedSteps,
                    timingInfo,
                    toleranceDays,
                    requiredBehavior,
                    intelligenceActions,
                    groupId
            ));
        }

        String nestedGroupId = group ? action.getId() : groupId;
        for (PlanDefinition.PlanDefinitionActionComponent nestedAction : action.getAction()) {
            if (isStepAction(nestedAction)) {
                flattenAction(nestedAction, nestedGroupId, steps, groups);
            }
        }
    }

    private static boolean isStepAction(PlanDefinition.PlanDefinitionActionComponent action) {
        return hasTypeCoding(action, PlanDefinitionActionType.STEP);
    }

    private static boolean isIntelligenceAction(PlanDefinition.PlanDefinitionActionComponent action) {
        return hasTypeCoding(action, PlanDefinitionActionType.FIRE_EVENT);
    }

    /**
     * Validate that an action has a required type coding.
     * Valid types: "step" (trigger-based, may contain sub-steps) or "fire-event" (intelligence).
     */
    private void validateActionType(PlanDefinition.PlanDefinitionActionComponent action) {
        if (!action.hasType()) {
            throw new IllegalArgumentException(
                    "Action '" + action.getId() + "' must have explicit type coding: 'step' or 'fire-event'");
        }
        if (!isStepAction(action) && !isIntelligenceAction(action)) {
            throw new IllegalArgumentException(
                    "Action '" + action.getId() + "' has unsupported type coding. Must be 'step' or 'fire-event'");
        }
    }

    private static boolean hasTypeCoding(PlanDefinition.PlanDefinitionActionComponent action, PlanDefinitionActionType planActionType) {
        if (!action.hasType()) {
            return false;
        }
        CodeableConcept type = action.getType();
        for (Coding coding : type.getCoding()) {
            if (planActionType.getCode().equals(coding.getCode())
                    && planActionType.getSystem().equals(coding.getSystem())) {
                return true;
            }
        }
        return false;
    }


    private List<TriggerInfo> extractTriggerInfos(PlanDefinition.PlanDefinitionActionComponent action) {
        List<TriggerInfo> triggers = new ArrayList<>();
        for (TriggerDefinition trigger : action.getTrigger()) {
            List<DataRequirementInfo> dataReqs = new ArrayList<>();
            for (DataRequirement dr : trigger.getData()) {
                List<CodeFilterInfo> codeFilters = new ArrayList<>();
                if (dr.getCodeFilter() != null) {
                    for (DataRequirement.DataRequirementCodeFilterComponent cf : dr.getCodeFilter()) {
                        List<CodingInfo> codes = new ArrayList<>();
                        for (Coding c : cf.getCode()) {
                            codes.add(new CodingInfo(c.getSystem(), c.getCode()));
                        }
                        codeFilters.add(new CodeFilterInfo(cf.getPath(), codes));
                    }
                }
                dataReqs.add(new DataRequirementInfo(dr.getType(), codeFilters));
            }

            ConditionInfo conditionInfo = null;
            Expression cond = getCondition(trigger);
            if (cond != null) {
                conditionInfo = new ConditionInfo(cond.getLanguage(), cond.getExpression());
            }
            triggers.add(new TriggerInfo(dataReqs, conditionInfo));
        }
        return triggers;
    }

    /**
     * Extract an action's {@code relatedAction} entries verbatim. Each one names a step this action
     * is positioned <em>relative to</em>, not a step this action leads to — see
     * {@link #classifyRelationship} for which end comes first.
     */
    private List<RelatedStepInfo> extractRelatedSteps(PlanDefinition.PlanDefinitionActionComponent action) {
        List<RelatedStepInfo> relatedSteps = new ArrayList<>();
        for (PlanDefinition.PlanDefinitionActionRelatedActionComponent ra : action.getRelatedAction()) {
            Duration offset = ra.getOffsetDuration();
            relatedSteps.add(new RelatedStepInfo(
                    ra.getActionId(),
                    ra.getRelationship() != null ? ra.getRelationship().toCode() : null,
                    offset != null ? offset.getValue() : null,
                    offset != null && offset.getUnit() != null ? offset.getUnit() : null
            ));
        }
        return relatedSteps;
    }

    private TimingInfo extractTimingInfo(PlanDefinition.PlanDefinitionActionComponent action) {
        if (action.hasTiming() && action.getTiming() instanceof Timing timing) {
            Timing.TimingRepeatComponent repeat = timing.getRepeat();
            if (repeat != null) {
                return new TimingInfo(
                        repeat.hasCount() ? repeat.getCount() : null,
                        repeat.hasFrequency() ? repeat.getFrequency() : null,
                        repeat.hasPeriod() ? repeat.getPeriod() : null,
                        repeat.hasPeriodUnit() ? repeat.getPeriodUnit().toCode() : null
                );
            }
        }
        return null;
    }

    private IntelligenceActionInfo buildIntelligenceActionInfo(PlanDefinition.PlanDefinitionActionComponent intelligenceAction) {
        // Extract condition (kind=applicability)
        String condLanguage = null;
        String condExpression = null;
        for (PlanDefinition.PlanDefinitionActionConditionComponent cond : intelligenceAction.getCondition()) {
            if (cond.getKind() == PlanDefinition.ActionConditionKind.APPLICABILITY
                    && cond.hasExpression()
                    && cond.getExpression().hasExpression()) {
                condLanguage = cond.getExpression().getLanguage();
                condExpression = cond.getExpression().getExpression();
                break;
            }
        }

        // Skip intelligence actions without a condition
        if (condLanguage == null || condExpression == null) return null;

        // Extract definitionCanonical
        String definitionCanonical = null;
        if (intelligenceAction.hasDefinition() && intelligenceAction.getDefinition() instanceof CanonicalType canonical) {
            definitionCanonical = canonical.getValue();
        }

        // Skip intelligence actions without a definitionCanonical
        if (definitionCanonical == null) return null;

        // Extract required severity and destination extensions
        String severity = extractCodeExtension(intelligenceAction,
                "http://openphc.org/fhir/StructureDefinition/intelligence-severity");
        String intelligenceDestination = extractCodeExtension(intelligenceAction,
                "http://openphc.org/fhir/StructureDefinition/intelligence-destination");

        // Reject intelligence actions missing required extensions
        if (severity == null) {
            throw new IllegalArgumentException(
                    "Intelligence action '" + intelligenceAction.getId()
                            + "' is missing required extension: intelligence-severity");
        }
        if (intelligenceDestination == null) {
            throw new IllegalArgumentException(
                    "Intelligence action '" + intelligenceAction.getId()
                            + "' is missing required extension: intelligence-destination");
        }

        return new IntelligenceActionInfo(
                intelligenceAction.getId(),
                condLanguage,
                condExpression,
                definitionCanonical,
                severity,
                intelligenceDestination
        );
    }

    private Integer extractToleranceDays(PlanDefinition.PlanDefinitionActionComponent action) {
        Extension ext = action.getExtensionByUrl("http://openphc.org/fhir/StructureDefinition/tolerance-days");
        if (ext != null && ext.getValue() instanceof IntegerType intVal) {
            return intVal.getValue();
        }
        return null;
    }

    private String extractCodeExtension(PlanDefinition.PlanDefinitionActionComponent action, String url) {
        Extension ext = action.getExtensionByUrl(url);
        if (ext != null && ext.getValue() instanceof CodeType codeVal) {
            return codeVal.getCode();
        }
        return null;
    }

    private boolean hasData(TriggerDefinition trigger) {
        return trigger.getData() != null && !trigger.getData().isEmpty();
    }

    private Expression getCondition(TriggerDefinition trigger) {
        if (trigger.hasCondition()) {
            Expression cond = trigger.getCondition();
            if (cond.hasExpression() && !cond.getExpression().isBlank()) {
                return cond;
            }
        }
        return null;
    }

    /**
     * Parse a FHIR {@code DataRequirement.type} code into a {@link ResourceType}. Rejected at
     * protocol load time (mapped to 400) if the code is not a known FHIR resource type.
     */
    private ResourceType parseResourceType(String code) {
        try {
            return ResourceType.valueOf(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown FHIR resource type in trigger data.type: " + code, e);
        }
    }

    /**
     * One decomposed trigger coordinate: a single (resourceType, path, codeSystem, codeValue) tuple
     * for one action of one protocol definition.
     *
     * <p>Deliberately persistence-agnostic. The service that owns the {@code trigger_index} table maps
     * these onto its own entity, so the parser stays usable by any service that only wants to read a
     * PlanDefinition.
     */
    public record TriggerIndexEntry(
            ResourceType resourceType,
            String path,
            String codeSystem,
            String codeValue,
            UUID protocolDefinitionId,
            String actionId
    ) {}

    // ── Inner record types for structured extraction ──

    /**
     * One step, with nested sub-steps flattened to peers.
     *
     * @param groupId the action id of the nearest step group enclosing this step, or null for a step
     *                outside any group
     */
    public record StepMetadata(
            String id,
            String title,
            List<TriggerInfo> triggers,
            List<RelatedStepInfo> relatedSteps,
            TimingInfo timing,
            Integer toleranceDays,
            String requiredBehavior,
            List<IntelligenceActionInfo> intelligenceActions,
            String groupId
    ) {
        /** A step outside any group. */
        public StepMetadata(String id,
                            String title,
                            List<TriggerInfo> triggers,
                            List<RelatedStepInfo> relatedSteps,
                            TimingInfo timing,
                            Integer toleranceDays,
                            String requiredBehavior,
                            List<IntelligenceActionInfo> intelligenceActions) {
            this(id, title, triggers, relatedSteps, timing, toleranceDays, requiredBehavior,
                    intelligenceActions, null);
        }
    }

    /**
     * A step group: a {@code step}-typed action with {@code groupingBehavior: logical-group}. It has no
     * trigger and is never matched to an event. Its members are the steps whose {@code groupId}, and the
     * groups whose {@code parentGroupId}, name it.
     *
     * @param parentGroupId     the enclosing group's action id, or null for a top-level group
     * @param requiredBehavior  {@code "must"} makes the group mandatory; anything else, including
     *                          absent, makes it optional, as for steps. An optional group is never
     *                          opened in advance and never counts towards its parent's completion.
     * @param selectionBehavior FHIR {@code selectionBehavior}; absent and {@code all} both mean every
     *                          mandatory member must be done
     */
    public record GroupMetadata(
            String id,
            String title,
            String parentGroupId,
            List<RelatedStepInfo> relatedSteps,
            TimingInfo timing,
            Integer toleranceDays,
            String requiredBehavior,
            String selectionBehavior,
            CycleBoundary cycleBoundary,
            List<IntelligenceActionInfo> intelligenceActions
    ) {
        public boolean isMandatory() {
            return RequiredBehavior.isMandatory(requiredBehavior);
        }
    }

    /**
     * A forward edge to a step group: {@code group} depends on the prerequisite named by
     * {@code edge.actionId()}, positioned by {@code edge}'s relationship and offset.
     */
    public record GroupDependency(
            GroupMetadata group,
            RelatedStepInfo edge
    ) {}

    /**
     * What an instance of a group creates when it opens.
     *
     * @param steps  the mandatory member steps to create
     * @param groups the mandatory member groups to open
     */
    public record EntryMembers(
            List<StepMetadata> steps,
            List<GroupMetadata> groups
    ) {}

    public record TriggerInfo(
            List<DataRequirementInfo> dataRequirements,
            ConditionInfo condition
    ) {}

    public record DataRequirementInfo(
            String resourceType,
            List<CodeFilterInfo> codeFilters
    ) {}

    public record CodeFilterInfo(
            String path,
            List<CodingInfo> codes
    ) {}

    public record CodingInfo(
            String system,
            String code
    ) {}

    public record ConditionInfo(
            String language,
            String expression
    ) {}

    public record RelatedStepInfo(
            String actionId,
            String relationship,
            java.math.BigDecimal offsetValue,
            String offsetUnit
    ) {}

    /**
     * A forward edge in the dependency graph: {@code step} depends on the prerequisite named by
     * {@code edge.actionId()}, positioned by {@code edge}'s relationship and offset.
     *
     * @param step the dependent step — the one to create once the prerequisite completes
     * @param edge the {@code relatedAction} the dependent step declares toward that prerequisite
     */
    public record StepDependency(
            StepMetadata step,
            RelatedStepInfo edge
    ) {}

    public record TimingInfo(
            Integer count,
            Integer frequency,
            java.math.BigDecimal period,
            String periodUnit
    ) {}

    public record ConditionOnlyTriggerInfo(
            String actionId,
            String conditionLanguage,
            String conditionExpression
    ) {}

    public record IntelligenceActionInfo(
            String actionId,
            String conditionLanguage,
            String conditionExpression,
            String definitionCanonical,
            String severity,
            String intelligenceDestination
    ) {}


}
