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

Each trial receives a `Cvc5ValidatedConfigurationPatch`, uses a fresh isolated
cvc5 solver, and applies that configuration before its first assertion. TSA
remains authoritative for workload execution, correctness, and artifact
references.

For local cross-repository validation, place `ksmt`, `ksmt-ml-tuner`, and `tsa`
next to each other and run:

```text
../ksmt/gradlew -p . -PuseLocalTuningContracts=true :tsa-tuning-api:check
```

No external artifact publication is assumed at this stage. The module is
included only when `useLocalTuningContracts=true`; the ordinary TSA build stays
independent of the sibling repositories. Internal development and CI that
validate these contracts must check out all three repositories side by side
and enable the flag explicitly.
