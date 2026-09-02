# Library Reference

> What `cce-common-util` provides, package by package.

Anything used by more than one service belongs here; anything used by one belongs in that service.
The library is a plain `java-library` — it applies Spring's dependency management but not the Boot
plugin, so it produces a jar rather than an executable archive.

For the tables the entities map to, see [Data Dictionary](data-dictionary.md). For the FHIR
semantics the parser implements, see [FHIR Conformance](fhir-conformance.md).

**Coordinates**: `org.openphc.cce:cce-common-util:2.0.0` · base package `org.openphc.cce.common`

---

## Consuming it

The library carries JPA, Kafka, Micrometer and Spring Web as `api` dependencies, so a service that
depends on it inherits them. Two pieces of wiring are required in the consuming application, because
Spring's component scan alone will not find them:

```java
@SpringBootApplication(scanBasePackages = "org.openphc.cce")
@EntityScan("org.openphc.cce")             // @Entity types are not component-scanned
@EnableJpaRepositories("org.openphc.cce")  // nor are Spring Data repository interfaces
public class SomeServiceApplication { }
```

Widening `scanBasePackages` to `org.openphc.cce` is what makes the shared `@Service` and
`@Component` beans available. Note the consequence: a service picks up **every** bean this library
declares, whether or not it uses it — see [§7](#what-a-consumer-gets-whether-it-asks-or-not).

**Or import what you need by name.** The Collector Service does that instead:

```java
@Configuration
@Import({FhirConfig.class, ClinicalEventTimeExtractor.class, KafkaTopicProperties.class})
public class CommonUtilConfig { }
```

It owns one table and has no business holding the runtime plane's entities and repositories, and a
scan of the whole library would also replace the `ObjectMapper` Spring Boot configures for its HTTP
layer and add a second `GlobalExceptionHandler` beside its own. Two notes if you follow that route:
the imports belong in a scanned `@Configuration` rather than on the application class, or slice tests
such as `@DataJpaTest` inherit them and must satisfy dependencies they have no reason to configure —
a `MeterRegistry`, in this case; and `@ConfigurationProperties` beans still bind normally when
imported by name.

Build wiring is covered in [Developer Setup](developer-setup.md).

---

## 1. `entity` — the shared persistence model

Eleven types mapping ten tables (`TriggerIndexId` is `TriggerIndex`'s composite key).

| Entity | Table |
|---|---|
| `ProtocolDefinition` | `protocol_definition` |
| `ActionDefinition` | `action_definition` |
| `TriggerIndex` + `TriggerIndexId` | `trigger_index` |
| `ProtocolInstance` | `protocol_instance` |
| `StepInstance` | `step_instance` |
| `StepSlaStateTransition` | `step_sla_state_transition` |
| `Deviation` | `deviation` |
| `IntelligenceEventLog` | `intelligence_event_log` |
| `ProtocolInstanceHistory` | `protocol_instance_history` |
| `StepInstanceHistory` | `step_instance_history` |

Identifiers are UUIDv7 via [`UuidV7Generator`](#6-support). Timestamps are stamped by
`@PrePersist` / `@PreUpdate` callbacks that **preserve a caller-supplied value** — services backdate
these to a clinical occurrence time, which must survive the insert rather than being overwritten
with `now()`.

`ProtocolDefinition.getCanonical()` and `ActionDefinition.getCanonical()` both render `url|version`,
the form [`ActionDefinitionResolver`](#intelligence--actiondefinitionresolver) parses.

## 2. `repository` — Spring Data interfaces

`ProtocolInstanceRepository`, `StepInstanceRepository`, `StepSlaStateTransitionRepository`,
`DeviationRepository`, `IntelligenceEventLogRepository`, `ActionDefinitionRepository`,
`ProtocolInstanceHistoryRepository`, `StepInstanceHistoryRepository`.

There is deliberately **no** `ProtocolDefinitionRepository` here. Read and write access to
definitions differ sharply by service — the Protocol Service writes them, the others read a narrow
slice — so each declares the interface it actually needs.

## 3. `fhir` — parsing and evaluation

### `PlanDefinitionParser`

Turns a FHIR PlanDefinition into the flat, normalized form the services work with. Instance methods
parse and validate; the graph operations are `static` because they act on already-extracted
metadata and need no FHIR context.

| Member | Purpose |
|---|---|
| `parse(String)` | JSON → `PlanDefinition`; throws `DataFormatException` on malformed input |
| `extractSteps(PlanDefinition)` | every step as `StepMetadata`, nested sub-steps flattened to peers |
| `buildDependencyGraph(List<StepMetadata>)` | `relatedAction` edges normalized into one directed `DependencyGraph` |
| `classifyRelationship(String)` | a FHIR relationship code → `AFTER` / `BEFORE` / `UNORDERED` |
| `findStep`, `computeAncestors`, `computeMustPredecessorSteps`, `mustStepIds` | graph queries used for ordering checks and backfill |
| `findUnorderedRelationships`, `findDanglingRelatedActions` | load-time diagnostics for edges that establish no ordering |
| `buildTriggerIndexEntries(PlanDefinition, UUID)` | the trigger index rows for a definition, as persistence-agnostic `TriggerIndexEntry` records |
| `extractConditionOnlyTriggers(PlanDefinition)` | triggers with a condition but no `data[]`, evaluated in memory |
| `validateActionIds`, `validateActionTypes`, `validateTriggers` | load-time rejection of definitions that cannot work |

`buildTriggerIndexEntries` returns records rather than `TriggerIndex` entities on purpose: only the
Protocol Service persists them, and it maps the records onto rows itself. The parser stays usable by
services that never write that table.

The direction of `relatedAction` is the subtlest thing here and is documented separately in
[FHIR Conformance §1](fhir-conformance.md#1-relatedaction-direction).

### `ParsedProtocolCache`

The derived form of a definition — flattened steps plus dependency graph — keyed by definition id.
Deriving it costs a FHIR parse and a full tree flatten, and it is needed repeatedly for the same
protocol: once per inbound event, again on every completion, again per intelligence evaluation.

```java
ParsedProtocol get(UUID protocolDefinitionId, Supplier<String> definitionJson)
void evict(UUID protocolDefinitionId)
```

The JSON arrives as a **supplier** so a cache hit never pays for it. Callers hold a JPA entity whose
JSONB column costs a full serialization to render as a string; on the hot path that would dominate
the lookup the cache exists to avoid.

Bounded by `cce.protocol.parsed-cache-size` (default 256). At the limit the cache is cleared
outright rather than evicting one entry — `ConcurrentHashMap` forbids a mapping function from
modifying the map it is computing on. There is no local write to invalidate on, so consumers that
need freshness poll for definitional changes and call `evict`; staleness is bounded by that poll
interval rather than by process lifetime.

### `ClinicalEventTimeExtractor`

The clinical occurrence time from a FHIR payload — when the act happened, as against the CloudEvents
envelope `time` (the emitter's transmission clock) or the moment a service processed the event.

FHIR has no single "when did this happen" field: each resource type carries its own, and most are
polymorphic choice types (`effective[x]`, `performed[x]`, `occurrence[x]`), so the class holds a
resource-type → ordered-candidate-field table and takes the first that parses. A `Period`'s `end`
bound says when something finished rather than when the act occurred, so it is the last resort in
every list — after that same Period's `start`. Parsing is lenient (HAPI `DateTimeType`, so `2026` and
`2026-03` resolve), and an unmapped type, absent field or unparseable value returns null with a
counter incremented, leaving the caller to fall back to the envelope time.

It is here because two services need the same answer from the same payload: the Collector Service
stamps `inbound_event_log.event_time` with it, and the Matcher Service bases a completed step's
`completed_at` — and therefore its SLA verdict and every dependent step's due date — on it. They held
separate copies until 2.0.0, and the copies had drifted: for an `Encounter` carrying both bounds, the
collector read `period.end` while the matcher read `period.start`, so the audit trail and the SLA
clock disagreed about when the visit happened. The two tables were reconciled by hand first, which is
why the move itself changed no behaviour — holding one copy is what stops them drifting again.

### `FhirExpressionEvaluator`

Evaluates trigger and intelligence conditions. An unsupported expression language raises
`UnsupportedExpressionLanguageException`, which [§5](#5-exception) maps to `422`.

## 4. `sla`, `deviation`, `history`, `intelligence` — shared behaviour

One package per functional area rather than a single `service` bag, so that what a class is for is
visible from where it lives — the same reason `fhir` and `kafka` are their own packages.

### `intelligence` — `IntelligenceActionEvaluator`

Decides whether a step's intelligence actions fire, records the attempt on `intelligence_event_log`,
and publishes the trigger. Driven by both the Matcher Service (on completion) and the Compliance
Service (on deviation), which is why it is here — see
[Architecture Overview §3](architecture-overview.md#3-the-intelligence-trigger).

### `intelligence` — `ActionDefinitionResolver`

Resolves a `definitionCanonical` (`url|version`) to an `ActionDefinition`. Splits on the **last**
separator, so a URL containing a pipe still resolves. A reference with no version is rejected rather
than guessed at — resolving on URL alone would silently pick a version.

Read-only by design: creating and retiring these rows belongs to the Protocol Service. Its only caller
is the evaluator above, so it reaches both services that drive that evaluator rather than being called
by either directly.

### `deviation` — `DeviationRecorder`

`recordDeviation` inserts a `deviation` row and reports whether the row was new, so the caller can
avoid re-triggering intelligence for a deviation already recorded. An empty metadata map is stored as
null rather than as an empty JSON object, so the absence of detail reads the same however it was
recorded.

### `sla` — `SlaThresholdReader`

Reads a step's `dueDate` / `missedDate` back from its `step_sla_state_transition` rows.

```java
record SlaThresholds(OffsetDateTime dueDate, OffsetDateTime missedDate) { }
SlaThresholds getThresholds(UUID stepInstanceId)
```

Read-only and `Propagation.SUPPORTS`, so it can be called inside or outside a transaction.

**It is not compliance-only, despite reading SLA rows.** The Matcher Service calls it when scheduling
a dependent step, to anchor an after-start offset to the step it follows, and the shared intelligence
evaluator calls it to build the `dueDate` and `daysOverdue` of a rule context — which both services
drive. Neither should re-derive the deadlines from the definition: the schedule already exists as rows,
and recomputing risks disagreeing with what was scheduled. Only the Matcher Service *writes* the
schedule, and that write path deliberately stays in that service — nothing in this library can invent a
deadline.

### `history` — `StateTransitionHistoryWriter`

Appends a row to `protocol_instance_history` / `step_instance_history` for each state change. Runs
`MANDATORY`, inside the caller's transaction, so the history row commits with the change it records
and there is no window where one exists without the other.

Lives here rather than in one service because **both** write it: Matcher records enrolment, step
creation and completion; the Compliance Service records each `sla_status` it applies. Append-only is
what makes two writers safe — they insert disjoint rows and neither updates the other's. Before
Compliance wrote here, every time-driven transition was missing from the table, so a step that went
overdue and was never completed had one history row instead of three.

`step_instance_history.sla_status` is nullable, mirroring the column it copies.

## 5. `exception`

`GlobalExceptionHandler` is a `@ControllerAdvice` shared by every service, so this mapping is a
contract of the library rather than of any one controller:

| Exception | Status |
|---|---|
| `EntityNotFoundException` | `404` |
| `IllegalArgumentException`, `MethodArgumentNotValidException` | `400` |
| `IllegalStateException` | `409` |
| `UnsupportedExpressionLanguageException`, `DataFormatException` | `422` |
| anything else | `500` |

Validation failures list every offending field rather than only the first. The `500` body is a fixed
string — the exception detail goes to the log, not to the caller. Every response carries the
`correlationId` from MDC when one is in scope, and omits the field entirely when not.

`ErrorResponse` is the body: `status`, `error`, `message`, `timestamp`, `correlationId`.

## 6. `support`

`UuidV7Generator` — RFC 9562 v7 identifiers, time-ordered so they cluster on insert rather than
scattering across the index. Monotonic within a millisecond via a 12-bit counter; on counter
overflow it borrows into the timestamp, and a backwards wall clock holds the last timestamp instead
of emitting a decreasing id.

## 7. `event`, `kafka`, `config`

`CloudEventMessage` is the CloudEvents v1.0 envelope with the CCE extension attributes;
`IntelligenceTriggerEvent` is the payload published to `cce.intelligence.triggers`.

`IntelligenceTriggerProducer` publishes it. `publishAndConfirm` waits for the broker acknowledgement
and returns whether it arrived, so an unacknowledged trigger can be recorded unpublished rather than
assumed delivered. An interrupt is re-asserted on the thread rather than swallowed.
`KafkaTopicProperties` binds `cce.kafka.topics.*`.

`AppConfig` provides the shared `ObjectMapper` — `JavaTimeModule` registered and
`WRITE_DATES_AS_TIMESTAMPS` disabled, so timestamps serialize as ISO-8601 across every service.
`FhirConfig` provides the R4 `FhirContext`. `KafkaRetryProperties` binds `cce.kafka.retry.*`
(`max-attempts` 3, `backoff-interval-ms` 1000).

### What a consumer gets whether it asks or not

Because consumers widen the component scan to `org.openphc.cce`, they instantiate every bean here.
The practical effect is that a service may hold beans it never calls, and any configuration property
those beans read is live in that service's configuration even when it has no purpose there. Worth
knowing when auditing a service's YAML: presence of a key does not prove that service uses it.

---

## Configuration properties

| Property | Default | Read by |
|---|---|---|
| `cce.protocol.parsed-cache-size` | `256` | `ParsedProtocolCache` |
| `cce.kafka.topics.inbound-events` | — | Matcher's `InboundEventConsumer` and its topic declarations; the Collector Service's producer and topic creation |
| `cce.kafka.topics.intelligence-triggers` | — | `IntelligenceTriggerProducer` |
| `cce.kafka.topics.default-partitions` | `25` | consuming services' topic declarations |
| `cce.kafka.retry.max-attempts` | `3` | consuming services' error handlers |
| `cce.kafka.retry.backoff-interval-ms` | `1000` | consuming services' error handlers |
| `cce.intelligence.publish-confirm-timeout-ms` | `5000` | `IntelligenceTriggerProducer` |
