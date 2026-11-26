# tsa-tuning

Opt-in TSA-side implementation of the cvc5 tuning boundary declared in
[`tsa-tuning-api`](../tsa-tuning-api/README.md). It runs TSA analyses in-process
and evaluates captured solver workloads; it never invokes TSA as a subprocess.

The module depends on `tsa-core`, `tsa-tuning-api`, and the sibling `ksmt` and
`ksmt-ml-tuner` checkouts. See [`CONTRIBUTING.md`](../CONTRIBUTING.md) for the
required repository layout and JDK version. Use
`../ksmt/gradlew -p . -PuseLocalTuningContracts=true` for the commands on this page.
The opt-in CLI uses cvc5 when given `--cvc5-tuning` or `--solver-profile-dir`.

## ML team quickstart

TSA is the measuring instrument of the loop. It captures a real analysis into
replayable cvc5 workloads, evaluates any candidate configuration against them with
a correctness oracle, and applies an accepted configuration to a real analysis.

### 1. Describe a workload

A workload is one bounded analysis: a contract, a target, its limits, and a TSA
seed. It is a versioned `tsa-analysis-workload-v0` document stored next to the
profiles that capture will produce:

```text
<workloadsRoot>/
  <workloadId>/
    <revision>/
      workload.json                    # the descriptor you author
      tsa-solver-profile-*.json        # the profiles capture writes
```

Start from [`examples/workload.json`](examples/workload.json):

```json
{
  "schemaVersion": "tsa-analysis-workload-v0",
  "workloadId": "integer-underflow-receivers",
  "revision": "r1",
  "sourceKind": "boc",
  "sourcePath": "tsa-test/src/test/resources/demo/boc/integer-underflow.boc",
  "targetKind": "receivers",
  "methodTimeoutSeconds": 60,
  "solverTimeoutSeconds": 5,
  "iterationLimit": 6,
  "maxRecursionDepth": 5
}
```

`sourcePath` is resolved against the process working directory. A revision is
immutable: capture refuses to write into a revision that already holds profiles,
so a second capture needs a new revision.

### 2. Capture the workload

```kotlin
val root = Paths.get("build/workloads")
val reference = TsaWorkloadReference(workloadId = "integer-underflow-receivers", revision = "r1")

val launch =
    TsaSolverProfileLauncherImpl(
        catalog = FileSystemTsaAnalysisWorkloadCatalog(root),
        profileStoreRoot = root,
    ).launch(
        request =
            TsaSolverProfileLaunchRequest(
                runId = "capture-1",
                workload = reference,
                solver = KSolverDescriptor("cvc5"),
            ),
        sink = TsaSolverProfileSink { profile -> captured += profile },
        cancellation = TsaLaunchCancellation.NONE,
    )

check(launch.status == TsaSolverProfileLaunchStatus.COMPLETED) {
    "${launch.failureType}: ${launch.failureMessage}"
}
```

