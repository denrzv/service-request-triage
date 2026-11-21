# conventions.md — engineering conventions

Engineering standards for this repository. The lifecycle lives in
`workflow.md`, hard rules in `.claude/PROJECT_RULES.md`, binding architecture
in `CLAUDE.md`, `docs/architecture.md`, `docs/contracts.md`, and
`docs/adr/README.md`. Do not duplicate those documents here.

## Stack

- Java 25, Spring Boot 3.5.x, Spring AI 1.1.2 and Spring AI Alibaba Graph
  1.1.2.0 aligned through BOMs. Verify exact artifacts and source-compatible
  APIs before use; do not guess.
- English-only code, identifiers, prompts, fixtures, and documentation.

## Structure and dependency direction

- Ports & adapters: domain and application code do not depend on Spring Web,
  HTTP clients, or provider SDK types.
- Jev and DeepSeek are reached only through application ports
  (`JevClassificationPort`, investigator port); adapters own HTTP details,
  explicit timeouts, bounded retries, and cost budgets.
- Graph nodes have explicit inputs/outputs and safe failure transitions. Keep
  graph execution state separate from domain SR lifecycle.
- The Java Policy Engine is pure and deterministic; model output is untrusted
  input to it.
- Constructor injection only; configuration via validated
  `@ConfigurationProperties`, no scattered `@Value`.

## Code quality (SonarQube-aligned)

- Do not duplicate string literals; extract constants.
- Keep cognitive complexity of each method at 15 or below.
- Remove gratuitous boolean expressions (conditions that cannot change the
  outcome); inspect them for logic errors first.
- Prefer immutable records for contracts and value objects.
- When changing OpenAPI/AsyncAPI files, bump both the version field and the
  filename.

## Testing

- Unit tests with fakes for every provider port; default builds never call
  live Jev/DeepSeek. Live smoke tests are opt-in (profile or env flag) and
  their results are reported, not assumed.
- Test failure paths deliberately: provider errors/timeouts, budget
  exhaustion, `ABSTAIN`, `HUMAN_REVIEW`, `PARTIAL`.
- Fixtures are synthetic English requests only. Goldset labels never flow into
  prompts, tools, or agent-accessible resources.
- `./mvnw -q verify` is the deterministic gate run by `/pre-commit` once the
  Maven project exists.

## Security and privacy

- API keys from environment variables only; never logged or committed.
- Do not log full customer text by default; log ids, model aliases, scores,
  decisions, and latency.
- No real banking APIs; mock Task Management creates synthetic drafts only.
