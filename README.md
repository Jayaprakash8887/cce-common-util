# cce-common-util

Shared library for the CCE services: the persistence model, FHIR parsing, and the behaviour that
more than one service needs.

**Coordinates**: `org.openphc.cce:cce-common-util:2.0.0` · Java 21 · Spring Boot BOM 3.4.2

This is a plain `java-library` — it applies Spring's dependency management but not the Boot plugin,
so it produces a jar and no runnable application.

## Documentation

| Document | Contents |
|---|---|
| [Architecture Overview](docs/architecture-overview.md) | How the four repositories divide the work, the contracts between them, and the deployment order |
| [Data Dictionary](docs/data-dictionary.md) | Canonical schema for the nine shared tables — columns, indexes, enums, JSONB shapes, and table ownership |
| [Library Reference](docs/library-reference.md) | What this library provides, package by package, and how to wire it into a service |
| [FHIR Conformance](docs/fhir-conformance.md) | `relatedAction` direction, status vocabularies, action types, triggers, and timing offsets |
| [Developer Setup](docs/developer-setup.md) | Building, the coverage gate, and what belongs in this library |

Start with the [Architecture Overview](docs/architecture-overview.md) if you are new to the system —
it is the shared context the service repositories build on and do not restate. Four consume this
library: the Protocol, Matcher and Compliance services, which map its entities, and the Collector
Service, which imports three beans by name and maps none.

## What is here

```
org.openphc.cce.common
├── entity/      10 shared tables (+ one composite key type)
├── repository/  Spring Data interfaces for them
├── fhir/        PlanDefinitionParser, ParsedProtocolCache, FhirExpressionEvaluator,
│                ClinicalEventTimeExtractor, TriggerPath
├── sla/         SlaThresholdReader — a step's deadlines, read back
├── deviation/   DeviationRecorder
├── history/     StateTransitionHistoryWriter
├── intelligence/ IntelligenceActionEvaluator, ActionDefinitionResolver
├── enums/       the shared vocabularies (StepStatus, SlaStatus, DeviationType, …)
├── event/       CloudEvents envelope and the intelligence trigger payload
├── kafka/       IntelligenceTriggerProducer, topic properties
├── exception/   GlobalExceptionHandler and its error body
├── config/      shared ObjectMapper, FhirContext, retry properties
└── support/     UUIDv7 generator
```

## Build

```bash
./gradlew build   # compile, test, coverage gate (0.98 instruction coverage)
```

Consumers include it as a composite build (`includeBuild '../cce-common-util'`), so local changes are
picked up without publishing — see [Developer Setup](docs/developer-setup.md).
