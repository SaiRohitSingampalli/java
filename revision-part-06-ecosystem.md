[⬅️ Prev: Part V](revision-part-05-design.md) · [📋 Revision index](revision.md) · [Next: Part VII · Production ➡️](revision-part-07-production.md)

# Part VI · Ecosystem — revision sheet
*Chapters [18 · Spring Internals](18-spring-internals.md) · [19 · Persistence: JDBC, JPA & Hibernate](19-persistence-jdbc-jpa-hibernate.md) · [20 · Testing & Quality](20-testing-and-quality.md)*

## Spring: the three mechanisms behind most incidents
1. **The lifecycle.** Instantiate → populate → `Aware` callbacks → `postProcessBeforeInitialization` (including `@PostConstruct`) → `afterPropertiesSet` → init method → **`postProcessAfterInitialization`, where the AOP proxy is created**. So a `@Transactional` method called from `@PostConstruct` runs **without a transaction**; use `ApplicationReadyEvent` or `SmartInitializingSingleton` for startup work.
2. **Proxies.** Self-invocation (`this.method()`), `private` and `final` methods, and objects created with `new` are **not intercepted**. Aspect order (`@Order`, lower = outer) changes behaviour: retries outside the transaction get a fresh transaction per attempt.
3. **Thread-bound context.** Transactions, security and MDC live in `ThreadLocal`s, so `@Async`, executors, `CompletableFuture`, parallel streams and virtual threads you start yourself **don't inherit the transaction**.

