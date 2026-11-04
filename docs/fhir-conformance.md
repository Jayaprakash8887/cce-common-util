# FHIR Conformance

> How CCE reads FHIR R4, and where it does something FHIR doesn't cover.

Protocols are written as FHIR R4 **PlanDefinition** resources. Intelligence actions are written as
**ActivityDefinition** resources. This document explains how CCE reads them.
- The code that reads them is [`PlanDefinitionParser`](library-reference.md#plandefinitionparser).
- Where the results are stored is in the [Data Dictionary](data-dictionary.md).

---

<a id="1-relatedaction-direction"></a>

## 1. `relatedAction`: the order of a patient's journey

A protocol is a journey: registration, then a first visit, then a lab test, and so on.
`PlanDefinition.action.relatedAction` is how a protocol says which step comes after which. CCE uses it
to follow each patient's journey through the protocol:
- **What comes next.** When a step is done, CCE creates the mandatory (`must`) steps that follow it, so
  they are awaited for this patient. This is called progressive instantiation.
- **When it is due.** A `relatedAction` can carry an offset, such as "3 days after". The next step is
  then due that long after the step it follows.
- **Steps done out of order.** A step recorded before the step it follows is reported as an order
  violation.
- **Steps skipped.** A step recorded with no record of a mandatory earlier step makes CCE create the
  missing earlier step, so the gap shows. This is called backfill.

A step that follows nothing, such as the first step of a protocol, starts the journey. It is created
when its own event arrives, or when its group opens.

### `after-end`: the only supported relationship

A `relatedAction` is written on the **later** step, and names the step it follows.

```json
{ "id": "lab-results",
  "relatedAction": [{ "actionId": "lab-order",
                      "relationship": "after-end",
                      "offsetDuration": { "value": 3, "unit": "d" } }] }
```

Read it as: *lab-results is due 3 days after lab-order is done.*
- `lab-order` comes first.
- The `relatedAction` is written on `lab-results`, the later step.

So a step's own `relatedAction` list names **what it waits for**, not what waits for it.

`after-end` is the default. A `relatedAction` with **no** `relationship` is read as `after-end`, so
it can be left out.

### A `relatedAction` to a step that doesn't exist

A `relatedAction` can name an `actionId` that no step in the protocol has, for example after a typo. It
then sets no order. `findDanglingRelatedActions` reports each one when the protocol is loaded.

This is a warning, not an error. The protocol still loads. It was accepted before, so refusing it now
would break a system that already sends such protocols.

### A step in a `calendar-day` group that follows a step outside the group

A `relatedAction` does two things. It says which step comes first, and its offset says when the step is
due. Inside a `calendar-day` group, only the first works when the earlier step is outside the group.

Example: a patient journey that repeats each day the patient comes, with a consent inside it. The consent
says "2 days after registration", and registration is outside the journey.

```
registration
journey [cycle-boundary: calendar-day] {
  check-in
  consent   (relatedAction: after registration, offset 2 days)
}
```

- **The order still works.** A consent recorded before registration is reported as an order violation.
  A consent recorded with no registration at all makes CCE create the missing registration step.
- **The offset is not used.** A visit only starts when the patient comes, never in advance. When it
  starts, it creates the consent with the visit's own due date. "Registration plus 2 days" is never
  used, so the consent's `tolerance-days` never applies either.
- **The consent is created again in every visit.** A step that should happen only once belongs outside
  the journey.

`findOffsetsIgnoredInCalendarDayGroups` finds every such offset. The protocol service logs a warning for
each one when it loads the PlanDefinition. The load still succeeds. An offset of zero is not reported,
since it only sets the order. Neither is a `relatedAction` to a step inside the same group.

To give the first visit a deadline, put the `relatedAction` on the `calendar-day` group itself, for
example "journey: 2 days after registration". The journey's first cycle then takes that as its due date.

---

## 2. Status values

### `step_status`: from FHIR `CarePlanActivityStatus`

A `step_instance` row is one occurrence of a step for one patient. FHIR's
[CarePlanActivityStatus](https://hl7.org/fhir/R4/valueset-care-plan-activity-status.html) is the status
list for that. CCE uses two of its codes:

| Enum | FHIR code | FHIR definition |
|---|---|---|
| `NOT_STARTED` | `not-started` | "Care plan activity is planned but no action has yet been taken." |
| `COMPLETED` | `completed` | "Care plan activity has been completed (more or less) as planned." |

`StepStatus.code()` returns the FHIR code. That code is what is sent in messages and used in rules. The
database column and the Java code use the enum name.

The other codes in that list (`cancelled`, `stopped`, `on-hold`, `unknown`, `entered-in-error`) are not
used, because nothing in CCE cancels or pauses a step yet. They can be added later without a database
change.

### `sla_status`: CCE's own

`PENDING`, `OVERDUE`, `MISSED` and `MET` are not FHIR codes. FHIR has no idea of a deadline on a plan
activity. They are kept apart from `step_status` on purpose. That way one row can say both that the
deadline was missed *and* that the event came in later. See
[Architecture Overview §4](architecture-overview.md#4-step-status-and-sla-status).

---

## 3. Action types

Every `PlanDefinition.action` must have one of these as `type.coding[0]`. `validateActionTypes` refuses a
protocol where one doesn't:

| Code | System | Meaning |
|---|---|---|
| `step` | `http://openphc.org/fhir/CodeSystem/action-type` | A clinical step, matched to events by its trigger. It can contain nested steps. |
| `fire-event` | `http://terminology.hl7.org/CodeSystem/action-type` | An intelligence action. What it does is defined by the ActivityDefinition in its `definitionCanonical`. |

`step` uses a CCE code system, because FHIR's own list has nothing like it. FHIR's codes say *how* an
action changes a plan (create, update, remove). They don't say that an action is a clinical step to
track.

### Nested steps are treated as ordinary steps

A step nested inside another step is read as a separate step, with its own action id. Nesting is only a
way to arrange the document. When CCE runs, a nested step is treated exactly like a top-level one: for
matching events, for order, and for deadlines. So action ids must be unique across the whole protocol.
`validateActionIds` checks this.

### Step groups

A `step`-typed action with `groupingBehavior: logical-group` is a **step group**, not a step. It is a set
of steps that belong together clinically, such as one visit.
- A group is never matched to an event, so it has no trigger.
- `extractGroups` returns it as `GroupMetadata`, not among the steps.
- `visual-group` and `sentence-group` only affect how a protocol is displayed. An action with either is
  an ordinary step.

A group that repeats per visit says what starts a new cycle in a CCE extension:

```json
"extension": [{
  "url": "http://openphc.org/fhir/StructureDefinition/cycle-boundary",
  "valueCode": "calendar-day"
}]
```

| `valueCode` | A new cycle starts |
|---|---|
| `calendar-day` | on each clinical day the group has events, in the deployment's time zone |
| `entry-step` | with each event for the group's entry step, unless the current cycle is still waiting for it. The first cycle starts with an event for any of its steps, or when a step outside the group that the entry step follows is done |

An `entry-step` group names its entry step, by action id, in a second extension. It must have exactly
one:

```json
"extension": [
  { "url": "http://openphc.org/fhir/StructureDefinition/cycle-boundary",   "valueCode": "entry-step" },
  { "url": "http://openphc.org/fhir/StructureDefinition/cycle-entry-step", "valueId": "history" }
]
```

FHIR's `Timing.repeat` can only describe repeats on a calendar, such as every 3 months. It can't say "a
new cycle each visit", so CCE uses its own extension.

`validateGroups` refuses:
- any other `cycle-boundary` code;
- `cycle-boundary` on an action that is not a group;
- a `calendar-day` group inside another `calendar-day` group, at any depth;
- an `entry-step` group without exactly one `cycle-entry-step`, or with no `valueId`;
- an entry step that is not a step inside the group (directly or in a nested group), or that follows
  another step inside the group;
- `cycle-entry-step` on any other action.

---

## 4. Triggers

An action's `trigger[]` decides which incoming events match it. There are two kinds, stored in
different ways:

| Kind | How it is handled |
|---|---|
| Has `data[]` | Stored in `trigger_index` by resource type, path, code system and code value. Matched by a lookup. |
| A condition only, no `data[]` | Kept in memory and checked against every incoming event. |

A trigger with **neither** `data[]` nor a condition could never match anything. `validateTriggers`
refuses it when the protocol is loaded, rather than storing an action that can never fire.

Inside a `data[]` entry:
- No `codeFilter` means the resource type alone is enough. "Any Encounter" is a valid trigger.
- A `Coding` with no `code` uses its `display` instead. Some systems send display-only codings, and
  storing an empty code would match nothing.
- A `type` that is not a known FHIR resource name stops the load. Storing it would create an index row
  that no event could ever match.

---

## 5. Time offsets

`relatedAction.offsetDuration` says how long after the earlier step the next step is due. The units are
FHIR's `UnitsOfTime`:

| Unit | Meaning |
|---|---|
| `min`, `h`, `d` | a fixed length of time |
| `wk` | 7 days |
| `mo` | a calendar month. A step due one month after 31 January is due at the end of February, not on 2 March |
| `a` | a calendar year |

With no unit, or no offset at all, the next step is due as soon as the earlier step is done. An
**unknown** unit stops the load, rather than being read as zero. Read as zero, it would make the step due
at once, and the protocol would look as if it worked.

---

## 6. Canonical references

An intelligence action refers to its ActivityDefinition as `url|version`.
[`ActionDefinitionResolver`](library-reference.md#intelligence--actiondefinitionresolver) looks it up.
- A protocol is identified the same way (`ProtocolDefinition.getCanonical()`).
- This identifier is not stored on `protocol_instance`. The foreign key already gives the version the
  patient was enrolled under, because each new version is stored as a new `protocol_definition` row.

A reference without a version is refused. Looking it up by URL alone would mean picking a version
silently. A protocol that relies on how one version of an action behaves would then change without
notice when a new version is published.
