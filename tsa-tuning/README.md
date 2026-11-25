# tsa-tuning

Production TSA-side implementation of the cvc5 tuning boundary declared in
[`tsa-tuning-api`](../tsa-tuning-api/README.md). It runs TSA analyses in-process
and evaluates captured solver workloads; it never invokes TSA as a subprocess.

The module depends on `tsa-core`, `tsa-tuning-api`, and the sibling `ksmt` and
`ksmt-ml-tuner` checkouts. See [`CONTRIBUTING.md`](../CONTRIBUTING.md) for the
required repository layout and JDK version.

## Workload layout

A workload is one independent analysis task: a contract, a method, its input
parameters, and a TSA seed. It is described by a versioned
[`tsa-analysis-workload-v0`](schemas/tsa-analysis-workload-v0.schema.json)
document and a directory of captured solver profiles:

```text
<workloadsRoot>/
  <workloadId>/
    <revision>/
      workload.json                    # tsa-analysis-workload-v0 descriptor
      tsa-solver-profile-*.json        # captured KSMT profiles
```

`profileStoreRoot` of the launcher and `profilesRoot` of the workload resolver
are the same root, so a captured workload is immediately resolvable.

## Capture

`TsaSolverProfileLauncherImpl` implements `TsaSolverProfileLauncher`:

- resolves the descriptor through `TsaAnalysisWorkloadCatalog` and fails closed
  when it is missing or invalid;
- runs the analysis with KSMT profiling enabled and emits every terminal
  `KSolverProfile` to the sink, in addition to the profile JSON artifacts;
- splits the requested timeout between the remaining methods, because TSA
  applies its own timeout to every method independently;
- stops at a method boundary on cancellation or deadline and reports the
  captured and failed profile counts.

`FileSystemTsaAnalysisWorkloadCatalog` reads descriptors from
`<workloadsRoot>/<workloadId>/<revision>/workload.json`.

## Trial evaluation

`TsaCvc5ProfileReplayWorkloadResolver` resolves a `TsaWorkloadReference` into a
`TsaCvc5ProfileReplayWorkload`, whose instances are the replayable cvc5 backend
profiles captured for that workload revision. Every trial replays exactly one
profile against a fresh, isolated cvc5 solver with the validated patch applied
before its first assertion.

Replay results map to `Cvc5TrialObservation` as follows:

| Replay result | Observation |
| --- | --- |
| completed, all recorded checks matched | `SUCCESS`, `CORRECT`, finite cost |
| a recorded SAT/UNSAT status disagreed | `WRONG_ANSWER`, `INCORRECT`, no cost |
| a recorded status stayed unresolved | `TIMEOUT`, `censored`, no cost |
| replay outlived the trial budget | `TIMEOUT`, `censored`, no cost |
| replay did not complete | `INFRASTRUCTURE_FAILURE` |

Only the first row is rankable. Hard termination of a stuck evaluator belongs to
the tuner host, which owns process isolation.

## Scope

`tact` sources are not described by the workload schema yet, because they need a
compiled Tact project rather than a single contract file. Capturing a Tact
workload therefore has to go through a FunC/BoC artifact for now.
