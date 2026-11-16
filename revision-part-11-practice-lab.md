[⬅️ Prev: Part X](revision-part-10-specialist-topics.md) · [📋 Revision index](revision.md) · [Next: Part XII · Data & AI ➡️](revision-part-12-data-and-ai.md)

# Part XI · Practice Lab — revision sheet
*Chapters [32 · Production Troubleshooting Scenarios](32-production-troubleshooting-scenarios.md) · [33 · Annotated Mock Interviews](33-annotated-mock-interviews.md)*

## The incident method
**Stabilise** (roll back, scale out, shed load) → **observe** (what changed, and when? RED and USE) → **hypothesise** (rank by likelihood and cost to test) → **prove** (thread dumps, histograms, NMT, JFR, logs; back to hypotheses if disproved) → **fix** (mitigate now, repair properly later) → **prevent** (an alert, a test, a guardrail, a postmortem).

"What changed?" answers most incidents. Capture evidence **before** restarting; "we restarted it and it went away" means the evidence is gone.

## The twelve cases
| # | Symptom | Cause (reproduced in the labs) | Proof | Fix and prevention |
|---|---|---|---|---|
| 1 | `OOMKilled` (exit 137), heap 30–40%, no Java error | Off-heap growth: leaked direct buffers (heap stayed at **2 MB** while 64 MB grew off-heap) | Kernel kill in pod status and `memory.events`; NMT "Other" grows | Set `MaxDirectMemorySize` (otherwise it defaults to the max heap), size heap so non-heap fits, NMT diffs, alert on RSS vs limit |
| 2 | Requests hang, CPU idle, "no deadlock found" | **Thread-starvation deadlock:** parents block on children queued to the same pool | Every pool thread `WAITING` in `Future.get`/`join` | Separate pools, non-blocking composition, virtual threads or structured concurrency |
| 3 | Throughput fell after enabling virtual threads | Pinning: blocking inside `synchronized` on JDK 21 (**5,049 ms vs 81 ms** on JDK 25) | JFR `jdk.VirtualThreadPinned` | JDK 24+ (JEP 491), `ReentrantLock`, upgrade the library; load-test the switch |
| 4 | `OutOfMemoryError: Metaspace` after days | Class-loader leak: old plugin instances keep their loaders alive | `VM.classloader_stats`; many loader instances in a heap dump | Drop every reference to old loaders; `MaxMetaspaceSize` as a safety net |
| 5 | Old gen grows for days, then OOM | An unbounded session map | Two `GC.class_histogram`s: **your** class grows in lockstep with `HashMap$Node` (548,001 → 1,208,001) | Bounded cache with expiry (Caffeine), external session store |
| 6 | Latency and CPU climb, then OOM or a zombie | GC death spiral: the live set approaches the heap | GC log: **97%** of wall time paused, `124M->123M` full GCs | Alert on share of time in GC (> 10% over 5 min); `ExitOnOutOfMemoryError` |
| 7 | Pool timeouts while the database is idle | A 200 ms remote call **inside** the transaction | Little's law: ~220 ms per connection → 10 connections ≈ 45 req/s (113 timeouts vs 0) | Remote calls outside transactions; outbox. A bigger pool only moves the bottleneck |
| 8 | One pod at 100% CPU | Catastrophic regex backtracking on one customer's input | `top -H` → thread ID (decimal on modern JDKs) → `nid` in the thread dump | Non-backtracking patterns, input limits, continuous JFR |
| 9 | p99 spikes every 10 minutes | Synchronised cache expiry (or scheduled jobs, platform cycles, GC) | Align metrics, GC logs and job schedules in time | Jittered TTLs, refresh-ahead, coalesced misses |
| 10 | Kafka lag explodes after a deploy | Batches take longer than `max.poll.interval.ms` → rebalances and reprocessing | Rebalance rate, processing time per batch | Smaller `max.poll.records`, timeouts on enrichment, cooperative or KIP-848 protocol, static membership, idempotency |
| 11 | Pods restart in a loop during rollout | Liveness kills a slow start, liveness checks dependencies, or CPU throttling at boot | Probe events, cgroup throttling metrics | Startup probes, dependency-free liveness, faster startup (Ch30) |
| 12 | Errors for 10–30 min after a 30 s failover | Stale pooled connections, DNS caching, half-open TCP | Connection errors pointing at the old primary | `maxLifetime` and timeouts, failover-aware drivers, modest DNS TTL, TCP keepalive, rehearsed failovers |

## Telling an incident story
1. **Clarify and stabilise:** "All pods or some? Since when? Any deploy? I'd make sure we have headroom, or roll back."
2. **Cheap checks first:** "OOMKilled means the kernel, not the JVM, so I'm comparing RSS with the limit, not looking at the heap."
3. **Name hypotheses with their tests:** "Direct buffers, Metaspace, threads, or a heap sized too close to the limit. NMT diffs will tell me which."
4. **Say what evidence you'd expect,** then the fix and the prevention.
5. **Close with the systemic lesson:** the alert, guardrail or fleet default you'd change.

## What the mock transcripts teach
- **JVM deep-dive (virtual threads, p99 180 ms → 2.4 s):** reason from the shape ("low CPU + high latency means waiting"; a *cliff* suggests a saturated fixed resource); ranked hypotheses, each with its proving evidence; mitigate, repair, **verify** (pinned-event count), add a concurrency limit; then the staff move: inventory, guidance, fleet telemetry, a better default, an ADR.
- **System design (webhook platform, 50K events/s):** numbers that change the design (a separate backlog store); per-endpoint circuit breakers, a parked backlog and a **controlled drain**; per-tenant quotas and fair scheduling, cells for the largest tenants; optional per-resource ordering as an explicit trade-off; HMAC with timestamps and two active secrets; **SSRF through customer URLs**; an explicit v1 scope.
- **Behavioural (influence without authority):** the weak answer has no numbers and no "I"; the senior answer fixes one team's pain; the staff answer diagnoses a *class* of incidents from history, pre-wires the sceptics, changes the design because of their objections, ships a **mechanism with an escape hatch**, and measures adoption and outcomes.
- **Coding (per-key rate limiter):** ask about bursts, threads, **unbounded keys** and an injectable clock up front; test the burst, refill, isolation, a concurrency invariant and eviction; estimate memory (~140 bytes per key, so 10 M keys ≈ 1.4 GB); volunteer the eviction race and its bounded impact; for 20 instances, compare local, centralised (Redis Lua) and hybrid limits per traffic class.

## Score yourself (1–4 on each)
Clarifying · structure · depth (the *why*) · evidence (how you'd prove it) · trade-offs · failure modes · scope (team or fleet) · communication. Practise by covering the candidate lines and answering out loud, role-playing, recording yourself, and keeping to time: deep dives by minute 25 in design, coding finished by minute 30.

## Say it in one breath
- "Stabilise first; then ask what changed."
- "A plausible story isn't a root cause until the evidence proves it."
- "Virtual threads remove thread limits, not resource limits."
- "I'd turn this incident into a guardrail for the whole fleet."

---

[⬅️ Prev: Part X](revision-part-10-specialist-topics.md) · [📋 Revision index](revision.md) · [Next: Part XII · Data & AI ➡️](revision-part-12-data-and-ai.md)
