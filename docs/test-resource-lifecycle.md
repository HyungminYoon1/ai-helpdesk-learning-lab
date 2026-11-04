# Class-owned PostgreSQL test resources

Status: 2026-11-04 focused diagnosis, class-bound cleanup and full local verification completed. This changes test infrastructure, not application shutdown, business transactions or deployed resources.

## Evidence and selected repair

Two earlier full-suite dumps show System.exit waiting for SpringApplicationShutdownHook, which is waiting in HikariPool.shutdown/awaitTermination. They also contain surviving pool housekeeping/connection-adder threads. These snapshots identify the wait path, not every possible cause of a future shutdown delay.

The affected pattern combines a class-owned static PostgreSQL @Container with @SpringBootTest. The JUnit extension stops its container at the class boundary, while Spring can cache that class's context and data source until later eviction/JVM exit. The three-class baseline passed 60 tests; a new after-class verifier then exposed three still-active contexts/data sources. The red run reported 63 entries with three class-callback errors, not three previously failing business assertions.

The repair uses @DirtiesContext(classMode = AFTER_CLASS). It retains context reuse within the class and closes/removes that context at class completion. It does not raise Surefire's exit limit, suppress the warning, change Hikari configuration or share databases between classes. After the focused checks on AiRateLimitRetryIntegrationTest, AssignmentQueueConfirmationHttpIntegrationTest and CustomerMessageConfirmationHttpIntegrationTest, the same annotation was applied to all 21 matching default integration-test classes.

| Context and options | Decision and rationale | Files and follow-up |
|---|---|---|
| Class-owned DB and cached client have different lifetimes. Increase exit timeout, share a global DB, migrate to Spring-managed container beans, or close the class context explicitly | Choose class-bound context cleanup as the small repair to the existing isolated fixtures. A longer timeout alone does not align resource lifetimes; a global DB changes isolation; container beans offer stronger dependency shutdown ordering but require a broader fixture migration | Affected test-class annotations and the test-only verifier. Application/Schema/credentials/Provider policy unchanged. Do not claim this annotation guarantees the order of every JUnit/container callback |
| Passing test methods do not prove cleanup. Inspect annotations only or observe actual resources after lifecycle callbacks | Three representative classes run a low-order TestExecutionListener after the default dirty-context listener. Retain object references before execution and check inactive context, closed HikariDataSource and zero live platform threads with that pool's generated prefix | ClassOwnedContextCleanupVerifier and explicit MERGE_WITH_DEFAULTS registrations. No global listener, context reload during verification, thread kill or secret logging. The bounded two-second thread-settle check is separate from Surefire's unchanged exit limit |

## Focused result

With the repair, all 60 cases passed with zero failures/errors/skips. All three cleanup markers passed. Hikari shutdown-complete messages appeared before the next class started; the prior baseline had not closed those contexts at that boundary. The probe counts only the selected pool's named platform threads in the test JVM, not arbitrary child processes or all OS/database resources. It is an actual lifecycle check, not an assertion that an annotation exists.

```powershell
.\mvnw.cmd -B -ntp '-Dtest=AiRateLimitRetryIntegrationTest,AssignmentQueueConfirmationHttpIntegrationTest,CustomerMessageConfirmationHttpIntegrationTest' '-Dsurefire.runOrder=alphabetical' '-Dlogging.level.com.zaxxer.hikari.HikariDataSource=INFO' test
```

Ignored logs: target/week12-jvm-cleanup-before.log, target/week12-jvm-cleanup-red.log and target/week12-jvm-cleanup-focused-after.log. The extra INFO setting is command-scoped diagnostic output from HikariDataSource only; it is not a committed runtime logging change. No credentials, connection properties or environment dumps are printed.

## Full local verification

The unchanged standard command `.\mvnw.cmd -B -ntp verify` passed 1,173 Surefire tests and two Failsafe packaged tests, with zero failures/errors/skips. It used the normal test logging configuration. All three explicit cleanup probes passed. The log contained zero Surefire exit-deadline messages, no new dump/dumpstream files appeared relative to the pre-run inventory, and the post-run process check found zero lab-owned Surefire fork JVMs. This is one observed full run after the repair, not a promise about every future shutdown.

The 21 classes combine @SpringBootTest, class-level @Testcontainers and a static PostgreSQL @Container. Their lifecycle declarations were inventoried; this was not a new audit of every business assertion. Only the three named classes register the explicit resource verifier. Manual-context/restart tests, plain container tests and opt-in Browser/live experiments retain their own lifecycles. The normal verify command does not run those opt-in experiments. Existing Node/Lint/Browser results were not rerun or added to the Java count for this test-infrastructure change.

Ignored evidence: target/week12-jvm-cleanup-full-verify.log and target/week12-jvm-cleanup-full-evidence.json. Old dumps are retained. The application code, migrations, POM, test exit limit and deployed resources are unchanged. Remote CI and production shutdown remain separate verification scopes.

## Primary references and limits

- [Surefire 3.5.5 shutdown](https://maven.apache.org/surefire-archives/surefire-3.5.5/maven-surefire-plugin/examples/shutdown.html): test completion, shutdown hooks and the default exit grace are different stages.
- [Spring context caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html) and [DirtiesContext](https://docs.spring.io/spring-framework/reference/testing/annotations/integration-spring/annotation-dirtiescontext.html): cached contexts can outlive a class; explicit dirty-context semantics remove and close them.
- [Spring Boot container lifecycle](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html): Spring-managed container beans have a different dependency-aware lifecycle from JUnit-managed containers. That alternative is not implemented here.
- [HikariCP 7.0.2 shutdown/retry source](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/HikariPool.java): pool close waits for its executors; failed connection creation has a bounded backoff.

The local POM remains on Spring Boot 4.1.1/Surefire 3.5.5; the live Spring reference pages observed during this review show newer patch documentation. No dependency version was changed. The focused resource checks and the full-run exit evidence answer different questions; neither establishes remote CI or production shutdown behavior.
