[⬅️ Prev: Part IX](revision-part-09-interview-mastery.md) · [📋 Revision index](revision.md) · [Next: Part XI · Practice Lab ➡️](revision-part-11-practice-lab.md)

# Part X · Specialist Topics — revision sheet
*Chapters [29 · Reactive Java: Reactor & WebFlux](29-reactive-java-reactor-webflux.md) · [30 · Startup, Footprint, AOT & Native Image](30-startup-footprint-aot-native-image.md) · [31 · API Design Between Services](31-api-design-between-services.md)*

## Reactive
- **Fits:** streaming (SSE, WebSockets), gateways with huge numbers of idle connections, fan-out with per-call timeouts, producers that must be slowed. **Poor fit:** blocking JDBC/JPA CRUD, CPU-bound work, teams new to it. Virtual threads removed most of the *scalability* argument for request/response; reactive keeps **streaming and backpressure**.
- **The contract:** `onSubscribe` once → `onNext` never more than requested → at most one terminal signal (`onError` or `onComplete`). Signals are serial, demand is cumulative, `request(0)` is an error, `onNext(null)` is illegal.
- **Nothing happens until `subscribe()`** ("my save never happened"). Subscriptions travel upstream, data downstream. Reactive doesn't mean multithreaded.
- **Backpressure, measured with `SubmissionPublisher`:** `offer()` kept the producer at full speed and dropped 492 of 500 items; `submit()` slowed the producer to the consumer's pace (989 ms for 200 items) and dropped none. If the producer can't slow down, choose an overflow strategy: bounded buffer, drop, latest, error, `limitRate`. `Flux.create` buffers **without bound** by default.
- **Cold vs hot:** cold publishers do the work per subscriber, so subscribing twice to a `WebClient` `Mono` sends **two requests** (and a retry is a re-subscription). Hot ones emit regardless; `share`, `publish().autoConnect`, `replay`, `cache` convert; check `EmitResult` from sinks.
- **Operators:** `flatMap` (concurrent, results in completion order; **256** concurrent inners by default, so pass a concurrency), `flatMapSequential` (concurrent, results in source order), `concatMap` (one at a time, in order), `switchMap` (a new element cancels the previous inner call). `zip` with an empty source is empty → `defaultIfEmpty`. `Mono.just(expensiveCall())` runs at assembly → `fromCallable` or `defer`.
- **Threads:** `parallel()` for short non-blocking CPU work; `boundedElastic()` (10 × cores) to wrap blocking calls; `subscribeOn` affects the subscription (the one closest to the source wins); `publishOn` switches everything after it (and buffers 256). `WebClient` responses arrive on Netty event-loop threads regardless.
- **Never block the event loop:** in the measured demo, **one** 200 ms blocking call delayed **166 unrelated requests**. Idiom: `Mono.fromCallable(blocking).subscribeOn(Schedulers.boundedElastic())`; BlockHound in tests; `block()` only at the edges.
- **Errors:** `onErrorReturn`, `onErrorResume`, `onErrorMap`; retries re-subscribe, so only for idempotent calls, with jitter; isolate per-element failures inside `flatMap`; avoid `onErrorContinue`.
- **Context:** `contextWrite` affects operators **above** it; MDC and tracing via Micrometer context-propagation (`spring.reactor.context-propagation=auto`).
- **WebFlux:** return the publisher (never `subscribe()` in a handler); R2DBC instead of JPA; `StepVerifier.withVirtualTime`; `.checkpoint()` for readable traces. HTTP carries no `request(n)` across the network beyond TCP flow control.

## Startup and footprint
- **Three different problems:** startup (time to first request), warmup (time to C2 peak), footprint (RSS). Every technique moves a different mix.
- **The CDS line:** default CDS (JDK 12) → AppCDS (10, dynamic archives in 13) → **AOT cache** (JDK 24, JEP 483: classes loaded *and linked*) → one-step training `-XX:AOTCacheOutput` (25, JEP 514) → method profiles (25, JEP 515) → cached objects with any GC (26, JEP 516).
- **Measured (small Jackson app):** JDK 25 default CDS 593 ms → **AOT cache 332 ms (−44%)**, with 3,663 of 3,706 classes from the cache; JDK 21 AppCDS −33%. The cache file was 32.3 MB.
- **The cache is only valid for the same JDK build and the same classpath** (JARs only, no wildcards or directories). On a mismatch the JVM **starts anyway without it**, slowly → `-XX:AOTMode=on` in a CI smoke test makes it fail instead.
- **Spring Boot:** extract the JAR (`-Djarmode=tools … extract`), training run with `-Dspring.context.exit=onRefresh`, then `-XX:AOTCache=app.aot`. Generate it in the final image stage, with a training profile that doesn't connect to real databases or brokers.
- **Committed ≠ resident:** NMT showed 186.7 MB committed but ~98 MB RSS. G1's structures scale with the **maximum** heap.
- **Compact object headers (measured, 5 M objects):** `Point(int,int)` 24 → 16 bytes, the whole heap −25%. Savings depend on 8-byte alignment.
- **Native Image:** closed-world analysis at build time → millisecond startup and low RSS, but long builds, reachability metadata for reflection and proxies, no `-javaagent`, usually lower peak throughput, and the bean graph is fixed at build time. **Support model:** in September 2025 Oracle announced that GraalVM for JDK 24 was the last release supported as part of Oracle Java SE products; GraalVM continues separately, and standard Java's startup story is Project Leyden.
- **CRaC:** restore a warmed-up snapshot (tens of ms), but it needs a CRaC JDK, Linux/CRIU privileges, and **snapshot hygiene**: identical restored state means identical "random" values unless you reseed, plus secrets in the snapshot.
- **The position I'd defend:** the **AOT cache on JDK 25** as the fleet default; native image where cold start dominates the economics; CRaC only with snapshot discipline.

