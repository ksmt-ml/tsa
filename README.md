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

### Experimental cvc5 tuning integration

The stable application-owned tuning boundary is documented in
[`tsa-tuning-api`](tsa-tuning-api/README.md). It contains launch and evaluator
interfaces only.

The `dev/litvinovmitch11/demo` branch additionally contains non-production
profile capture, persistence, and cvc5 replay tooling used to validate those
interfaces against the sibling `ksmt`, `ksmt-ml-tuner`, and `ksmt-ml`
repositories. These implementations are reference material for the
infrastructure team and are not part of TSA's production API. The canonical
demo walkthrough is `ksmt-ml/docs/demo-end-to-end-ru.md` in the sibling
`ksmt-ml` checkout.

### Funding
TSA has been funded by the [TON Foundation grant](https://github.com/ton-society/grants-and-bounties/issues/489) grant and has been developed under the [8-month roadmap](https://questbook.app/dashboard/?proposalId=667ee6b9b59d3e9ae042d6c9&chainId=10&role=builder&isRenderingProposalBody=true&grantId=65c7836df27e2e1702d2d279).

### License

MIT © Esprito Tech QFZ LLC
