# FEATURE-002: Reliability review
Status: RELIABILITY_PASS
Outcome: RELIABILITY_PASS

## Checklist
- Timeouts: explicit connect (HttpClient) and per-request timeouts, defaults 5s/30s, validated positive; no unbounded waits. Read timeout retried (tested).
- Retries: only 429, 5xx, `IOException` (including timeouts); attempts bounded 1..10 (default 3); exponential backoff capped, numeric `Retry-After` honoured up to the cap; terminal statuses and protocol errors never retried; worst-case wall time is bounded by attempts x request timeout plus capped sleeps.
- Idempotency: stateless read-style inference; a retried call may be billed twice (documented).
- Ordering/recovery: no state; any malformed or missing answer fails the whole call, no partial result.
- Interruption: `InterruptedException` in send or sleep restores the interrupt flag and raises `JevUnavailableException` (tested for backoff).
- Observability: per-attempt INFO logs (attempt, status, kind, elapsed) and success log with model/tokens/latency; latency uses a monotonic clock; result exposes attempts and usage. Metrics deferred to FEATURE-012.
- Test determinism: injectable `Sleeper` and clock; no real sleeping in tests.

## Findings
No Blocking or Important findings.
Note (Minor): retry attempts of a fully malformed 2xx are intentionally not retried; this is a policy choice for the later graph node.
