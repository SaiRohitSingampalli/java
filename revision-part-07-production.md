[⬅️ Prev: Part VI](revision-part-06-ecosystem.md) · [📋 Revision index](revision.md) · [Next: Part VIII · Architecture ➡️](revision-part-08-architecture.md)

# Part VII · Production — revision sheet
*Chapters [21 · Performance Engineering & Troubleshooting](21-performance-engineering-troubleshooting.md) · [22 · Production Readiness, Observability & Resilience](22-production-readiness-observability-resilience.md)*

## Methods
- **Start from a goal:** "p99 < 200 ms at 2,000 RPS with CPU < 70%". Then measure → find the bottleneck → **one hypothesis, one change** → measure again under the same conditions.
- **USE for every resource** (CPU, memory, disk, network, thread and connection pools, locks): utilisation, saturation (what's waiting), errors.
- **RED for every service:** rate, errors, duration. Google's four golden signals add **saturation**.

## The laws
| Law | Formula | Example to quote |
|---|---|---|
| Little | `L = λ × W` | 500 req/s × 0.2 s = **100 in flight**. 50 workers ÷ 0.25 s = **200 req/s** maximum |
| Amdahl | `1 / ((1 − p) + p/N)` | 90% parallel → at most **10×**, however many cores |
| Queueing knee (M/M/1) | wait = `S × ρ / (1 − ρ)` | 80% busy → 4× the service time; 90% → **9×**; 99% → **99×**. Hence autoscaling targets of 60–70% |
| Fan-out tail | `1 − 0.99ᴺ` | 100 backends each slow 1% of the time → **≈ 63%** of requests hit a slow one |

## Measuring honestly
- **Averages lie, and you can't average percentiles.** Merge histograms (`publishPercentileHistogram()`, then `histogram_quantile`); `publishPercentiles(0.99)` can't be aggregated across instances.
- **Coordinated omission:** closed-loop load generators stop sending during a stall and under-report the tail by orders of magnitude → open-model, constant-arrival-rate tools (wrk2, Gatling open injection, k6 `constant-arrival-rate`).
- **Load, stress, soak, spike:** each answers a different question. Warm up the JIT before measuring.
- **Profilers:** sampling, not instrumenting. Beware safepoint bias in old profilers. **JFR** costs about 1% and can stay on in production; **async-profiler** `-e cpu | alloc | lock | wall`. **Wall-clock mode** finds "slow but low CPU" (threads waiting on I/O, locks or pools).
- **Flame graphs:** width = share of samples; the **x-axis is not time**; the top edge is what was running. Differential flame graphs explain regressions.

## Toolbox
| Need | Command |
|---|---|
| Thread dumps (take 3, ~5 s apart) | `jcmd <pid> Thread.print -l`; with virtual threads `Thread.dump_to_file -format=json` |
| Hot thread → stack | `top -H -p <pid>`, then find that ID in the dump: `[tid]` and `nid=tid` in decimal on JDK 21+; on JDK 17 and earlier `nid` is hex (`printf '%x' <tid>`) |
| JFR | `jcmd <pid> JFR.start settings=profile duration=60s filename=…`; `jfr view hot-methods`, `allocation-by-site`, `gc-pauses` |
| Heap | `GC.heap_info`, `GC.class_histogram` (forces a full GC), `GC.heap_dump` |
| Native memory | `VM.native_memory summary` (needs `-XX:NativeMemoryTracking=summary`) |
| GC every second | `jstat -gcutil <pid> 1000` |

**Thread-dump patterns:** one `RUNNABLE` holder and many `BLOCKED` waiters (I/O under a lock) · every request thread parked in `HikariPool.getConnection` (who holds the connections?) · pool workers in `CompletableFuture.join` waiting for subtasks queued to the same pool (a deadlock without locks) · hundreds of threads in socket reads (missing read timeouts).

**Playbooks:** high CPU → GC? JIT? code (flame graph)? CFS throttling? · high latency with normal CPU → tracing, wall-clock profile, thread dumps, GC pauses, queue depth · memory growth → post-GC floor trend, histogram diffs, heap dump, dominator tree; RSS growth with a healthy heap means native memory · regression after deploy → **roll back first**, then diff flame graphs, query counts and dependencies.

**Anti-patterns:** a `Pattern` compiled per call · `new ObjectMapper()` per request · exceptions as control flow · string building at disabled log levels · boxing in hot loops · I/O under locks · unbounded caches and queues · blocking work in the common pool · oversized pools.

**Mechanical sympathy:** L1 ~1 ns, DRAM ~100 ns, a datacenter round trip ~0.5 ms, a cross-continent round trip ~30–80 ms. One avoided round trip beats any CPU micro-optimisation.

## Resilience
- **Timeouts on every outbound call;** propagate the *remaining* deadline; a callee's timeout must be shorter than its caller's.
- **Retries:** transient failures and idempotent operations only; exponential backoff with **full jitter** (`random(0, min(cap, base × 2ⁿ))`); retry at **one** layer (3 layers × 3 attempts = **27** calls at the bottom); retry budgets; honour `Retry-After`; never retry 4xx except 429.
- **Circuit breaker:** closed → open (failure or slow-call rate over a window, with a minimum number of calls) → half-open trial calls. Put the retry outside the breaker so retries respect its state.
- **Bulkheads** per dependency (semaphores work with virtual threads) · **load shedding** (reject early with 429/503 and `Retry-After`) · adaptive concurrency limits · shed low-priority traffic first.
- **Fallbacks** must be counted and alerted on, and must never compromise correctness (don't approve payments when fraud checks are down).

## Running it
- **Probes:** liveness checks internal state only, **never dependencies** (otherwise a 30-second database blip restarts the whole fleet); readiness checks whether this instance can serve now; startup probes cover slow starts. Serve Actuator on a separate management port.
- **Graceful shutdown:** `preStop` sleep (endpoint removal and SIGTERM happen concurrently) → readiness `REFUSING_TRAFFIC` → drain → commit consumer offsets → exit before `terminationGracePeriodSeconds`.
- **JVM on Kubernetes:** memory `requests == limits`, `MaxRAMPercentage` 60–75%, be careful with CPU limits (CFS throttling), `ExitOnOutOfMemoryError`, heap dumps to a mounted volume (they're secrets), continuous JFR, `jcmd` in the image, PodDisruptionBudgets.
- **Logs:** structured JSON with `trace_id`; parameterised logging; MDC set and cleared per request and propagated across threads; never log secrets or PII. **Metrics:** bounded tag values only; route templates (`/orders/{id}`), never raw paths or user IDs.
- **SLOs:** error budget = 1 − SLO (99.9% over 28 days ≈ **40 minutes**). Multi-window burn-rate alerts: page at **14.4× over 1 h with a 5-minute window (2% of budget)**, page at 6× over 6 h with 30 min (5%), ticket at 1× over 3 days with 6 h (10%). Page on symptoms, with a runbook.
- **Deploys:** rolling (both versions live at once), blue/green, canary with automated analysis, feature flags (deploy ≠ release), shadow traffic (watch mirrored writes).
- **Incidents:** mitigate first, diagnose later; incident commander, comms lead and operators; capture dumps before restarting; blameless postmortems with owned, dated actions.

## Say it in one breath
- "Little's law sizes pools; the queueing knee sets autoscaling targets; Amdahl tells you when more cores won't help."
- "Timeouts are the foundation; retries without jitter and budgets are how a blip becomes an outage."
- "Liveness asks whether the process is broken beyond repair, not whether the database is up."
- "Error budgets turn reliability arguments into a pre-agreed policy."

---

[⬅️ Prev: Part VI](revision-part-06-ecosystem.md) · [📋 Revision index](revision.md) · [Next: Part VIII · Architecture ➡️](revision-part-08-architecture.md)
