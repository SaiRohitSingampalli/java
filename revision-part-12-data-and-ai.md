[⬅️ Prev: Part XI](revision-part-11-practice-lab.md) · [📋 Revision index](revision.md)

# Part XII · Data & AI — revision sheet
*Chapters [34 · Data Stores: Redis, Cassandra & DynamoDB](34-data-stores-redis-cassandra-dynamodb.md) · [35 · AI Features in Java: Spring AI, LangChain4j, RAG & Guardrails](35-ai-features-spring-ai-langchain4j-rag.md)*

## Choosing a store
| Store | Reach for it when | Price you pay |
|---|---|---|
| PostgreSQL | The default system of record: transactions, constraints, ad-hoc queries | Vertical scaling first; sharding is work |
| Redis / Valkey | It fits in memory and can be rebuilt or lost: caches, counters, rate limits, leaderboards, short-lived coordination | Async replication, memory cost, one command thread |
| Cassandra | Write-heavy, partition-shaped data (time series, messaging), multi-datacentre, on infrastructure you run | Query-first modelling, repair, compaction, tombstones |
| DynamoDB | AWS, known access patterns, no servers, predictable latency at any scale | Key design up front; hot keys throttle; every scan is billed |

## Redis (measured on a live server)
- **One command thread:** every command is atomic, and a slow one stalls everyone. `KEYS *` over 1 M keys: **282 ms** blocked; `SCAN`: worst wait **4.5 ms**. `DEL` of a 2 M-field hash: **589 ms**; `UNLINK`: **< 1 ms** (freed in the background).
- **Round trips dominate:** at ~0.8 ms RTT, **1,174** ops/s one at a time vs **413,720** pipelined. Pipelining isn't atomic; `MULTI`/`EXEC` is atomic but can't read mid-block and has **no rollback**; Lua runs read-decide-write atomically (and blocks everyone meanwhile).
- **Lost updates:** 4 clients × 2,500 `GET`+`SET` → **3,286** of 10,000. Use `INCR`/`HINCRBY`/`SET … IFEQ` (8.4+), Lua, or `WATCH` + retry (13,284 retries here).
- **Eviction:** `noeviction` is the default; `volatile-*` evicts **only keys with a TTL**, so without TTLs it fails exactly like `noeviction` (both at ~24,000 keys). Caches: `allkeys-lru`/`allkeys-lfu`, TTL with jitter on every key, ~**1.4 KB** per 1 KB value.
- **Locks:** `SET key token NX PX ttl`; release with compare-and-delete, never plain `DEL` (it deleted the next holder's lock). Pauses outlive leases, so correctness needs **fencing tokens** checked by the resource.
- **Durability:** RDB loses everything since the snapshot; AOF `everysec` about a second; replication is **asynchronous**, so failover can drop acknowledged writes. `WAIT`/`WAITAOF` narrow the window; they don't make Redis strongly consistent.
- **Cluster:** 16,384 slots, `CRC16(key) mod 16384`; hash tags `{user:42}` co-locate keys (and concentrate load); cross-slot multi-key ops fail with `CROSSSLOT`; clients follow `MOVED`/`ASK`.
- **Streams vs Pub/Sub:** streams keep messages, track pending entries per consumer and let another consumer `XAUTOCLAIM` a crashed one's work; Pub/Sub to zero subscribers loses the message.
- **From Java:** Lettuce (Boot's default; its own command timeout is 60 s, so set ~250 ms), no JDK serialisation, versioned key names, retries only for idempotent commands, a circuit breaker and a **cache-down plan**.

## Cassandra
- Ring of equal nodes; Murmur3 tokens; RF per datacentre; **`LOCAL_QUORUM` writes + reads with RF 3 (2 + 2 > 3) = read-your-writes** in a datacentre.
- LSM writes (commit log → memtable → SSTables → compaction). Compaction: size-tiered (writes), leveled (reads), time-window (TTL'd time series), UCS (5.0).
- **Model from the queries:** one table per query, every query restricts the partition key (`ALLOW FILTERING` is a smell), clustering columns order rows, partitions bounded (~**100 MB / 100,000 rows**; bucket by time).
- **Deletes write tombstones,** purged after `gc_grace_seconds` (10 days) only if repair ran. Reads warn at 1,000 and fail at 100,000 tombstones, so **never a queue**. LWTs (`IF NOT EXISTS`) run Paxos: compare-and-set only.
- 5.0: SAI indexes, vector search, UCS. 6.0 (alpha in 2026): Accord transactions.

## DynamoDB
- Items ≤ **400 KB**. `Query` = one partition key + sort-key condition; `Scan` = whole table; a `FilterExpression` runs **after** the read, so you pay for everything read.
- 1 RCU = 1 strongly consistent read/s ≤ 4 KB (or 2 eventually consistent); 1 WCU = 1 write/s ≤ 1 KB; transactions cost double. Each partition: **3,000 RCU, 1,000 WCU, ~10 GB**; one hot key throttles however much the table has.
- GSIs: own keys, **eventually consistent only**, sparse, ≤ 20; a lagging GSI throttles base-table writes (provisioned). LSIs: created with the table, 10 GB per item collection.
- Conditional writes for create-if-absent and optimistic locking; transactions of ≤ 100 actions; Streams (24 h) as the outbox; TTL deletes within days; vector search GA August 2026.
- **Single-table design** when access patterns are known and related items should come back in one `Query`; not for exploratory queries or entities owned by different teams.

## AI features: the model call
- **A slow, non-deterministic, metered RPC with untrusted text in and out.** Stream to people; Little's law (50 req/s × 6 s = **300 in flight**); virtual threads + a semaphore sized to the provider's limits; timeouts at three levels; retry 429/5xx with `Retry-After`; fallbacks.
- **Cost:** output caps, small models for simple tasks, stable prompt prefixes for **prompt caching**, budgets per tenant, tokens logged per request. Semantic caching can confuse "cancel" with "don't cancel".
- **Spring AI 2.0** (Boot 4): `ChatClient` + advisors, `ToolCallingAdvisor`, `StructuredOutputValidationAdvisor`, MCP Java SDK 2.0. **LangChain4j**: `AiServices` interfaces, `@Tool`, input and output guardrails (re-prompt, 2 retries by default). Policies stay in your code either way.

## RAG and vector search (measured)
- Ingest: parse → chunk (structure-aware) → embed (store model + version) → index with **tenant, ACL, source, version** metadata. Query: rewrite → embed → retrieve **filtered by permissions** → re-rank → prompt with passage IDs → validate citations.
- **HNSW**, 50,000 vectors: exact scan **2,761 µs**; `efSearch` 40 → recall@10 **0.995 in 66 µs** (~42×, under 1% of vectors compared); `efSearch` 10 → 0.846. pgvector defaults: `m` 16, `ef_construction` 64, `ef_search` 40.
- **Quantisation:** int8 (4× smaller) 0.981 → re-score top 50 → 1.000; 1-bit (32× smaller) 0.213 → re-score top 500 → 1.000.
- 💣 **Post-filtering:** top-40 then one tenant of 100 → **0.4** results; exact scan of the tenant's 500 rows → 88 µs, recall 1.000. Filter inside the index, partition by tenant, or scan small subsets exactly.
- **Vector API** dot products: **3.5×** over a scalar loop on JDK 25, 2.5× on JDK 21, 4.4× in cache; the big scan is memory-bandwidth bound. Still incubating (12th round in JDK 27).
- **Hybrid search:** BM25 + vectors fused with **RRF** (1 / (60 + rank)); re-rank ~50 → 5; most RAG bugs are retrieval bugs, so measure recall@k separately.

## Guardrails, tools and security (measured)
- **Structured output:** schema-constrained generation → strict parse → cheap repair → **business validation in code** → re-prompt with the error, capped. Strict parsing failed 4 of 7 replies, repair rescued 2, and 2 valid-JSON replies were still wrong.
- **Tool loop order:** unknown tool → policy from **your data** → argument checks → human approval (irreversible or expensive) → run (idempotency key) → step, token and cost caps → log everything.
- **Prompt injection:** a regex blocklist caught **2 of 8** attacks and blocked 1 of 2 customers. **Lethal trifecta:** private data + untrusted content + external communication; remove a leg. Least-privilege tools, untrusted content processed without tools, no secrets in context, output encoded and parameterised, PII redacted (Luhn keeps order numbers).
- **OWASP LLM Top 10 (2026):** prompt injection · sensitive information disclosure · excessive agency · supply chain · data and model poisoning · unbounded consumption · misinformation · hidden context exposure · vector and embedding weaknesses · improper output handling.
- **Workflows before agents.** MCP: standard tool protocol (Linux Foundation's AAIF since December 2025; stateless from the 2026-07-28 revision). Treat every server as third-party code your agent obeys.
- **Evaluate:** an eval set per stage (retrieval, accuracy, faithfulness via an LLM judge with human spot checks), run in CI on every prompt, model or retrieval change; trace with OTel `gen_ai.*` (still Development stability).

## Say it in one breath
- "Redis holds what I can rebuild or lose: every key gets a TTL, and the system gets a cache-down plan."
- "A lease without a fencing token is a hint, not mutual exclusion."
- "In Cassandra and DynamoDB I design keys from the queries, not queries from the tables."
- "A model call is a slow, non-deterministic, metered RPC with untrusted text in and out."
- "Permissions are enforced at retrieval: a model can't un-see a document."
- "I assume the model will be fooled, and limit what a fooled model can do."

---

[⬅️ Prev: Part XI](revision-part-11-practice-lab.md) · [📋 Revision index](revision.md)
