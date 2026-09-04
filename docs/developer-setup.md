# Developer Setup

> Building, testing and consuming this library.

## Prerequisites

| Requirement | Version |
|---|---|
| JDK | 21 (Gradle toolchain) |
| Gradle | wrapper included — `./gradlew` |
| Spring Boot BOM | 3.4.2 (dependency management only; the Boot plugin is not applied) |
| HAPI FHIR | 7.6.0 |

No database, broker or other running service is needed. Every test here is a unit test.

## Build

```bash
./gradlew build          # compile, test, and the coverage gate
./gradlew test           # tests only
./gradlew jacocoTestReport
```

The coverage report lands at `build/reports/jacoco/test/html/index.html`.

## Consuming it from a service

All four consumers wire it in as a **composite build**, so a change here is picked up by the next
service build without a publish step:

```groovy
// settings.gradle
rootProject.name = 'cce-some-service'
includeBuild '../cce-common-util'
```

```groovy
// build.gradle — no version; the composite substitutes the included build
implementation 'org.openphc.cce:cce-common-util'
```

This assumes the repositories are checked out as siblings. The dependency resolves to the local build
output, which means editing a shared entity and rebuilding a service exercises the change immediately
— and equally, a compile break here breaks every service build. Run `./gradlew build` in each
consumer after changing anything in `entity`, `repository` or a service signature.

For a released artifact instead of a composite build, publish `org.openphc.cce:cce-common-util:2.0.0`
and drop the `includeBuild` line.

The application-side annotations a consumer needs are in
[Library Reference](library-reference.md#consuming-it).

## Coverage gate

`check` depends on `jacocoTestCoverageVerification`, set at **0.98** instruction coverage with no
class exclusions. The floor sits just under the measured level, so an uncovered addition fails the
build while an ordinary refactor does not.

The gate is stricter here than in the services on purpose: this is the code every service compiles
against, so an uncovered change here is an uncovered change everywhere.

## What belongs in this library

Code used by **more than one** service. Code used by one belongs in that service, even if it feels
generic — the cost of pulling it back out later is much lower than the cost of a shared abstraction
that only ever had one caller.

Two consequences worth keeping in mind when adding to it:

- The Protocol, Matcher and Step SLA services instantiate every bean declared here, because they
  widen their component scan to `org.openphc.cce`. A new `@Service` appears in all three whether they
  use it or not.
- A new `@ConfigurationProperties` or `@Value` default becomes live configuration in all three.
- The Collector Service is the exception, and the reason the distinction matters: it imports
  `FhirConfig`, `ClinicalEventTimeExtractor` and `KafkaTopicProperties` by name in a
  `CommonUtilConfig`, so nothing else here reaches it. It owns one table and should not hold the
  runtime plane's entities and repositories — and a full scan would replace the `ObjectMapper` Spring
  Boot configures for its HTTP layer and add a second `GlobalExceptionHandler` beside its own. Adding
  a bean here does not reach that service; adding one it needs means adding it to that import list.

## Adding a shared entity

1. Add the `@Entity` here, with `@PrePersist` / `@PreUpdate` callbacks that preserve a
   caller-supplied timestamp rather than overwriting it.
2. Add the migration to whichever service **owns** that table's DDL — see
   [Data Dictionary §3](data-dictionary.md#3-ownership). Never add DDL to two services.
3. Rebuild the consumers. The Step SLA Service runs `ddl-auto: validate` and will refuse to start
   if its mapping and the schema disagree, which is the intended early warning.

## Testing conventions

Tests assert behaviour through public API. Where a decision is encoded in a private field of a
framework class — a retry budget, a destination resolver — it is driven through the public entry
point and observed at the boundary rather than read by reflection, so the test survives a library
upgrade.

Two areas carry deliberately white-box tests because the branch is otherwise unreachable: the
UUIDv7 counter-overflow path, and the parsed-protocol cache's size bound.
