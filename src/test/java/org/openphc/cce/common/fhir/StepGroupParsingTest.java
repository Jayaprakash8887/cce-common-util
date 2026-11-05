package org.openphc.cce.common.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Duration;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.PlanDefinition.ActionGroupingBehavior;
import org.hl7.fhir.r4.model.PlanDefinition.ActionRelationshipType;
import org.hl7.fhir.r4.model.PlanDefinition.ActionRequiredBehavior;
import org.hl7.fhir.r4.model.PlanDefinition.ActionSelectionBehavior;
import org.hl7.fhir.r4.model.PlanDefinition.PlanDefinitionActionComponent;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Timing;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.common.enums.CycleBoundary;
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

    private static PlanDefinitionActionComponent repeatingBy(PlanDefinitionActionComponent group, String code) {
        group.addExtension(new Extension(PlanDefinitionParser.CYCLE_BOUNDARY_EXTENSION_URL, new CodeType(code)));
        return group;
    }

    /**
     * A visit that repeats per calendar day: check-in, then a Consultation of history and examination.
     * Repeating by entry step, the Consultation names history as its entry step.
     */
    private static PlanDefinition journeyProtocol(String consultationBoundary) {
        PlanDefinition pd = new PlanDefinition();
        PlanDefinitionActionComponent journey = repeatingBy(group(pd.addAction(), "journey", null), "calendar-day");
        triggeredStep(journey.addAction(), "check-in", "1");
        PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation", null);
        if (consultationBoundary != null) {
            repeatingBy(consultation, consultationBoundary);
        }
        if ("entry-step".equals(consultationBoundary)) {
            namingEntryStep(consultation, "history");
        }
        triggeredStep(consultation.addAction(), "history", "2");
        after(triggeredStep(consultation.addAction(), "examination", "3"), "history", 0);
        return pd;
    }

    private static PlanDefinitionActionComponent namingEntryStep(PlanDefinitionActionComponent group, String stepId) {
        group.addExtension(new Extension(PlanDefinitionParser.CYCLE_ENTRY_STEP_EXTENSION_URL, new IdType(stepId)));
        return group;
    }

    /** A Consultation repeating by entry step, of history and BP, neither waiting on the other, naming no entry step. */
    private static PlanDefinition consultationOfHistoryAndBp() {
        PlanDefinition pd = new PlanDefinition();
        PlanDefinitionActionComponent consultation =
                repeatingBy(group(pd.addAction(), "consultation", null), "entry-step");
        triggeredStep(consultation.addAction(), "history", "10164-2");
        triggeredStep(consultation.addAction(), "bp", "85354-9");
        return pd;
    }

    private static void after(PlanDefinitionActionComponent action, String prerequisiteId, int days) {
        action.addRelatedAction()
                .setActionId(prerequisiteId)
                .setRelationship(ActionRelationshipType.AFTEREND)
                .setOffset(new Duration().setValue(days).setUnit("d"));
    }

    /**
     * A quarterly-review shape: a group holding vitals (day 0), an HbA1c draw 3 days after vitals, and an
     * optional consultation.
     */
    private static PlanDefinition reviewProtocol() {
        PlanDefinition pd = new PlanDefinition();
        PlanDefinitionActionComponent review = group(pd.addAction(), "review", null);
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
            assertFalse(groups.get(0).isMandatory(), "a group with no requiredBehavior is optional");
        }

        @Test
        void eachStepNamesItsNearestGroup_throughPlainNesting() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = group(pd.addAction(), "journey", null);
            PlanDefinitionActionComponent checkIn = triggeredStep(journey.addAction(), "check-in", "1");
            // A step nested under a step is flattened as before, but still belongs to the group.
            triggeredStep(checkIn.addAction(), "triage", "2");
            PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation", null);
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
            PlanDefinitionActionComponent journey = group(pd.addAction(), "journey", null);
            PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation", null);
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
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2", null);
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
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2", null);
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
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2", null);
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
        void membersCreatedOnOpen_areTheMandatoryMembersWithNoPredecessorInsideTheGroup() {
            PlanDefinition pd = reviewProtocol();
            List<StepMetadata> steps = parser.extractSteps(pd);
            List<GroupMetadata> groups = parser.extractGroups(pd);

            var created = PlanDefinitionParser.membersCreatedOnOpen("review", steps, groups,
                    PlanDefinitionParser.buildDependencyGraph(steps, groups));

            // hba1c waits on vitals; the consultation is optional.
            assertEquals(List.of("vitals"), created.steps().stream().map(StepMetadata::id).toList());
            assertTrue(created.groups().isEmpty());
        }

        @Test
        void aMemberWaitingOnAStepInANestedGroup_isNotCreatedOnOpen() {
            // Mandatory groups are refused at load until a later phase (plan point 11). The parser keeps their
            // rule for then, so it is tested here without the load checks.
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = group(pd.addAction(), "journey", "must");
            PlanDefinitionActionComponent consultation = group(journey.addAction(), "consultation", "must");
            triggeredStep(consultation.addAction(), "diagnosis", "1");
            PlanDefinitionActionComponent dispensing = triggeredStep(journey.addAction(), "dispensing", "2");
            after(dispensing, "diagnosis", 0);
            List<StepMetadata> steps = parser.extractSteps(pd);
            List<GroupMetadata> groups = parser.extractGroups(pd);

            var created = PlanDefinitionParser.membersCreatedOnOpen("journey", steps, groups,
                    PlanDefinitionParser.buildDependencyGraph(steps, groups));

            assertTrue(created.steps().isEmpty());
            assertEquals(List.of("consultation"), created.groups().stream().map(GroupMetadata::id).toList());
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
    class MandatoryPredecessors {

        /**
         * Registration and an optional leaflet; ANC visit 2 following both, holding BP, and a nested
         * nutrition group of diet that waits on BP; a treatment after BP, outside the visit.
         */
        private PlanDefinition ancProtocol() {
            PlanDefinition pd = new PlanDefinition();
            triggeredStep(pd.addAction(), "registration", "1");
            triggeredStep(pd.addAction(), "leaflet", "2").setRequiredBehavior(ActionRequiredBehavior.COULD);
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit-2", null);
            after(visit, "registration", 42);
            after(visit, "leaflet", 0);
            triggeredStep(visit.addAction(), "bp", "3");
            PlanDefinitionActionComponent nutrition = group(visit.addAction(), "nutrition", null);
            after(triggeredStep(nutrition.addAction(), "diet", "4"), "bp", 0);
            after(triggeredStep(pd.addAction(), "treatment", "5"), "bp", 0);
            return pd;
        }

        private Set<String> mustPredecessorsOf(String observedStepId) {
            PlanDefinition pd = ancProtocol();
            List<StepMetadata> steps = parser.extractSteps(pd);
            List<GroupMetadata> groups = parser.extractGroups(pd);
            return PlanDefinitionParser.computeMustPredecessorSteps(List.of(observedStepId), steps, groups,
                    PlanDefinitionParser.buildDependencyGraph(steps, groups));
        }

        @Test
        void aStepWaitsOnWhatItsGroupWaitsOn() {
            assertEquals(Set.of("registration"), mustPredecessorsOf("bp"), "the optional leaflet is not required");
        }

        @Test
        void andOnWhatEveryEnclosingGroupWaitsOn() {
            assertEquals(Set.of("bp", "registration"), mustPredecessorsOf("diet"));
        }

        @Test
        void aStepOutsideTheGroup_reachesItThroughAStepInside() {
            assertEquals(Set.of("bp", "registration"), mustPredecessorsOf("treatment"));
        }

        @Test
        void aProtocolWithNoMandatoryStepHasNoMandatoryPredecessors() {
            PlanDefinition pd = new PlanDefinition();
            triggeredStep(pd.addAction(), "a", "1").setRequiredBehavior(ActionRequiredBehavior.COULD);
            List<StepMetadata> steps = parser.extractSteps(pd);

            assertTrue(PlanDefinitionParser.computeMustPredecessorSteps(List.of("a"), steps, List.of(),
                    PlanDefinitionParser.buildDependencyGraph(steps)).isEmpty());
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
            PlanDefinitionActionComponent outer = group(pd.addAction(), "outer", null);
            group(outer.addAction(), "inner", null);

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
        void aGroupDeclaringRequiredBehaviorIsRejected_everyGroupIsOptionalForNow() {
            for (ActionRequiredBehavior requiredBehavior : List.of(ActionRequiredBehavior.MUST, ActionRequiredBehavior.COULD)) {
                PlanDefinition pd = reviewProtocol();
                pd.getAction().get(0).setRequiredBehavior(requiredBehavior);

                assertRejected(pd, "Group 'review' declares requiredBehavior '" + requiredBehavior.toCode()
                        + "'. Mandatory groups are not supported yet");
            }
        }

        @Test
        void aGroupDeadlineIsRejected_aGroupIsOptional() {
            PlanDefinition pd = reviewProtocol();
            pd.getAction().get(0).addExtension(new Extension(
                    "http://openphc.org/fhir/StructureDefinition/tolerance-days", new IntegerType(14)));

            assertRejected(pd, "is optional (requiredBehavior=<absent>) and declares tolerance-days");
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
        void aVisitDrivenJourneyWithAnEntryStepConsultationLoads_andEachKeepsItsBoundary() {
            PlanDefinition pd = journeyProtocol("entry-step");

            assertDoesNotThrow(() -> parser.validateGroups(pd));
            List<GroupMetadata> groups = parser.extractGroups(pd);
            assertEquals(CycleBoundary.CALENDAR_DAY, groups.get(0).cycleBoundary());
            assertEquals(CycleBoundary.ENTRY_STEP, groups.get(1).cycleBoundary());
        }

        @Test
        void aGroupWithNoCycleBoundaryHasNone() {
            GroupMetadata review = parser.extractGroups(reviewProtocol()).get(0);

            assertNull(review.cycleBoundary());
        }

        @Test
        void anUnknownCycleBoundaryIsRejected() {
            PlanDefinition pd = reviewProtocol();
            repeatingBy(pd.getAction().get(0), "encounter");

            assertRejected(pd, "the code 'encounter'. Supported codes: calendar-day, entry-step");
        }

        @Test
        void aCycleBoundaryThatIsNotACodeIsRejected() {
            PlanDefinition pd = reviewProtocol();
            pd.getAction().get(0).addExtension(new Extension(
                    PlanDefinitionParser.CYCLE_BOUNDARY_EXTENSION_URL, new StringType("calendar-day")));

            assertRejected(pd, "no valueCode");
        }

        @Test
        void aCalendarDayGroupInsideACalendarDayGroupIsRejected() {
            assertRejected(journeyProtocol("calendar-day"),
                    "Group 'consultation' repeats by calendar-day inside a group that repeats by calendar-day");
        }

        /** A pregnancy repeating by entry step, holding an ANC visit; the visit's boundary is given. */
        private PlanDefinition pregnancyProtocol(String visitBoundary) {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent pregnancy =
                    namingEntryStep(repeatingBy(group(pd.addAction(), "pregnancy", null), "entry-step"), "registration");
            triggeredStep(pregnancy.addAction(), "registration", "1");
            PlanDefinitionActionComponent visit = repeatingBy(group(pregnancy.addAction(), "visit", null), visitBoundary);
            triggeredStep(visit.addAction(), "bp", "85354-9");
            return pd;
        }

        @Test
        void aCalendarDayGroupInsideAnEntryStepGroupLoads() {
            assertDoesNotThrow(() -> parser.validateGroups(pregnancyProtocol("calendar-day")));
        }

        @Test
        void aCalendarDayGroupInsideAnEntryStepGroupInsideACalendarDayGroupIsRejected() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent journey = repeatingBy(group(pd.addAction(), "journey", null), "calendar-day");
            triggeredStep(journey.addAction(), "check-in", "1");
            PlanDefinitionActionComponent consultation =
                    namingEntryStep(repeatingBy(group(journey.addAction(), "consultation", null), "entry-step"), "history");
            triggeredStep(consultation.addAction(), "history", "2");
            PlanDefinitionActionComponent round = repeatingBy(group(consultation.addAction(), "round", null), "calendar-day");
            triggeredStep(round.addAction(), "bp", "85354-9");

            assertRejected(pd, "Group 'round' repeats by calendar-day inside a group that repeats by calendar-day");
        }

        @Test
        void aOneOffGroupInsideARepeatingGroupLoads() {
            assertDoesNotThrow(() -> parser.validateGroups(journeyProtocol(null)));
        }

        @Test
        void theCycleBoundaryOnAPlainStepIsRejected() {
            PlanDefinition pd = reviewProtocol();
            repeatingBy(pd.getAction().get(0).getAction().get(0), "calendar-day");

            assertRejected(pd, "Action 'vitals' declares the cycle-boundary extension but is not a step group");
        }

        @Test
        void anEntryStepGroupThatNamesNoEntryStepIsRejected() {
            assertRejected(consultationOfHistoryAndBp(),
                    "Group 'consultation' repeats by entry-step but names no entry step");
        }

        @Test
        void theEntryStepIsTheStepTheGroupNames() {
            PlanDefinition pd = consultationOfHistoryAndBp();
            namingEntryStep(pd.getAction().get(0), "history");

            assertDoesNotThrow(() -> parser.validateGroups(pd));
            assertEquals("history", parser.extractGroups(pd).get(0).entryStepId());
        }

        @Test
        void aGroupThatDoesNotRepeatByEntryStepHasNoEntryStep() {
            List<GroupMetadata> groups = parser.extractGroups(journeyProtocol(null));

            assertNull(groups.get(0).entryStepId());
            assertNull(groups.get(1).entryStepId());
        }

        @Test
        void namingMoreThanOneEntryStepIsRejected() {
            PlanDefinition pd = consultationOfHistoryAndBp();
            namingEntryStep(namingEntryStep(pd.getAction().get(0), "history"), "bp");

            assertRejected(pd, "Group 'consultation' has the cycle-entry-step extension 2 times");
        }

        @Test
        void namingAnEntryStepOnAGroupThatDoesNotRepeatByEntryStepIsRejected() {
            PlanDefinition pd = reviewProtocol();
            namingEntryStep(pd.getAction().get(0), "vitals");

            assertRejected(pd, "Group 'review' names an entry step (cycle-entry-step) but does not repeat by entry-step");
        }

        @Test
        void namingAnEntryStepOnAPlainStepIsRejected() {
            PlanDefinition pd = consultationOfHistoryAndBp();
            namingEntryStep(pd.getAction().get(0), "history");
            namingEntryStep(pd.getAction().get(0).getAction().get(0), "history");

            assertRejected(pd, "Action 'history' declares the cycle-entry-step extension but is not a step group");
        }

        @Test
        void anEntryStepThatIsNotAnIdIsRejected() {
            PlanDefinition pd = consultationOfHistoryAndBp();
            pd.getAction().get(0).addExtension(new Extension(
                    PlanDefinitionParser.CYCLE_ENTRY_STEP_EXTENSION_URL, new StringType("history")));

            assertRejected(pd, "Group 'consultation' has the cycle-entry-step extension with no valueId");
        }

        @Test
        void namingAStepOutsideTheGroupIsRejected() {
            PlanDefinition pd = consultationOfHistoryAndBp();
            triggeredStep(pd.addAction(), "registration", "1");
            namingEntryStep(pd.getAction().get(0), "registration");

            assertRejected(pd, "Group 'consultation' names 'registration' as its entry step, which is not a step inside it");
        }

        @Test
        void aStepInANestedGroupCanBeTheEntryStep() {
            PlanDefinition pd = consultationOfHistoryAndBp();
            PlanDefinitionActionComponent intake = group(pd.getAction().get(0).addAction(), "intake", null);
            triggeredStep(intake.addAction(), "weight", "29463-7");
            namingEntryStep(pd.getAction().get(0), "weight");

            assertDoesNotThrow(() -> parser.validateGroups(pd));
            assertEquals("weight", parser.extractGroups(pd).get(0).entryStepId());
        }

        @Test
        void namingANestedGroupIsRejected() {
            PlanDefinition pd = consultationOfHistoryAndBp();
            PlanDefinitionActionComponent intake = group(pd.getAction().get(0).addAction(), "intake", null);
            triggeredStep(intake.addAction(), "weight", "29463-7");
            namingEntryStep(pd.getAction().get(0), "intake");

            assertRejected(pd, "Group 'consultation' names 'intake' as its entry step, which is not a step inside it");
        }

        @Test
        void namingAStepThatWaitsOnAnotherInsideTheGroupIsRejected() {
            PlanDefinition pd = new PlanDefinition();
            PlanDefinitionActionComponent consultation =
                    namingEntryStep(repeatingBy(group(pd.addAction(), "consultation", null), "entry-step"), "examination");
            triggeredStep(consultation.addAction(), "history", "2");
            after(triggeredStep(consultation.addAction(), "examination", "3"), "history", 0);

            assertRejected(pd, "Group 'consultation' names 'examination' as its entry step, but it waits on another action inside the group");
        }

        @Test
        void aVisitDrivenGroupWithADeadlineIsRejected_beforeEitherFeatureIsBuilt() {
            // A permanent rule, so it must fire ahead of the phase gates on tolerance-days and
            // cycle-boundary: lifting either gate later must not let the pair through.
            // Mandatory, as it could be once that gate lifts too.
            PlanDefinition pd = reviewProtocol();
            PlanDefinitionActionComponent review = pd.getAction().get(0);
            review.setRequiredBehavior(ActionRequiredBehavior.MUST);
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
            PlanDefinitionActionComponent followUp = group(pd.addAction(), "follow-up", null);
            triggeredStep(followUp.addAction(), "recheck", "8480-6");
            after(followUp, "review", 30);

            assertRejected(pd, "Action 'follow-up' follows group 'review'");
        }

        @Test
        void aGroupFollowingAStepLoads() {
            PlanDefinition pd = new PlanDefinition();
            triggeredStep(pd.addAction(), "registration", "1");
            PlanDefinitionActionComponent visit = group(pd.addAction(), "visit", null);
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
