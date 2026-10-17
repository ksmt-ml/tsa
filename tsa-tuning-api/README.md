# TSA tuning API

This module contains application-owned launch interfaces only. It deliberately
contains no profiling, replay, solver, worker-process, persistence, or search
implementation.

The boundaries are:

- `TsaSolverProfileLauncher` starts one bounded TSA analysis and emits terminal
  KSMT solver profiles;
- `TsaCvc5TrialWorkloadResolver` resolves an opaque workload reference;
- `TsaCvc5TrialWorkload` implements the tuner-owned evaluator directly, so TSA
  does not duplicate trial request, observation, status, or cancellation DTOs;
- `ksmt-ml-tuner` starts inference or search and owns provider lifecycle,
  budgets, validation, fallback, telemetry, and the external-worker protocol.

Each trial uses a fresh isolated cvc5 solver and applies a validated
configuration patch before its first assertion. TSA remains authoritative for
workload execution, correctness, and artifact references.

For local cross-repository validation, place `ksmt`, `ksmt-ml-tuner`, and `tsa`
next to each other and run:

```text
./gradlew -PuseLocalTuningContracts=true :tsa-tuning-api:check
```

Published KSMT and tuner artifacts are required before this module can be
merged into a branch that builds without the local composite.