## Spring facts
| Fact | Detail |
|---|---|
| Injection | Constructor injection by default. Resolution by type, then `@Qualifier` → `@Primary` → parameter name, otherwise `NoUniqueBeanDefinitionException` |
| Circular references | Prohibited by default since Boot 2.6 (`spring.main.allow-circular-references=false`). Field and setter cycles *can* be resolved through the three-level cache; constructor cycles can't (`BeanCurrentlyInCreationException`). Fix the design; `@Lazy` is a stopgap |
| Prototype in a singleton | Injected once, so it's the same instance forever → `ObjectProvider<T>` or `@Lookup`. The container never destroys prototypes |
| `@Configuration` | Full mode (the default) is a CGLIB subclass, so `clock()` called twice returns the same bean. Lite mode (`proxyBeanMethods = false`, what Boot's auto-configs use) makes it a plain call: inject dependencies as `@Bean` method parameters |
| Auto-configuration | Candidates listed in `META-INF/spring/…AutoConfiguration.imports`, guarded by conditions, and they **back off** (`@ConditionalOnMissingBean`). Debug with `--debug` or `/actuator/conditions` |
| Configuration precedence | Command line → `SPRING_APPLICATION_JSON` → system properties → environment variables → profile files outside, then inside, the JAR → `application.yml` |
| Boot 4 / Framework 7 (November 2025) | Java 17 minimum (25 recommended), Jakarta EE 11, JSpecify, API versioning on `@RequestMapping`, `@Retryable` and `@ConcurrencyLimit` in core, `@HttpExchange` clients, **Jackson 3**, modular auto-configuration, Undertow dropped, Hibernate 7 |

## `@Transactional` traps
- **Checked exceptions commit** by default; only `RuntimeException` and `Error` roll back → `rollbackFor = Exception.class`.
- **`UnexpectedRollbackException`:** a `REQUIRED` inner call threw, marking the *shared* transaction rollback-only, and your `catch` can't undo that → `REQUIRES_NEW`, `noRollbackFor`, or return a result type instead of throwing.
- **Remote calls inside a transaction** hold a connection and row locks for seconds and drain the pool → transaction, then the call, then another transaction, with idempotency.
- **`REQUIRES_NEW` holds two connections per thread:** a pool of 10 and 10 concurrent requests can deadlock on the pool.
- **Side effects on rollback:** emails or Kafka sends for things that never committed → `@TransactionalEventListener(phase = AFTER_COMMIT)` or an outbox.
- `readOnly = true` is a hint (no dirty checking or flush; may route to a replica), not a security control.

## Persistence
| Topic | Remember |
|---|---|
| Pool size | Start near `(core_count × 2) + effective_spindle_count`, about **10** for a 4-core database. **Multiply by instances**: 60 pods × 20 = 1,200 connections. Watch **acquire time**. `connectionTimeout` 1–5 s on request paths, not the 30 s default; `maxLifetime` shorter than any network idle timeout |
| Default isolation | Read Committed (PostgreSQL, Oracle, SQL Server); Repeatable Read (MySQL InnoDB) |
| PostgreSQL Repeatable Read | Snapshot isolation: no phantoms, but **write skew is possible**. Serializable (SSI) aborts with `40001`, so **retry** |
| Lost update fixes | Atomic conditional `UPDATE … WHERE stock >= 1` · `SELECT … FOR UPDATE` · `@Version` · Serializable plus retry |
| Job queue in SQL | `SELECT … FOR UPDATE SKIP LOCKED` |
| IDs | `IDENTITY` **disables Hibernate insert batching** → `SEQUENCE` with a pooled optimizer. Random UUIDv4 fragments B-tree indexes; UUIDv7 is time-ordered |
| Associations | `@ManyToOne` and `@OneToOne` default to **EAGER**: make everything `LAZY`. Only the owning side (the foreign key) is written. Two bags in one fetch → `MultipleBagFetchException` |
| N+1 | Detect by asserting query counts in tests. Fix with `JOIN FETCH`, entity graphs, `default_batch_fetch_size`, or **DTO projections** for reads |
| `JOIN FETCH` + pagination | Paginates **in memory** (Hibernate warns) → page the IDs first, then fetch |
| Open Session in View | **On by default** in Boot; holds a connection for the whole request and hides N+1s → `spring.jpa.open-in-view=false` |
| Write-behind | Constraint violations surface at flush or commit, not at `persist()` |
| Bulk JPQL | Bypasses the persistence context, leaving managed entities stale → `@Modifying(clearAutomatically = true)` |
| Entity `equals`/`hashCode` | ID-based `equals` (null ID equals only itself), constant `hashCode`. Never Lombok `@Data` on entities |
| Zero-downtime migrations | Expand → backfill in batches → switch reads → contract. `CREATE INDEX CONCURRENTLY`; set `lock_timeout` |

## Testing
- **Shape follows where your bugs come from:** a pyramid for logic-heavy code, a honeycomb (service-level integration tests with Testcontainers) for microservices, **contract tests** (Pact, Spring Cloud Contract) between services instead of a shared E2E environment.
- **Doubles:** dummy, stub, spy, mock, fake. Don't mock values or types you don't own; prefer fakes for your own ports. Mockito 5's inline mock maker can mock `final` classes; configure it as a `-javaagent` because of JEP 451's dynamic-attach warning.
- **Spring test slices** (`@WebMvcTest`, `@DataJpaTest`, …) and `@MockitoBean` (which replaced `@MockBean`, deprecated in Boot 3.4). **Every distinct context configuration means another cached context:** keep the set of mocked beans consistent and avoid `@DirtiesContext`.
- **H2 instead of PostgreSQL lies:** different dialect, locking and constraints. Use Testcontainers with `@ServiceConnection`.
- **Coverage measures what ran, not what was checked:** mutation testing (PIT) on critical modules.
- **JMH, not `nanoTime` loops:** dead-code elimination, constant folding and warmup all mislead. Return results or use a `Blackhole`, and use `@Fork`.
- **Flaky tests are bugs:** inject `Clock`, use Awaitility instead of `Thread.sleep`, fix seeds; quarantine with an owner and a deadline.
- JUnit 6 (September 2025) keeps the Jupiter model with a Java 17 baseline.

## Say it in one breath
- "The bean in the context is the proxy, not your object; everything about `@Transactional` follows from that."
- "Checked exceptions commit unless you say otherwise."
- "A small connection pool is usually faster: the database can only run about as many queries as it has cores."
- "Snapshot isolation stops phantoms but not write skew."
- "Coverage is a floor, not a target; mutation score tells you whether tests check anything."

---

[⬅️ Prev: Part V](revision-part-05-design.md) · [📋 Revision index](revision.md) · [Next: Part VII · Production ➡️](revision-part-07-production.md)
