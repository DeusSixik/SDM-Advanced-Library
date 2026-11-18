# Frozen ECS comparison baseline

This directory contains the exact ECS implementation captured before the cleanup
and optimization task on 2026-11-17, after the public API package split.

- `ecs-before-cleanup-1.20.1.jar`: compiled Java 17 ECS classes for Minecraft 1.20.1.
- `ecs-before-cleanup-sources.zip`: corresponding workspace sources, including the
  pre-existing uncommitted ECS work. This is not a reconstruction from Git HEAD.
- `manifest.json`: SHA-256 checksums of the frozen binary and source artifacts.
- `LICENSE.txt` / `NOTICE.txt`: ECS upstream attribution and licensing.

The baseline is loaded only by `EcsCleanupBenchmark`. Historical workload sources
in `src/jmhBaseline/java` compile against the frozen JAR; the current equivalent
in `src/jmh/java` uses the renamed ECS API. Setup verifies matching observed state
and serialization checksums before measurement.
Neither reflection nor class loading runs inside the measured operation.

Do not regenerate or overwrite these artifacts when running the comparison.
They belong to the JMH source set and must never be added to a mod runtime JAR.
