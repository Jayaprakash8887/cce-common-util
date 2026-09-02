# FHIR Conformance

> Where the CCE model meets FHIR R4, and where it deliberately does not.

Protocols are authored as FHIR R4 **PlanDefinition** resources and intelligence actions as
**ActivityDefinition** resources. This document records how those are interpreted. The parser that
implements it is [`PlanDefinitionParser`](library-reference.md#plandefinitionparser); the columns the
interpretation lands in are in [Data Dictionary](data-dictionary.md).

---

## 1. `relatedAction` direction

`PlanDefinition.action.relatedAction` is a **relative** pointer. Its `relationship` describes the
*declaring* action's position with respect to the *referenced* one — so which of the two comes first
depends on which family the relationship code belongs to. This is the single easiest thing to get
backwards, and getting it backwards inverts every dependency in a protocol.

### `after-*` — the referenced action is the prerequisite

```json
{ "id": "lab-results",
  "relatedAction": [{ "actionId": "lab-order",
                      "relationship": "after-end",
                      "offsetDuration": { "value": 3, "unit": "d" } }] }
```

Reads: *lab-results happens 3 days after lab-order ends.* So `lab-order` is the prerequisite, and the
edge belongs to `lab-results`. A step's own `relatedAction` list names **what it waits on**, not what
waits on it.

An **absent** `relationship` is treated as `after-end` — the overwhelmingly common intent in authored
protocols.

### `before-*` — the declaring action is the prerequisite

```json
{ "id": "visit-encounter",
  "relatedAction": [{ "actionId": "vitals-recording", "relationship": "before" }] }
```

Reads: *visit-encounter happens before vitals-recording.* The same ordering as the `after-*` form,
stated from the other end: here the **declaring** action is the prerequisite. Both families are
honoured, and `buildDependencyGraph` normalizes `before-*` to the equivalent `after-end` edge so
downstream code sees one direction only.

Authors do use both forms in the same document, so a reader that handles only one family will sever
a chain at whichever edge is written the other way round.

### `concurrent-*` — no ordering at all

`concurrent`, `concurrent-with-start` and `concurrent-with-end` say the two actions happen together,
which implies no ordering. Such an edge drives neither progressive instantiation nor order checking.
Rather than silently ignoring it, `findUnorderedRelationships` reports it at load time, so a protocol
that depended on it being an ordering constraint surfaces immediately.

### Classification

| `relationship` | Direction | Prerequisite |
|---|---|---|
| absent, `after`, `after-start`, `after-end` | `AFTER` | the referenced action |
| `before`, `before-start`, `before-end` | `BEFORE` | the declaring action |
| `concurrent`, `concurrent-with-start`, `concurrent-with-end`, anything else | `UNORDERED` | neither |

A `relatedAction` naming an `actionId` that no action declares is reported by
`findDanglingRelatedActions`. Like `concurrent-*`, it is a warning rather than a rejection: both
shapes were accepted before, so failing the load would break an upstream publisher.

---

## 2. Status vocabularies

### `step_status` — FHIR `CarePlanActivityStatus`

`step_instance` records an *occurrence* of a plan's activity, which is what
[CarePlanActivityStatus](https://hl7.org/fhir/R4/valueset-care-plan-activity-status.html) governs.
Two of its codes are used:

| Enum | FHIR code | FHIR definition |
|---|---|---|
| `NOT_STARTED` | `not-started` | "Care plan activity is planned but no action has yet been taken." |
| `COMPLETED` | `completed` | "Care plan activity has been completed (more or less) as planned." |

`StepStatus.code()` returns the FHIR code — that is what goes on the wire and into rule contexts. The
database column and Java code use the enum name.

The value set's remaining codes (`cancelled`, `stopped`, `on-hold`, `unknown`,
`entered-in-error`) are not used: nothing in the current model cancels or suspends a step. They are
available without a schema change if that becomes a requirement.

### `sla_status` — CCE-specific

`PENDING`, `OVERDUE`, `MISSED`, `MET` have no FHIR equivalent, because FHIR has no concept of a
service-level agreement over a plan activity. Keeping them separate from `step_status` is what allows
a row to state that a deadline was missed *and* that the event eventually arrived — see
[Architecture Overview §4](architecture-overview.md#4-step-status-and-sla-status).

---

## 3. Action types

Every `PlanDefinition.action` must declare one of these as `type.coding[0]`, and
`validateActionTypes` rejects a definition where one does not:

| Code | System | Meaning |
|---|---|---|
| `step` | `http://openphc.org/fhir/CodeSystem/action-type` | A trigger-matched clinical step. May contain nested sub-steps. |
| `fire-event` | `http://terminology.hl7.org/CodeSystem/action-type` | An intelligence action, resolved through `definitionCanonical`. |

`step` uses a CCE code system because FHIR's own action-type value set has no equivalent concept —
its codes describe *how* an action relates to a plan (create, update, remove), not that the action is
a trackable clinical step.

### Nested actions are flattened

Sub-steps are extracted as peers of their parent, each keeping its own action id. The nesting in the
source document expresses authoring structure, not runtime hierarchy, and every runtime concern —
trigger matching, ordering, SLA scheduling — treats a sub-step exactly like a top-level step. Action
ids must therefore be unique across the whole document, which `validateActionIds` enforces.

---

## 4. Triggers

An action's `trigger[]` decides which inbound events match it. Two shapes are supported and they are
indexed differently:

| Shape | Handling |
|---|---|
| Has `data[]` | Indexed structurally in `trigger_index` — resource type, path, code system, code value. Matched by lookup. |
| Condition only, no `data[]` | Held in memory and evaluated against every inbound event. |

A trigger with **neither** `data[]` nor a condition matches nothing and is rejected at load time by
`validateTriggers`, rather than being stored as an action that can never fire.

Within a `data[]` requirement:

- A missing `codeFilter` indexes on resource type alone — "any Encounter" is a legitimate trigger.
- A `Coding` with no `code` falls back to its `display`. Publishers do send display-only codings, and
  indexing an empty code would register something that matches nothing.
- A `type` that is not a known FHIR resource name fails the load. Storing it would create an index
  row no inbound event could ever match.

---

## 5. Timing offsets

`relatedAction.offsetDuration` positions a dependent step relative to its prerequisite. Units follow
the FHIR `UnitsOfTime` value set:

| Unit | Interpretation |
|---|---|
| `min`, `h`, `d` | fixed-length durations |
| `wk` | 7 days |
| `mo` | calendar month — a step due one month after 31 January falls at the end of February, not 2 March |
| `a` | calendar year |

An offset with no unit, or no offset at all, makes the step due as soon as its prerequisite
completes. An **unrecognized** unit raises rather than defaulting to zero: treating it as zero would
schedule the step immediately and look like a working protocol.

---

## 6. Canonical references

`ActivityDefinition` resources are referenced from intelligence actions as `url|version`, resolved by
[`ActionDefinitionResolver`](library-reference.md#intelligence--actiondefinitionresolver). The same form identifies
a protocol (`ProtocolDefinition.getCanonical()`). It is not stored on `protocol_instance`: reaching it
by foreign key gives the version the patient was enrolled under, because a new version lands as a new
`protocol_definition` row rather than mutating one.

A reference without a version is rejected. Resolving on URL alone would mean silently picking a
version, and a protocol pinned to an action definition's behaviour would change underneath it on the
next publish.