`launch.capturedProfiles` counts only complete, replayable profiles. To run a
capture without writing any code, use the
[end-to-end harness](#end-to-end-mvp-harness):

```bash
../ksmt/gradlew -p . -PuseLocalTuningContracts=true :tsa-tuning:runTuningMvp \
  -Pcontract=tsa-test/src/test/resources/demo/boc/integer-underflow.boc \
  -Pcandidates=6
```

### 3. Evaluate candidate configurations

```kotlin
val workload =
    TsaCvc5ProfileReplayWorkloadResolver(
        profilesRoot = root,
        parameterSpaceId = parameterSpace.parameterSpaceId,
    ).resolve(reference)

// TsaCvc5TrialWorkload is a Cvc5TrialEvaluator, so it is passed to the tuner directly.
val observation = workload.evaluate(request, cancellationToken)
```

Every trial replays exactly one captured profile against a fresh, isolated cvc5
solver, with the validated configuration applied before its first assertion.
[Trial evaluation](#trial-evaluation) maps a replay result to a
`Cvc5TrialObservation`; only `SUCCESS` with `CORRECT` is rankable.

`TsaSolverProfileLauncherImpl.profileStoreRoot` and
`TsaCvc5ProfileReplayWorkloadResolver.profilesRoot` are the same root, so a
freshly captured workload is immediately resolvable.

### 4. Apply an accepted configuration to a real analysis

An application can load a
[`tsa-cvc5-configuration-v0`](../tsa-core/schemas/tsa-cvc5-configuration-v0.schema.json)
document and pass an approved tuner parameter space. Validation binds the patch
to the runtime cvc5 version and backend role before it can configure a solver.

```kotlin
val document = TsaCvc5ConfigurationDocument.load(configPath)
val configuration = document.validate(approvedParameterSpace)
val tunedOptions = options.copy(
    solverFactory = TvmCvc5SolverFactory(configuration = configuration),
)
```

The factory configures a fresh solver for the selected role before its first
assertion. The other cvc5 backend uses its default settings. Capture records the
applied options so replay can reproduce the initial configuration.

The tuning CLI builds and captures default cvc5 workloads and can replay
offline candidates. Applying a document to full analysis is a JVM integration
point until there is a loader for the approved parameter space.

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
- refuses to capture into a workload revision that already holds profiles,
  because a revision is immutable and mixing runs would change the measured
  workload;
- runs the analysis with KSMT profiling enabled and emits every terminal
  `KSolverProfile` to the sink, in addition to the profile JSON artifacts;
- gives each method the smaller of the workload's method timeout and its share
  of the remaining launch budget, because TSA applies its own timeout to every
  method independently;
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

## End-to-end MVP harness

`../ksmt/gradlew -p . -PuseLocalTuningContracts=true :tsa-tuning:runTuningMvp -Pcontract=<path-to.boc>` runs the whole TSA
side of the loop on a real contract: it writes a workload descriptor, captures
profiles, resolves them back into a workload, and measures the default cvc5
configuration against randomly proposed ones.

```bash
../ksmt/gradlew -p . -PuseLocalTuningContracts=true :tsa-tuning:runTuningMvp \
  -Pcontract=tsa-test/src/test/resources/demo/boc/integer-underflow.boc \
  -Pcandidates=4 -PmethodTimeoutSeconds=20
```

| Property | Default | Meaning |
| --- | --- | --- |
| `contract` | required | BoC contract to analyze |
| `workDir` | `build/mvp-tuning` | Descriptor and profile root; wiped before each run |
| `candidates` | `6` | Randomly proposed configurations |
| `methodTimeoutSeconds` | `60` | TSA timeout per method |
| `solverTimeoutSeconds` | `5` | cvc5 timeout per check |
| `trialTimeoutSeconds` | `120` | Budget of one trial |
| `seed` | `17` | Candidate sampling seed |
| `targetKind` | `receivers` | `receivers` or `all-methods` |
| `iterationLimit` | `6` | `unlimited` removes the limit |
| `maxRecursionDepth` | `5` | `unlimited` removes the limit |

Candidates are sampled from the option inventory of the loaded native library, so
only options that exist in this exact cvc5 build are proposed. The reported score
is the geometric mean of per-instance costs normalized by the baseline; below
`1.0` is faster. An instance that timed out contributes a PAR10-like penalty, and
a candidate with any wrong answer or unresolved instance is never selected.

The harness deliberately uses the stub `Cvc5ParameterSpaceImpl` and only the TSA
side of the loop: the production search host and a real parameter-space document
belong to `ksmt-ml-tuner`.

## Known limitations

- **Replay measures solver sessions.** End-to-end performance requires running
  the full analyzer with the approved configuration and with cvc5 defaults,
  then comparing elapsed time and analysis findings.
- **The MVP harness uses a smoke parameter space.** Full-analysis callers
  supply an approved `Cvc5ParameterSpace` that validates option domains and
  interactions. A deployable loader for the provider document remains open.
- **The ML-side providers are not implemented yet.** `ksmt-ml-tuner` contains the inference pipeline
  and deterministic stubs; it has no `Cvc5ConfigurationSearch` implementation, no ONNX adapter, and
  no search worker, and `ksmt-ml` publishes neither artifact on this branch.
- **No production command authors `workload.json`.** The descriptor is a curated artifact: copy
  [`examples/workload.json`](examples/workload.json) and edit it.
- **`tact` sources are not described by the workload schema yet**, because they need a compiled Tact
  project rather than a single contract file. Capturing a Tact workload has to go through a FunC/BoC
  artifact for now.
- **The FunC path is implemented but not exercised here.** It needs the `fift` and `func` binaries.