## API design between services
- **Good APIs:** designed from consumer use cases (not your schema); consistent conventions (UTC ISO-8601, money never as floats); explicit contracts; additive evolution; retry-safe; bounded; observable. **Hyrum's law:** every observable behaviour becomes part of the contract.
- **Style per interaction:** REST at the edge, gRPC for internal low-latency and streaming, GraphQL for many clients needing different shapes (a BFF), events for facts.
- **HTTP:** `PUT` and `DELETE` are idempotent, `POST` isn't, `PATCH` not necessarily · `201` + `Location` · `202` for async work · `400` vs `422` · `401` vs `403` · `409` vs `412` · `429`/`503` with `Retry-After`.
- **Retries follow the operation and the error code, not the status alone:** transient failures (connection errors, 408, 429, 502, 503, 504) and 500s are resent only for idempotent or keyed operations, with backoff, jitter, a budget and `Retry-After`. `409 idempotency-key-in-use` → resend the **same** request later; `409` version conflict or `412` → re-read and send a **new** request; `422 idempotency-key-reused` and other 4xx → don't resend unchanged. Idempotency keys: claim the key before the work; same key + same payload → replay; different payload → 422; in flight → 409 + `Retry-After`; an expiry window.
- **Pagination (demonstrated):** offsets duplicated an order after an insert and silently skipped one after a delete; **keyset cursors** were correct both times. Cursors need a total order (add `id` as a tie-breaker) and should be opaque.
- **ETags:** `If-None-Match` → **304**; `If-Match` → **412** = optimistic locking over HTTP (maps onto `@Version`). `Cache-Control: no-cache` means "revalidate", not "don't store".
- **Errors:** RFC 9457 problem details; clients branch on `type`, never on `detail` text; one format across services.
- **JSON evolution (demonstrated):** a plain `new ObjectMapper()` **rejected an added field**, so tolerant readers must be a guideline. Unsafe changes: required request fields, removals and renames, type or unit changes, tighter validation. Enum additions need an `UNKNOWN` mapping from day one.
- **Versioning:** evolve without versions as long as possible; Spring Framework 7 API versioning; deprecate with `Deprecation` and `Sunset` headers and per-consumer usage data.
- **Protobuf (demonstrated):** adding a field is safe even through old services (unknown fields are preserved); **reusing a field number silently corrupts data** (a 15% discount read as `quantity=15`); changing a type made the value vanish. Use `reserved`, zero-valued `…_UNSPECIFIED` enums, `buf breaking` in CI.
- **gRPC:** deadlines propagate; status codes are the error contract (`UNAVAILABLE` is the retryable one); HTTP/2 needs L7 or client-side load balancing; 4 MB default inbound message limit.
- **GraphQL traps:** N+1 resolvers (DataLoader, `@BatchMapping`), query-cost attacks (depth and complexity limits, persisted queries), errors with HTTP 200, per-field authorisation.
- **Events:** events are facts, commands request action; thin vs fat events; a CloudEvents envelope whose `id` is the dedup key; schema-registry modes `BACKWARD` (upgrade consumers first), `FORWARD` (producers first), `FULL`, `*_TRANSITIVE` for long retention.
- **Governance:** contract-first for cross-team APIs, linting and breaking-change checks in CI, a shared starter, review only for public or high-fan-out APIs. Five synchronous services at 99.9% each give at most ~99.5% end to end.

## Say it in one breath
- "Backpressure is a demand signal, not magic: if the producer can't slow down, pick what to drop."
- "On an event loop, one blocking call hurts every request sharing that thread."
- "The AOT cache is standard OpenJDK and nearly free, but a mismatched classpath silently disables it."
- "Never reuse a Protobuf field number; reserve it."
- "Offset pagination duplicates and skips under writes; cursors don't."

---

[⬅️ Prev: Part IX](revision-part-09-interview-mastery.md) · [📋 Revision index](revision.md) · [Next: Part XI · Practice Lab ➡️](revision-part-11-practice-lab.md)
