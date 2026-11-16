[⬅️ Prev: Part IV](revision-part-04-platform-apis.md) · [📋 Revision index](revision.md) · [Next: Part VI · Ecosystem ➡️](revision-part-06-ecosystem.md)

# Part V · Design — revision sheet
*Chapters [16 · Design Principles & Clean Architecture](16-design-principles-clean-architecture.md) · [17 · Design Patterns](17-design-patterns.md)*

## The two metrics
Almost every principle raises **cohesion** (a change touches one place) or lowers **coupling** (a change doesn't ripple). When principles conflict, ask which choice makes the *likely* changes cheaper.

Coupling, worst to most tolerable: **content** (reaching into internals) → **common** (shared mutable globals) → **temporal** (call `init()` before `start()`) → **control** (boolean flags that say what to do) → **stamp** (a whole object when one field is needed) → **data** ✅.

## Principles, as interviewers mean them
| Principle | The real meaning |
|---|---|
| SRP | One reason to change, meaning one **actor** who asks for changes |
| OCP | Add behaviour by adding code (a new strategy or sealed subtype), not by editing a growing `switch` everywhere |
| LSP | Subtypes don't surprise callers: no stronger preconditions, no weaker postconditions. `List.of(…).add(x)` throwing is a documented, deliberate compromise |
| ISP | Small, role-specific interfaces; clients don't depend on methods they don't call |
| DIP | The high-level side owns the abstraction (the port); details implement it. **DIP ≠ DI:** injecting a concrete `StripeClient` is DI without DIP |
| DRY | One authoritative representation of each piece of *knowledge*. "Duplication is far cheaper than the wrong abstraction" |
| YAGNI | Skip speculative features, but not the cheap-now, expensive-later decisions (IDs, UTC timestamps, idempotency keys) |
| Law of Demeter | `order.getCustomer().getAddress().getCity()` → `order.shippingCity()` |

## API design checklist
- Small surface ("when in doubt, leave it out"); consistent verbs (`find` → `Optional`, `get` throws if absent).
- Never return `null` for "nothing": an empty collection, `Optional`, or a sealed result type. Declare nullness with JSpecify (adopted by Spring Framework 7).
- Builders and enums instead of boolean soup (`send(msg, true, false, true, 3)`); types for units (`Duration`, not `long timeoutMillis`).
- Evolve additively; deprecate with `@Deprecated(since = …, forRemoval = true)` plus a migration path; check binary compatibility in CI.

## Architecture
- **Layered:** simple, but the domain ends up depending on persistence and the schema drives the design.
- **Hexagonal / Clean / Onion:** the core owns its ports; adapters implement them; **source dependencies point inward only**. The cost is mapping boilerplate, so it's worth it for complex domains (pricing), not for a CRUD admin service.
- **DDD tactical:** an entity has identity; a value object is immutable (`record Money`); an **aggregate** is the transactional consistency boundary with one root; one repository per aggregate root; **no business rules in application services**.
- **DDD strategic:** bounded contexts (no enterprise-wide `Customer` class), ubiquitous language, anti-corruption layers. Contexts are the first candidates for module boundaries, and later, perhaps, service boundaries.
- **Testability:** inject `Clock`, randomness and ID generators; constructor injection; prefer fakes to mocks for ports. Hard-to-test code is a design smell.
- **Legacy change:** characterisation tests → seams → break dependencies → small reversible steps. For big changes: branch by abstraction, expand–contract, strangler fig, the Mikado method.

## Patterns in modern Java
| GoF pattern | Modern form |
|---|---|
| Strategy / Command | A lambda; `Runnable`, `Callable`, `Supplier` |
| Visitor | Sealed interface + pattern-matching `switch` (the compiler checks exhaustiveness) |
| Singleton | A DI-managed bean, or an `enum`. As a class: the holder idiom; double-checked locking needs `volatile` |
| Value-object boilerplate | `record` |
| Observer | Spring application events, `Flow`, reactive streams |
| Template Method | A higher-order function that takes the varying step |

- **Where you already use them:** decorators (`java.io`, `BeanPostProcessor`), proxies (`@Transactional`, `@Cacheable`, `@Async`), chain of responsibility (the Spring Security `FilterChain`), unit of work and identity map (the JPA persistence context).
- **Don't pool cheap objects:** allocation is a pointer bump. Pool only expensive ones (connections, threads).
- **Anti-patterns to name:** god object, anaemic domain model, service locator, premature abstraction (wait for the rule of three), distributed monolith, pattern-itis.

## Say it in one breath
- "Every design choice is a trade-off; I'll say what we give up."
- "Slow down for one-way doors (public APIs, data models, service boundaries, event schemas); move fast on two-way doors."
- "An ADR records context, decision, consequences and alternatives, so the *why* outlives the people who decided."
- "If a principle matters, a fitness function (ArchUnit, latency budgets, compatibility checks) should fail the build when it's broken."
- "Refactor towards patterns when the code's growing pains call for them; don't start from them."

---

[⬅️ Prev: Part IV](revision-part-04-platform-apis.md) · [📋 Revision index](revision.md) · [Next: Part VI · Ecosystem ➡️](revision-part-06-ecosystem.md)
