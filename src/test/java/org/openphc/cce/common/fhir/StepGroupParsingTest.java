package org.openphc.cce.common.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Duration;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.PlanDefinition.ActionGroupingBehavior;
import org.hl7.fhir.r4.model.PlanDefinition.ActionRelationshipType;
import org.hl7.fhir.r4.model.PlanDefinition.ActionRequiredBehavior;
import org.hl7.fhir.r4.model.PlanDefinition.ActionSelectionBehavior;
import org.hl7.fhir.r4.model.PlanDefinition.PlanDefinitionActionComponent;
import org.hl7.fhir.r4.model.Timing;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.fhir.PlanDefinitionParser.GroupMetadata;
import org.openphc.cce.common.fhir.PlanDefinitionParser.StepMetadata;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step groups in the PlanDefinition: a {@code step}-typed action with {@code groupingBehavior:
 * logical-group}. A group is never a step and never matched; its members are.
 */
class StepGroupParsingTest {

    private static FhirContext fhirContext;
    private PlanDefinitionParser parser;

    @BeforeAll
    static void initFhirContext() {
        fhirContext = FhirContext.forR4();
    }

    @BeforeEach
    void setUp() {
        parser = new PlanDefinitionParser(fhirContext);
    }

    // ── Building blocks ──

    private static PlanDefinitionActionComponent step(PlanDefinitionActionComponent action, String id,
                                                      String requiredBehavior) {
        action.setId(id);
        action.getType().addCoding(new Coding()
                .setSystem("http://openphc.org/fhir/CodeSystem/action-type")
                .setCode("step"));
        if (requiredBehavior != null) {
            action.setRequiredBehavior(ActionRequiredBehavior.fromCode(requiredBehavior));
        }
        return action;
    }

    private static PlanDefinitionActionComponent group(PlanDefinitionActionComponent action, String id,
                                                       String requiredBehavior) {
        step(action, id, requiredBehavior);
        action.setGroupingBehavior(ActionGroupingBehavior.LOGICALGROUP);
        return action;
    }

    private static PlanDefinitionActionComponent triggeredStep(PlanDefinitionActionComponent action,
                                                               String id, String loincCode) {
        step(action, id, "must");
        action.addTrigger()
                .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
                .addData()
                .setType("Observation")
                .addCodeFilter()
                .setPath("code")
                .addCode(new Coding().setSystem("http://loinc.org").setCode(loincCode));
        return action;
    }

    private static void after(PlanDefinitionActionComponent action, String prerequisiteId, int days) {
        action.addRelatedAction()
                .setActionId(prerequisiteId)
                .setRelationship(ActionRelationshipType.AFTEREND)
                .setOffset(new Duration().setValue(days).setUnit("d"));
    }

    /**
     * A quarterly-review shape: a mandatory group holding vitals (day 0), an HbA1c draw 3 days after
     * vitals, and an optional consultation.
     */
    private static PlanDefinition reviewProtocol() {
        PlanDefinition pd = new PlanDefinition();
        PlanDefinitionActionComponent review = group(pd.addAction(), "review", "must");
        triggeredStep(review.addAction(), "vitals", "85354-9");
        PlanDefinitionActionComponent hba1c = triggeredStep(review.addAction(), "hba1c", "4548-4");
        after(hba1c, "vitals", 3);
        PlanDefinitionActionComponent consult = triggeredStep(review.addAction(), "consult", "11488-4");
        consult.setRequiredBehavior(ActionRequiredBehavior.COULD);
        return pd;
    }

    @Nested
    class Extraction {

        @Test
        void aGroupIsNeverAStep_andEachMemberNamesIt() {
            PlanDefinition pd = reviewProtocol();

            List<StepMetadata> steps = parser.extractSteps(pd);
            List<GroupMetadata> groups = parser.extractGroups(pd);

            assertEquals(List.of("vitals", "hba1c", "consult"), steps.stream().map(StepMetadata::id).toList());
            assertTrue(steps.stream().allMatch(s -> "review".equals(s.groupId())));
            assertEquals(1, groups.size());
            assertEquals("review", groups.get(0).id());
            assertNull(groups.get(0).parentGroupId());
            assertTrue(groups.get(0).isMandatory());
        }

        @Test
        void eachStepNamesItsNearestGroup_throughPlainNesting() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = group(pd.addAction(), "journey", "must");
            PlanDefinitionActionComponent checkIn = triggeredStep(journey.addAction(), "check-in", "1");
            // A step nested under a step is flattened as before, but still belongs to the group.
            triggeredStep(checkIn.addAction(), "triage", "2");
            PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation", "must");
            triggeredStep(consultation.addAction(), "history", "3");

