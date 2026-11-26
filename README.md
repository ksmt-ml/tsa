TSA(TON Symbolic Analyzer) is a static analysis tool based on symbolic execution and designed for smart contracts on the [TON blockchain](https://ton.org/).

### Quick start

To install TSA, follow the [guide](docs/getting-started.md).

To know more about TSA use cases, read the [following document](docs/modes/use-cases.md).

The easiest way to use TSA is to generate tests. For examples, [see the following](docs/modes/test-gen-mode.md).

### Language Support
TSA works on TVM bitcode level, so it is possible to analyze smart contracts written in any language, just need to compile it to BoC format.

### Use Cases
TSA is designed for a few purposes:

- Detect possible TVM runtime errors: Find possible misbehavior while processing integers (overflow/underflow, division by zero) and slices/builders (reading or writing wrong number of bits, incorrect types, etc).
- Generate regression tests: TSA is able to generate Blueprint-based tests based on discovered execution paths that allow to fix expected behavior and find errorneous executions.
- Honeypots detection: TSA can detect and report malicious contracts that are created to fool users.

### cvc5 tuning integration

TSA selects a cvc5 configuration for a fresh solver behind the
[`tsa-tuning-api`](tsa-tuning-api/README.md) launch and evaluator boundary, which
[`tsa-tuning`](tsa-tuning/README.md) implements in-process. Solver profiling and
configuration search are provided by the sibling `ksmt` and `ksmt-ml-tuner`
repositories, which must be checked out next to TSA; see
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the required layout and JDK version.

TSA remains authoritative for workload execution, the correctness oracle, and
the produced artifacts. A configuration is applied only while constructing a
fresh cvc5 solver, before its first assertion.

A configuration produced by the tuner is applied to a real analysis with
`--cvc5-configuration <file>`, where the file is a
[`tsa-cvc5-configuration-v0`](tsa-core/schemas/tsa-cvc5-configuration-v0.schema.json)
document naming the backend role and the cvc5 options:

```bash
java -jar tsa-cli.jar boc -i contract.boc \
  --cvc5-configuration cvc5-configuration.json -o report.sarif
```

Only the named backend role is configured; the other one keeps the defaults.
Options reach the solver before its first assertion, and a captured profile
records them, so replaying that profile reproduces exactly the configuration the
profile was captured with. TSA refuses the option names it owns itself, because
they would make a captured profile unreplayable or make costs incomparable. See
[`tsa-tuning/README.md`](tsa-tuning/README.md#ml-team-quickstart) for the
end-to-end flow, the refused names, and the known limitations.

### Funding
TSA has been funded by the [TON Foundation grant](https://github.com/ton-society/grants-and-bounties/issues/489) grant and has been developed under the [8-month roadmap](https://questbook.app/dashboard/?proposalId=667ee6b9b59d3e9ae042d6c9&chainId=10&role=builder&isRenderingProposalBody=true&grantId=65c7836df27e2e1702d2d279).

### License

MIT © Esprito Tech QFZ LLC
