[⬅️ Prev: Part XII](revision-part-12-data-and-ai.md) · [📋 Revision index](revision.md)

# Part XIII · Design Reviews & Practice — revision sheet
*Chapters [36 · Payments That Never Charge Twice](36-case-study-payments.md) · [37 · Moving 180 Services to JDK 25](37-case-study-fleet-jdk-upgrade.md) · [38 · A Multi-Tenant Webhook Delivery Platform](38-case-study-webhook-delivery.md) · [39 · Staff & Architect Artefacts](39-staff-artefact-exercises.md)*

## Every case study, the same packet
Brief → invariants → proposal → alternatives (with "do nothing") → ADR → failure analysis **with evidence** → migration stages with owners and gates → rollback limits → operational evidence.

**Rollback limits, three questions per stage:** after the new version writes, can the old one read it? What must flow back? When does rollback become a forward repair? Name the point of no return and hold a go/no-go before it.

## Payments (36)
- **Claim before charging:** insert the operation (unique on merchant + client key), call the PSP with the **operation's ID as the PSP key**, then a guarded update, ledger rows and an outbox row in one transaction. Lab: check-then-save charged **999 of 1,000** concurrent duplicate checkouts more than once; claim-first charged each **once**.
- **A timeout is unknown, not failed.** Re-send with the **same** key, at the same PSP, while the PSP keeps it (Stripe may prune keys after 24 h, so re-send within 23 h); after that, look up by reference. A new key only after a definite failure.
- **An empty search proves nothing:** search lags (Stripe's is eventually consistent). Taking it as "not charged" charged twice in the lab. `NOT_CHARGED` only from an authoritative source, such as the final settlement report.
- **A derived ID only protects a lost row while the PSP keeps the key:** a retry 26 h after the row was lost charged twice. So: claims on a **synchronous standby**, a **charge journal** outside the database (age and PSP), and recovery that re-sends only inside the window, otherwise reads and escalates.
- **Migration in both directions:** the new path looks up the old table, the old code gets a read shim, and each switch drains first. PostgreSQL lab: switching without the lookup charged **100** again, rolling back without the shim **101**; with them, **0**.
- **PostgreSQL:** with a derived primary key, `ON CONFLICT (merchant_id, idempotency_key)` let **19 of 2,000** racing claims fail on the primary key; `ON CONFLICT DO NOTHING` with no target handles every unique constraint.
- **HTTP answers:** 409 while the original is in progress (retry later) · 422 key reused with a different payload · 202 outcome still unknown · the stored 201 replayed for completed ones.
- Pin each operation to one PSP; failover is for new operations only. Prove the invariants with SQL (balanced ledger, one debit per success) and daily settlement reconciliation.

## Fleet JDK upgrade (37)
- **Scan, then run:** `jdeps --jdk-internals` and `jdeprscan --for-removal` from the **target** JDK, then tests with `--sun-misc-unsafe-memory-access=deny`. Both Guava 32 and protobuf-java 3.21 were flagged; under deny, Guava **fell back** and protobuf **failed** in a static initialiser.
- **Flags travel with the image** (`JDK_JAVA_OPTIONS`): JDK 21 won't start with `-XX:+UseCompactObjectHeaders`, `-XX:AOTCache` or `--sun-misc-unsafe-memory-access`; JDK 25 won't start with `-Djava.security.manager=allow`; `-XX:±ZGenerational` is ignored on 25.
- `-XX:+UseZGC` means **single-generation** on 17 and 21 and **generational** on 25, whatever the flags say. `-XX:+UseBiasedLocking` only warns on 17, and won't start on 21 or 25.
- **JDK 17 with no locale** (common in containers) reads and writes files as US-ASCII: "é" and "€" come back as replacement characters and are written as `?`. From JDK 18 the default is UTF-8.
- **Locale data:** JDK 17 writes an ordinary space before AM/PM; CLDR 42 (JDK 20) writes U+202F; `COMPAT` is gone from 23; lenient parsing accepts both spaces from 23, but JDK 17, and JDK 21 pinned to `COMPAT`, can't read U+202F even leniently. Text machines read is ISO-8601.
- **Step A** (run on 25, old `--release`) is reversible by image; **step B** (`--release 25`) is one-way: JDK 21 refuses class file version 69. Shared libraries move last.
- **Canary gates count requests:** at 1,000 requests a 5% p99 gate gave **19%** false alarms and missed **34%** of 10% regressions; about **20,000** brings both under 1%. A p50 gate missed **87–100%** of tail regressions.
- An AOT cache only works on the JDK release, OS, CPU architecture and class path that built it. Security defaults change in update releases too: compare builds, not version numbers.

## Webhook delivery (38)
- **Little's law:** one hung endpoint at 10 events/s × a 10 s timeout needs **100 workers**; with a pool of 64, healthy tenants waited **71 s** at p50 and **6.2 min** at p99.
- **Per-endpoint queues, a cap of 16 in flight, round robin:** healthy p99 **120 ms**; the backfill waits for itself (2.5 min instead of 2.3).
- **Breakers** on top: timed-out attempts **960 → 25**; the price is recovery (backlog cleared after **60 s** instead of 31). Queued events don't spend attempts while the breaker is open.
- The database is the source of truth and Kafka the doorbell; each endpoint has one owner (partition assignment), so caps and breakers are local state.
- **Standard Webhooks:** `webhook-id` stable across retries, `webhook-timestamp` within 5 minutes, HMAC-SHA256 over `id.timestamp.body`; sign with both secrets during rotation. Receivers dedupe for the retry window and acknowledge fast.
- **SSRF:** allow-list global unicast addresses. A "loopback or RFC 1918" check missed **8 of 15**, including 169.254.169.254 (twice), `0.0.0.0` and IPv6 private ranges. Resolve once, check, connect to that address (an egress proxy), and don't follow redirects.
- **Replays:** deliveries unique on (event, endpoint, **replay ID**), 0 for the original. Unique on (event, endpoint) alone, two replays were accepted and **nothing was sent**. Each replay is a request with its own `Idempotency-Key`: 11 retries of one call made **1** delivery (11 without a key).

## Decisions and artefacts (39)
- **Eight parts:** problem · constraints · options · decision · experiment · **acceptance criteria, written before the run** · delivery · reconsideration.
- **Virtual threads at 1.5× database capacity:** platform pool **454/s** answered in time, unbounded virtual threads **237/s**; with a bulkhead of 20 and deadlines, platform threads **671/s** and virtual threads **668/s** (p99 81 ms, no wasted queries). The limits fixed it, not the threads: the control row proves it.
- **Where the thread model matters:** 300 requests waiting on a 300 ms call. A pool of 200 answered **100/s** in time; a pool of 1,000 kept up on **1,010** platform threads, virtual threads on **10**. Judge virtual threads on resource use, simplicity and replayed production traffic.
- **Proposal:** the ask, cost and return first; options with their costs; a recommendation. **RFC:** open questions with owners and dates; dissent in its strongest form, with a status. **Plan:** named owners, dependencies, critical path, observable "done", rollback points.
- **Cost:** by year, people included, with a sensitivity table. Build vs buy came out **2%** apart, so the real quote and growth rate decide it (break-even **$15.39** per million at 40% growth).
- **Follow-up memo** at 90 days against the original criteria. **Evidence log:** decided, challenged, changed, outcome with a source. Real entries only.

## Say it in one breath
- "Claim the operation before you charge; a timeout is unknown, not failed, and an empty search is not a no."
- "JDK-specific flags travel with the image, and every one-way step gets a go/no-go."
- "A canary gate counts requests, not minutes."
- "Isolation comes from bounding what one tenant can hold, not from adding workers."
- "Virtual threads make waiting cheap; they don't make the database bigger."
- "I write the acceptance criteria before the experiment, so the result is allowed to say no."

---

[⬅️ Prev: Part XII](revision-part-12-data-and-ai.md) · [📋 Revision index](revision.md)