            List<StepMetadata> steps = parser.extractSteps(pd);
            List<GroupMetadata> groups = parser.extractGroups(pd);

            assertEquals("journey", PlanDefinitionParser.findStep(steps, "check-in").groupId());
            assertEquals("journey", PlanDefinitionParser.findStep(steps, "triage").groupId());
            assertEquals("consultation", PlanDefinitionParser.findStep(steps, "history").groupId());
            assertEquals("journey", PlanDefinitionParser.findGroup(groups, "consultation").parentGroupId());
        }

        @Test
        void presentationGroupingsStaySteps() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent visit = triggeredStep(pd.addAction(), "visit", "1");
            visit.setGroupingBehavior(ActionGroupingBehavior.VISUALGROUP);
            triggeredStep(visit.addAction(), "bp", "2");

            List<StepMetadata> steps = parser.extractSteps(pd);

            assertEquals(List.of("visit", "bp"), steps.stream().map(StepMetadata::id).toList());
            assertTrue(parser.extractGroups(pd).isEmpty());
            assertTrue(steps.stream().allMatch(s -> s.groupId() == null));
        }

        @Test
        void stepsOutsideAnyGroupHaveNoGroup() {
            PlanDefinition pd = new PlanDefinition();
            triggeredStep(pd.addAction(), "registration", "1");

            assertNull(parser.extractSteps(pd).get(0).groupId());
        }

        @Test
        void theParsedProtocolCarriesItsGroups() {
            String json = fhirContext.newJsonParser().encodeResourceToString(reviewProtocol());
            ParsedProtocolCache cache = new ParsedProtocolCache(parser, 16);

            ParsedProtocolCache.ParsedProtocol protocol = cache.get(UUID.randomUUID(), () -> json);

            assertTrue(protocol.hasGroups());
            assertNotNull(protocol.group("review"));
            assertNull(protocol.step("review"), "a group is not a step");
            assertEquals("review", protocol.step("vitals").groupId());
        }
    }

    @Nested
    class Triggers {

        @Test
        void aStepNestedTwoGroupsDeepIsIndexed() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = group(pd.addAction(), "journey", "must");
            PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation", "must");
            triggeredStep(consultation.addAction(), "history", "11329-0");

            var entries = parser.buildTriggerIndexEntries(pd, UUID.randomUUID());

            assertEquals(1, entries.size());
            assertEquals("history", entries.get(0).actionId());
        }

        @Test
        void aGroupsOwnTriggerIsNeverIndexedOrEvaluated() {
            // validateGroups refuses this at load; the parser must still never index it.
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent review = triggeredStep(pd.addAction(), "review", "1");
            review.setGroupingBehavior(ActionGroupingBehavior.LOGICALGROUP);
            review.addTrigger().setType(TriggerDefinition.TriggerType.NAMEDEVENT)
                    .setCondition(new org.hl7.fhir.r4.model.Expression()
                            .setLanguage("text/fhirpath").setExpression("true"));
            PlanDefinitionActionComponent vitals = step(review.addAction(), "vitals", "must");
            vitals.addTrigger().setType(TriggerDefinition.TriggerType.NAMEDEVENT)
                    .setCondition(new org.hl7.fhir.r4.model.Expression()
                            .setLanguage("text/fhirpath").setExpression("false"));

            assertTrue(parser.buildTriggerIndexEntries(pd, UUID.randomUUID()).isEmpty());
            assertEquals(List.of("vitals"), parser.extractConditionOnlyTriggers(pd).stream()
                    .map(PlanDefinitionParser.ConditionOnlyTriggerInfo::actionId).toList());
        }
    }

    @Nested
    class Graph {

        @Test
        void aGroupFollowingAStepIsAGroupSuccessor() {
            PlanDefinition pd = new PlanDefinition();
            triggeredStep(pd.addAction(), "registration", "1");
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2", "must");
            after(visit, "registration", 42);
            triggeredStep(visit.addAction(), "bp", "2");

            var graph = PlanDefinitionParser.buildDependencyGraph(
                    parser.extractSteps(pd), parser.extractGroups(pd));

            assertEquals(List.of("visit-2"), graph.groupSuccessorsOf("registration").stream()
                    .map(d -> d.group().id()).toList());
            assertEquals(new BigDecimal("42"), graph.groupSuccessorsOf("registration").get(0).edge().offsetValue());
            assertTrue(graph.successorsOf("registration").isEmpty(), "a group is not created as a step");
            assertEquals(List.of("registration"), graph.predecessorsOf("visit-2"));
        }

        @Test
        void aStepFollowingAGroupIsAStepSuccessorOfTheGroup() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2", "must");
            triggeredStep(visit.addAction(), "bp", "1");
            PlanDefinitionActionComponent referral = triggeredStep(pd.addAction(), "referral", "2");
            after(referral, "visit-2", 0);

            var graph = PlanDefinitionParser.buildDependencyGraph(
                    parser.extractSteps(pd), parser.extractGroups(pd));

            assertEquals(List.of("referral"), graph.successorsOf("visit-2").stream()
                    .map(d -> d.step().id()).toList());
        }

        @Test
        void aRelatedActionNamingAGroupIsNotDangling() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2", "must");
            triggeredStep(visit.addAction(), "bp", "1");
            PlanDefinitionActionComponent referral = triggeredStep(pd.addAction(), "referral", "2");
            after(referral, "visit-2", 0);
            after(referral, "no-such-action", 0);

            List<String> dangling = PlanDefinitionParser.findDanglingRelatedActions(
                    parser.extractSteps(pd), parser.extractGroups(pd));

            assertEquals(List.of("action 'referral' -> unknown action 'no-such-action'"), dangling);
        }
    }

    @Nested
    class Members {

        @Test
        void entryMembersAreTheMandatoryMembersWithNoPredecessorInsideTheGroup() {
            PlanDefinition pd = reviewProtocol();
            List<StepMetadata> steps = parser.extractSteps(pd);
            List<GroupMetadata> groups = parser.extractGroups(pd);

            var entry = PlanDefinitionParser.entryMembers("review", steps, groups,
                    PlanDefinitionParser.buildDependencyGraph(steps, groups));

            // hba1c waits on vitals; the consultation is optional.
            assertEquals(List.of("vitals"), entry.steps().stream().map(StepMetadata::id).toList());
            assertTrue(entry.groups().isEmpty());
        }

        @Test
        void aMemberWaitingOnAStepInANestedGroupIsNotAnEntryMember() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = group(pd.addAction(), "journey", "must");
            PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation", "must");
            triggeredStep(consultation.addAction(), "diagnosis", "1");
            PlanDefinitionActionComponent dispensing = triggeredStep(journey.addAction(), "dispensing", "2");
            after(dispensing, "diagnosis", 0);
            List<StepMetadata> steps = parser.extractSteps(pd);
            List<GroupMetadata> groups = parser.extractGroups(pd);

            var entry = PlanDefinitionParser.entryMembers("journey", steps, groups,
                    PlanDefinitionParser.buildDependencyGraph(steps, groups));

            assertTrue(entry.steps().isEmpty());
            assertEquals(List.of("consultation"), entry.groups().stream().map(GroupMetadata::id).toList());
        }

        @Test
        void aGroupWithNoRequiredBehaviorIsOptional_asAStepIs() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent counselling = group(pd.addAction(), "counselling", null);
            triggeredStep(counselling.addAction(), "diet", "1");

            assertFalse(parser.extractGroups(pd).get(0).isMandatory());
        }
    }

    @Nested
    class Validation {

        @Test
        void aWellFormedGroupLoads() {
            PlanDefinition pd = reviewProtocol();

            assertDoesNotThrow(() -> parser.validateGroups(pd));
            assertDoesNotThrow(() -> parser.validateActionTypes(pd));
            assertDoesNotThrow(() -> parser.validateTriggers(pd));
        }

        private void assertRejected(PlanDefinition pd, String expectedFragment) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> parser.validateGroups(pd));
            assertTrue(ex.getMessage().contains(expectedFragment),
                    () -> "expected '" + expectedFragment + "' in: " + ex.getMessage());
        }

        @Test
        void aGroupWithATriggerIsRejected() {
            PlanDefinition pd = reviewProtocol();
            pd.getAction().get(0).addTrigger().setType(TriggerDefinition.TriggerType.NAMEDEVENT);

            assertRejected(pd, "has a trigger");
        }

        @Test
        void aGroupWithNoStepIsRejected_evenThroughEmptyNestedGroups() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent outer = group(pd.addAction(), "outer", "must");
            group(outer.addAction(), "inner", "must");

            assertRejected(pd, "contains no step");
        }

        @Test
        void aSelectionBehaviorOtherThanAllIsRejected() {
            PlanDefinition pd = reviewProtocol();
            pd.getAction().get(0).setSelectionBehavior(ActionSelectionBehavior.EXACTLYONE);

            assertRejected(pd, "selectionBehavior 'exactly-one'");
        }

        @Test
        void selectionBehaviorAllIsAccepted() {
            PlanDefinition pd = reviewProtocol();
            pd.getAction().get(0).setSelectionBehavior(ActionSelectionBehavior.ALL);

            assertDoesNotThrow(() -> parser.validateGroups(pd));
        }

        @Test
        void anOptionalGroupWithADeadlineIsRejected() {
            PlanDefinition pd = reviewProtocol();
            PlanDefinitionActionComponent review = pd.getAction().get(0);
            review.setRequiredBehavior(ActionRequiredBehavior.COULD);
            review.addExtension(new Extension(
                    "http://openphc.org/fhir/StructureDefinition/tolerance-days", new IntegerType(14)));

            assertRejected(pd, "is optional");
        }

        @Test
        void aGroupDeadlineIsNotSupportedYet() {
            PlanDefinition pd = reviewProtocol();
            pd.getAction().get(0).addExtension(new Extension(
                    "http://openphc.org/fhir/StructureDefinition/tolerance-days", new IntegerType(14)));

            assertRejected(pd, "Group deadlines are not supported yet");
        }

        @Test
        void aRepeatingGroupIsNotSupportedYet() {
            PlanDefinition pd = reviewProtocol();
            Timing timing = new Timing();
            timing.getRepeat().setPeriod(3).setPeriodUnit(Timing.UnitsOfTime.MO).setCount(4);
            pd.getAction().get(0).setTiming(timing);

            assertRejected(pd, "Repeating groups are not supported yet");
        }

        @Test
        void theCycleBoundaryExtensionIsNotSupportedYet() {
            PlanDefinition pd = reviewProtocol();
            pd.getAction().get(0).addExtension(new Extension(
                    PlanDefinitionParser.CYCLE_BOUNDARY_EXTENSION_URL, new CodeType("calendar-day")));

            assertRejected(pd, "cycle-boundary");
        }

        @Test
        void aVisitDrivenGroupWithADeadlineIsRejected_beforeEitherFeatureIsBuilt() {
            // A permanent rule, so it must fire ahead of the phase gates on tolerance-days and
            // cycle-boundary: lifting either gate later must not let the pair through.
            PlanDefinition pd = reviewProtocol();
            PlanDefinitionActionComponent review = pd.getAction().get(0);
            review.addExtension(new Extension(
                    PlanDefinitionParser.CYCLE_BOUNDARY_EXTENSION_URL, new CodeType("calendar-day")));
            review.addExtension(new Extension(
                    "http://openphc.org/fhir/StructureDefinition/tolerance-days", new IntegerType(14)));

            assertRejected(pd, "has the cycle-boundary extension and declares tolerance-days");
        }

        @Test
        void aStepFollowingAGroupIsNotSupportedYet() {
            PlanDefinition pd = reviewProtocol();
            after(triggeredStep(pd.addAction(), "referral", "57133-1"), "review", 7);

            assertRejected(pd, "Action 'referral' follows group 'review'");
        }

        @Test
        void aGroupFollowingAGroupIsNotSupportedYet() {
            PlanDefinition pd = reviewProtocol();
            PlanDefinitionActionComponent followUp = group(pd.addAction(), "follow-up", "must");
            triggeredStep(followUp.addAction(), "recheck", "8480-6");
            after(followUp, "review", 30);

            assertRejected(pd, "Action 'follow-up' follows group 'review'");
        }

        @Test
        void aGroupFollowingAStepLoads() {
            PlanDefinition pd = new PlanDefinition();
            triggeredStep(pd.addAction(), "registration", "1");
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit", "must");
            triggeredStep(visit.addAction(), "bp", "85354-9");
            after(visit, "registration", 42);

            assertDoesNotThrow(() -> parser.validateGroups(pd));
        }

        @Test
        void anIntelligenceActionOnAGroupIsNotSupportedYet() {
            PlanDefinition pd = reviewProtocol();
            PlanDefinitionActionComponent alert = pd.getAction().get(0).addAction();
            alert.setId("missed-review-alert");
            alert.getType().addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/action-type")
                    .setCode("fire-event"));

            assertRejected(pd, "Intelligence actions on a group are not supported yet");
        }

        @Test
        void aFireEventActionCannotBeAGroup() {
            PlanDefinition pd = reviewProtocol();
            PlanDefinitionActionComponent vitals = pd.getAction().get(0).getAction().get(0);
            PlanDefinitionActionComponent alert = vitals.addAction();
            alert.setId("alert");
            alert.getType().addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/action-type")
                    .setCode("fire-event"));
            alert.setGroupingBehavior(ActionGroupingBehavior.LOGICALGROUP);

            assertRejected(pd, "fire-event action with groupingBehavior 'logical-group'");
        }
    }
}
