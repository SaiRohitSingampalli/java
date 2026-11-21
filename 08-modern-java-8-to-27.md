[⬅️ Prev: 07 · Lambdas & Streams](07-lambdas-streams-functional.md) · [🏠 Index](README.md) · [Next: 09 · JVM Internals ➡️](09-jvm-internals.md)

# 08 · Modern Java: 8 → 27

> *Java changed more between 2017 and 2026 than in its first twenty years. If your mental model is still "Java 8 with streams", interviewers will notice. This chapter is the catch-up, version by version, with the features that actually matter explained properly.*

🧪 **Runnable labs:** [the programs behind this chapter's outputs](labs.md#chapter-08--modern-java)

---

## Table of contents
1. [Release timeline at a glance](#1-release-timeline-at-a-glance-)
2. [Java 8: the foundation of modern Java](#2-java-8-the-foundation-of-modern-java-)
3. [`java.time`: getting dates right](#3-javatime-getting-dates-right-)
4. [Java 9 → 17: the road to the next LTS](#4-java-9--17-the-road-to-the-next-lts-)
5. [Java 18 → 21: Loom and Amber land](#5-java-18--21-loom-and-amber-land-)
6. [Pattern matching: the full picture](#6-pattern-matching-the-full-picture-)
7. [Java 22 → 25: polishing and the 2025 LTS](#7-java-22--25-polishing-and-the-2025-lts-)
8. [Java 26 & 27: where we are now](#8-java-26--27-where-we-are-now-)
9. [What's next: Valhalla, Leyden, Babylon](#9-whats-next-valhalla-leyden-babylon-)
10. [Migration playbook: 8 → 11 → 17 → 21 → 25](#10-migration-playbook-8--11--17--21--25-)
11. [Interview questions](#11-interview-questions)
12. [References](#12-references)

---

## 1. Release timeline at a glance 🟢

| Version | Released | LTS | Headline features |
|---|---|---|---|
| **8** | Mar 2014 | ✅ | Lambdas, streams, `java.time`, default methods, `CompletableFuture`, Metaspace replaces PermGen |
| 9 | Sep 2017 | | Modules (JPMS), JShell, `List.of`, G1 by default, compact strings, indy string concat |
| 10 | Mar 2018 | | `var`, application class-data sharing, `List.copyOf` |
| **11** | Sep 2018 | ✅ | Standard `HttpClient`, new `String` methods, single-file launch, ZGC (exp.), **Java EE & CORBA modules removed** |
| 12 | Mar 2019 | | Shenandoah (exp.), `Collectors.teeing`, switch expressions (preview) |
| 13 | Sep 2019 | | Dynamic CDS archives, text blocks (preview) |
| 14 | Mar 2020 | | **Switch expressions**, helpful NPEs, CMS removed |
| 15 | Sep 2020 | | **Text blocks**, ZGC & Shenandoah production-ready, hidden classes, Nashorn removed, biased locking disabled |
| 16 | Mar 2021 | | **Records**, `instanceof` patterns, `Stream.toList()`, strong encapsulation by default |
| **17** | Sep 2021 | ✅ | **Sealed classes**, internals strictly encapsulated, `RandomGenerator`, Security Manager deprecated |
| 18 | Mar 2022 | | UTF-8 by default, `jwebserver`, `@snippet` in Javadoc, finalization deprecated for removal |
| 19 | Sep 2022 | | Virtual threads (preview), record patterns (preview) |
| 20 | Mar 2023 | | Scoped values (incubator) |
| **21** | Sep 2023 | ✅ | **Virtual threads**, **pattern matching for `switch`**, **record patterns**, sequenced collections, generational ZGC |
| 22 | Mar 2024 | | **Foreign Function & Memory API**, unnamed variables `_`, multi-file source launch |
| 23 | Sep 2024 | | Markdown doc comments, generational ZGC by default; String Templates *withdrawn* |
| 24 | Mar 2025 | | **Stream Gatherers**, Class-File API, **virtual threads no longer pin on `synchronized`**, AOT class loading, ML-KEM/ML-DSA |
| **25** | Sep 2025 | ✅ | **Scoped values**, compact source files & instance `main`, module imports, flexible constructor bodies, compact object headers (opt-in) |
| 26 | Mar 2026 | | HTTP/3 in `HttpClient`, AOT object caching with any GC, "final means final" warnings, Applet API removed |
| 27 | Sep 2026 | | **G1 default everywhere**, **compact object headers by default**, post-quantum hybrid TLS 1.3, JFR data redaction |

> [!TIP]
> In interviews, "Java 21 vs 25" questions are common right now. The short version: **21** brought the big features (virtual threads, pattern matching). **25** finalized the supporting cast (scoped values, flexible constructors, compact sources) and fixed virtual-thread pinning (that fix landed in 24, so 25 is the first LTS to have it).

---

## 2. Java 8: the foundation of modern Java 🟢

Java 8 is old now, but everything modern builds on it:
- **Lambdas and method references** ([Chapter 07](07-lambdas-streams-functional.md))
- **Streams API** ([Chapter 07](07-lambdas-streams-functional.md))
- **Default and static interface methods** ([Chapter 02](02-oop-and-object-model.md#6-abstract-classes-vs-interfaces-modern-edition-))
- **`Optional`** ([Chapter 07](07-lambdas-streams-functional.md#6-optional-what-its-for-and-how-its-abused-))
- **`java.time`** (JSR 310), see §3
- **`CompletableFuture`**, `StampedLock`, `LongAdder`, `ConcurrentHashMap` redesign ([Chapter 12](12-concurrency-advanced-juc-loom.md))
- **PermGen removed, Metaspace introduced** ([Chapter 10](10-memory-and-garbage-collection.md))
- `String.join`, `Base64`, `Arrays.parallelSort`, type annotations, repeatable annotations

---

## 3. `java.time`: getting dates right 🟡→🔴

`java.util.Date` and `Calendar` were mutable, not thread-safe, 0-indexed for months, and confusing about time zones. `java.time` (designed by the Joda-Time author) fixed all of that. Every class is **immutable and thread-safe**.

| Type | Represents | Use for |
|---|---|---|
| `Instant` | A point on the UTC timeline (nanosecond precision) | Event timestamps, logs, DB audit columns |
| `LocalDate` / `LocalTime` / `LocalDateTime` | A *wall-clock* date/time with **no zone** | Birthdays, "the store opens at 09:00", business dates |
| `ZonedDateTime` | Local date-time + `ZoneId` (with DST rules) | User-facing scheduling, calendar math across DST |
| `OffsetDateTime` | Local date-time + fixed `ZoneOffset` | Wire formats, `TIMESTAMP WITH TIME ZONE` columns |
| `Duration` | Exact amount of time (seconds + nanos) | Timeouts, elapsed time |
| `Period` | Calendar amount (years, months, days) | "Add one month", subscriptions |
| `Clock` | Source of "now" | **Inject it** for testable code |

💣 **`Duration` vs `Period` across a DST change:**
```java
ZonedDateTime t = ZonedDateTime.of(2026, 3, 7, 9, 0, 0, 0, ZoneId.of("America/New_York"));
t.plus(Period.ofDays(1));    // 2026-03-08T09:00-04:00[America/New_York]  ← same wall-clock time
t.plus(Duration.ofDays(1));  // 2026-03-08T10:00-04:00[America/New_York]  ← exactly 24h later; the day had 23 hours
```

💣 **`SimpleDateFormat` is not thread-safe.** A `static final SimpleDateFormat` shared across request threads is a classic source of silently corrupted dates. `DateTimeFormatter` is immutable and safe to share.

```java
// Testable time: never call Instant.now() directly inside business logic
class TokenService {
    private final Clock clock;
    TokenService(Clock clock) { this.clock = clock; }
    boolean isExpired(Token t) { return Instant.now(clock).isAfter(t.expiresAt()); }
}
// in tests: new TokenService(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
```

🧠 **Staff lens: storing time.**
- **Past events** (orders placed, logins): store an `Instant` in UTC and convert to the user's zone only for display.
- **Future local events** ("the meeting is at 09:00 Berlin time next October"): store the **`LocalDateTime` + `ZoneId`**, *not* an `Instant`. Governments change time-zone rules, and once the tzdata update arrives, an `Instant` computed today may point to 08:00 or 10:00 local time. The JDK ships tzdata updates, so keep your JDK patched.
- Never use `LocalDateTime` for an event that happened at a specific moment. It can't represent an instant (during a DST fall-back, the same local time occurs twice).

---

## 4. Java 9 → 17: the road to the next LTS 🟡

### Java 9 (2017)
- **JPMS / modules**: `module-info.java`, strong encapsulation ([Chapter 15](15-modules-and-build-tools.md)).
- **Collection factories**: `List.of`, `Set.of`, `Map.of`, `Map.entry` ([Chapter 06](06-collections.md#12-immutable-vs-unmodifiable-vs-fixed-size-)).
- **JShell**, **private interface methods**, try-with-resources on effectively-final variables, `@SafeVarargs` on private methods.
- `Optional.ifPresentOrElse/or/stream`, `Stream.takeWhile/dropWhile/ofNullable/iterate(seed, hasNext, next)`.
- **`CompletableFuture.orTimeout/completeOnTimeout`**, `delayedExecutor`.
- `StackWalker`, `VarHandle`, the Process API (`ProcessHandle`), the `Flow` reactive-streams interfaces, multi-release JARs.
- **G1 becomes the default GC**, **compact strings**, **indy string concatenation**.

### Java 10 (2018)
- **`var`** ([Chapter 01](01-java-platform-fundamentals.md#11-variables-var-final-scope-)), `List.copyOf`, `Collectors.toUnmodifiable*`, `Optional.orElseThrow()`.
- Application Class-Data Sharing (AppCDS), parallel full GC for G1.

### Java 11 (2018, LTS)
- **`java.net.http.HttpClient`**: HTTP/2, sync and async ([Chapter 13](13-io-nio-networking-serialization.md)).
- `String.isBlank/lines/strip/repeat`, `Files.readString/writeString`, `Predicate.not`, `Optional.isEmpty`.
- **Single-file source launch** (`java Foo.java`), `var` in lambda parameters, nestmates.
- **Removed:** Java EE modules (JAXB, JAX-WS, JAF, Common Annotations) and CORBA. This is *the* classic 8 → 11 migration breakage: `javax.xml.bind.JAXBException` at startup. Add the Jakarta artifacts explicitly.
- Epsilon GC, ZGC (experimental), **Flight Recorder open-sourced**, TLS 1.3.

### Java 12–16
- **Switch expressions** (14): arrow labels, `yield`, exhaustiveness.
- **Helpful NullPointerExceptions** (14, on by default from 15).
- **Text blocks** (15) ([Chapter 03](03-strings.md#9-text-blocks-)).
- **Records** (16) ([Chapter 02](02-oop-and-object-model.md#12-records-)).
- **Pattern matching for `instanceof`** (16):
  ```java
  if (obj instanceof String s && !s.isBlank()) { use(s); }   // no cast needed; s is in scope where it's definitely matched
  ```
- `Stream.toList()`, `Stream.mapMulti` (16), `Collectors.teeing` (12).
- **CMS removed** (14). ZGC and Shenandoah production-ready (15). **Biased locking disabled** (15). Nashorn removed (15). Hidden classes (15).
- **JDK internals strongly encapsulated by default** (16), then **strictly** in 17 (`--illegal-access` gone).

### Java 17 (2021, LTS)
- **Sealed classes** ([Chapter 02](02-oop-and-object-model.md#13-sealed-types--algebraic-data-types-)).
- **Strong encapsulation of JDK internals** (JEP 403): reflective access to `sun.*` and `java.*` internals needs `--add-opens`.
- `RandomGenerator` API, always-strict floating point, context-specific deserialization filters (JEP 415).
- **Security Manager deprecated for removal** (JEP 411).

---

## 5. Java 18 → 21: Loom and Amber land 🟡→🔴

### Java 18–20
- **UTF-8 by default** (18, JEP 400). `jwebserver`, a tiny static file server for testing (18). `@snippet` in Javadoc (18).
- **Finalization deprecated for removal** (18, JEP 421).
- Core reflection reimplemented on method handles (18).
- Previews and incubators: virtual threads (19), record patterns (19), structured concurrency (19), scoped values (20).

### Java 21 (2023, LTS): the big one
| Feature | JEP | One-liner |
|---|---|---|
| **Virtual threads** | 444 | Millions of cheap threads, so blocking code scales. See [Chapter 12](12-concurrency-advanced-juc-loom.md) |
| **Pattern matching for `switch`** | 441 | Type patterns, guards, `case null`, exhaustiveness (§6) |
| **Record patterns** | 440 | Deconstruct records inside patterns, with nesting (§6) |
| **Sequenced collections** | 431 | `getFirst/getLast/reversed` everywhere ([Chapter 06](06-collections.md#13-sequenced-collections-java-21-)) |
| **Generational ZGC** | 439 | Sub-millisecond pauses with much better throughput ([Chapter 10](10-memory-and-garbage-collection.md)) |
| Key Encapsulation Mechanism API | 452 | Foundation for post-quantum crypto |
| Prepare to disallow dynamic agent loading | 451 | Warnings when agents attach at runtime |

Also in 21: `String.indexOf(ch, from, to)`, `StringBuilder.repeat`, `Character.isEmoji`, `Math.clamp`, and `HttpClient` implementing `AutoCloseable`.

> [!NOTE]
> **String Templates** (`STR."Hello \{name}"`) were previewed in 21 and 22, then **withdrawn in 23** for redesign. If someone mentions them as a current feature, they're out of date.

---

## 6. Pattern matching: the full picture 🔴

Pattern matching is Project Amber's biggest contribution. Combined with records and sealed types, it lets Java model data the way ML-family languages do.

```java
sealed interface Expr permits Num, Add, Mul, Neg {}
record Num(int value)            implements Expr {}
record Add(Expr left, Expr right) implements Expr {}
record Mul(Expr left, Expr right) implements Expr {}
record Neg(Expr operand)          implements Expr {}

static int eval(Expr e) {
    return switch (e) {                                   // exhaustive over the sealed hierarchy: no default
        case Num n             -> n.value();
        case Add(var l, var r) -> eval(l) + eval(r);      // record pattern: deconstruct in place
        case Mul(var l, var r) -> eval(l) * eval(r);
        case Neg(var x)        -> -eval(x);
    };
}

static Expr simplify(Expr e) {
    return switch (e) {
        case Mul(Num(var n), var x) when n == 1 -> simplify(x);   // NESTED pattern + guard
        case Mul(var x, Num(var n)) when n == 1 -> simplify(x);
        case Mul(Num(var n), var x) when n == 0 -> new Num(0);
        case Add(Num(var n), var x) when n == 0 -> simplify(x);
        case Neg(Neg(var x))                    -> simplify(x);   // double negation
        case Add(var l, var r)                  -> new Add(simplify(l), simplify(r));
        case Mul(var l, var r)                  -> new Mul(simplify(l), simplify(r));
        case Neg(var x)                         -> new Neg(simplify(x));
        case Num n                              -> n;
    };
}
```

**The rules, precisely:**
- **Type patterns** (`case Num n`) test and bind in one step.
- **Record patterns** (`case Add(var l, var r)`) deconstruct through the record's accessors, and they nest.
- **Guards** (`when cond`) refine a pattern. A guarded case **doesn't count towards exhaustiveness**.
- **Dominance:** a more general pattern can't come before a more specific one (`case Expr e` before `case Num n` won't compile). Order your cases from specific to general.
- **Exhaustiveness:** switch *expressions*, and any switch that uses patterns, must cover every possible value. With sealed hierarchies the compiler checks this, so no `default` is needed. Adding a new permitted subtype then makes every non-exhaustive switch fail to compile, which is the feature, not a bug.
- **`null` handling:** traditionally `switch (null)` throws NPE. With patterns you can write `case null ->` or `case null, default ->`. Without that case, it still throws.
- **Unnamed patterns and variables (`_`, Java 22):**
  ```java
  case Add(Num(var n), _) when n == 0 -> ...;               // don't care about the right side
  try { parse(s); } catch (NumberFormatException _) { return fallback; }
  for (var _ : items) count++;
  map.forEach((_, value) -> process(value));
  ```

**Primitive types in patterns** (*preview* in 23 through 27) extend patterns to primitives with safe-conversion semantics:
```java
// preview: --enable-preview
String describe(int status) {
    return switch (status) {
        case 200 -> "OK";
        case int c when c >= 500 -> "server error " + c;
        case int c -> "other " + c;
    };
}
if (someLong instanceof int i) { /* only if the value fits in an int without loss */ }
```

🧠 **Why this matters architecturally.** Pattern matching over sealed types is the modern alternative to the **Visitor pattern** ([Chapter 17](17-design-patterns.md)). Operations live in plain functions instead of `accept/visit` double dispatch, and the compiler guarantees every case is handled. The trade-off is that adding a new *operation* is easy (write a function), while adding a new *variant* means touching every switch, though the compiler tells you where.

---

## 7. Java 22 → 25: polishing and the 2025 LTS 🔴

### Java 22 (2024)
- **Foreign Function & Memory API final** (JEP 454): call native code and manage off-heap memory **without JNI**:
  ```java
  Linker linker = Linker.nativeLinker();
  MethodHandle strlen = linker.downcallHandle(
          linker.defaultLookup().find("strlen").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

  try (Arena arena = Arena.ofConfined()) {                  // deterministic, scoped off-heap memory
      MemorySegment cString = arena.allocateFrom("Hello");  // a NUL-terminated C string
      long len = (long) strlen.invokeExact(cString);         // 5
  }                                                          // memory freed here, with no GC involved
  ```
- **Unnamed variables and patterns** (JEP 456), **multi-file source programs** (JEP 458), **region pinning for G1** (JEP 423, so JNI critical regions no longer stall GC).

### Java 23 (2024)
- **Markdown documentation comments** (`///`) (JEP 467).
- **Generational ZGC becomes the default ZGC mode** (JEP 474).
- `sun.misc.Unsafe` memory-access methods **deprecated for removal** (JEP 471).

### Java 24 (2025)
- **Stream Gatherers final** (JEP 485) ([Chapter 07](07-lambdas-streams-functional.md#12-stream-gatherers-java-24-)).
- **Class-File API final** (JEP 484): a JDK-native replacement for ASM in tools ([Chapter 14](14-reflection-annotations-proxies.md)).
- **Synchronize virtual threads without pinning** (JEP 491): `synchronized` and `Object.wait()` no longer pin a virtual thread to its carrier. This removed the single biggest caveat of Java 21's virtual threads.
- **AOT class loading & linking** (JEP 483): the first Project Leyden delivery. A training run records loaded and linked classes into an AOT cache, so startup is faster.
- **Quantum-resistant crypto:** ML-KEM (JEP 496) and ML-DSA (JEP 497).
- **Security Manager permanently disabled** (JEP 486).
- Warnings for JNI use (JEP 472) and for `Unsafe` memory access (JEP 498).
- Compact object headers (experimental, JEP 450), generational Shenandoah (experimental, JEP 404). ZGC's non-generational mode removed (JEP 490).

### Java 25 (2025, LTS)
| Feature | JEP | Status |
|---|---|---|
| **Scoped values** | 506 | Final |
| **Module import declarations** | 511 | Final |
| **Compact source files & instance `main`** | 512 | Final |
| **Flexible constructor bodies** | 513 | Final |
| **Compact object headers** | 519 | Product feature (opt-in: `-XX:+UseCompactObjectHeaders`) |
| **Generational Shenandoah** | 521 | Product feature |
| AOT command-line ergonomics & method profiling | 514, 515 | Final (Leyden) |
| Key Derivation Function API | 510 | Final |
| JFR: CPU-time profiling (exp.), cooperative sampling, method timing & tracing | 509, 518, 520 | — |
| Structured concurrency | 505 | 5th preview |
| Stable values (later renamed lazy constants) | 502 | Preview |
| Primitive types in patterns | 507 | 3rd preview |
| PEM encodings | 470 | Preview |
| 32-bit x86 port removed | 503 | — |

**Scoped values**: immutable, inheritable, bounded-lifetime context. The modern replacement for many `ThreadLocal` uses:
```java
static final ScopedValue<RequestContext> CONTEXT = ScopedValue.newInstance();

void handle(Request req) {
    ScopedValue.where(CONTEXT, new RequestContext(req.traceId(), req.user()))
               .run(() -> service.process(req));          // bound only for the duration of run()
}

// anywhere deeper in the call stack, with no parameter threading:
String traceId = CONTEXT.get().traceId();
```
Unlike `ThreadLocal`, a scoped value is **immutable** (no `set`), it's **automatically unbound** when the scope exits (no leaks in pools), and it's **cheaply inherited** by child threads in structured concurrency. With millions of virtual threads, those properties matter ([Chapter 12](12-concurrency-advanced-juc-loom.md)).

**Flexible constructor bodies**: statements before `super(...)`:
```java
public class PositiveBigInteger extends BigInteger {
    public PositiveBigInteger(long value) {
        if (value <= 0) throw new IllegalArgumentException("non-positive: " + value);  // validate BEFORE super
        super(Long.toString(value));
    }
}

class Child extends Parent {
    private final String name;
    Child(String name) {
        this.name = Objects.requireNonNull(name);   // initialise own fields first…
        super();                                    // …so Parent's constructor calling an override sees them set
    }
}
```
The *prologue* (code before `super`) can't read `this` or call instance methods, but it may **assign** the class's own fields. This finally lets you fix the "overridable method called from constructor sees null" trap ([Chapter 01 §17](01-java-platform-fundamentals.md#17-static-class-initialization--init-order-)).

**Compact source files + module imports**:
```java
// Fetch.java: run with `java Fetch.java`
import module java.net.http;          // imports every package that java.net.http exports

void main() throws Exception {
    var client = HttpClient.newHttpClient();
    var response = client.send(
            HttpRequest.newBuilder(URI.create("https://example.com")).build(),
            HttpResponse.BodyHandlers.ofString());
    IO.println(response.statusCode());
}
```
A compact source file implicitly imports the `java.base` module, so `java.util`, `java.io`, `java.net` and the rest are available. This makes Java a reasonable scripting language for ops tasks and teaching.

**Structured concurrency** (*preview*; API as of Java 25. It's still evolving in 26 and 27):
```java
// --enable-preview
Response handle() throws InterruptedException {
    try (var scope = StructuredTaskScope.open()) {        // default policy: all must succeed; fail fast otherwise
        Subtask<User>  user  = scope.fork(() -> findUser());
        Subtask<Order> order = scope.fork(() -> fetchOrder());
        scope.join();                                     // waits for both; throws if either failed (the other is cancelled)
        return new Response(user.get(), order.get());
    }                                                     // guaranteed: no subtask outlives this block
}
```

---

## 8. Java 26 & 27: where we are now 🔴→🟣

### Java 26 (March 2026): 10 JEPs
| JEP | Feature | Why you care |
|---|---|---|
| 500 | **Prepare to Make Final Mean Final** | Warnings when deep reflection mutates `final` fields. A future release will deny it by default, which affects serialization and DI libraries that write into `final` fields |
| 504 | Remove the Applet API | Cleanup |
| 516 | **AOT Object Caching with Any GC** | Leyden's AOT cache now works with every GC, including ZGC |
| 517 | **HTTP/3 for the HTTP Client API** | QUIC-based HTTP/3 with minimal code changes |
| 522 | **G1: Improve Throughput by Reducing Synchronization** | Less overhead between application and GC threads |
| 524 | PEM Encodings (2nd preview) | Read and write keys and certificates in PEM without third-party libraries |
| 525 | Structured Concurrency (6th preview) | Still converging |
| 526 | Lazy Constants (2nd preview) | Renamed from "stable values" |
| 529 | Vector API (11th incubator) | SIMD; waiting on Valhalla |
| 530 | Primitive Types in Patterns (4th preview) | — |

**Lazy constants**, deferred immutability (*preview*):
```java
class OrderController {
    private final LazyConstant<Logger> logger =
            LazyConstant.of(() -> Logger.create(OrderController.class));   // computed at most once, on first get()

    void submit(Order o) { logger.get().info("submitting {}", o.id()); }
}
```
It's the JDK-blessed replacement for double-checked locking and holder-class idioms. Once set, the JIT can treat the value as a **true constant** and fold it, which a hand-written lazy field never gets.

### Java 27 (September 2026): 9 JEPs, current feature release
| JEP | Feature | Status |
|---|---|---|
| 523 | **Make G1 the Default GC in All Environments** (including small, "client-class" machines and containers, where Serial used to be picked) | Final |
| 534 | **Compact Object Headers by Default**: 8-byte headers for every object, no flag needed | Final |
| 527 | **Post-Quantum Hybrid Key Exchange for TLS 1.3** | Final |
| 536 | **JFR In-Process Data Redaction** (scrub sensitive data such as command-line arguments from recordings) | Final |
| 531 | Lazy Constants | 3rd preview |
| 532 | Primitive Types in Patterns | 5th preview |
| 533 | Structured Concurrency | 7th preview |
| 538 | PEM Encodings | 3rd preview |
| 537 | Vector API | 12th incubator |

🧠 **What 27 means operationally:** after upgrading, **heap usage typically drops** (compact headers save 4 bytes per object), and **small containers that used to get Serial GC by default now get G1**. Both are wins, but both change memory and latency baselines. Re-baseline your dashboards and alerts after the upgrade instead of being surprised by them.

---

## 9. What's next: Valhalla, Leyden, Babylon 🟣

| Project | Goal | Status (late 2026) |
|---|---|---|
| **Valhalla** | **Value classes** (identity-free objects the JVM can flatten into arrays and fields, "codes like a class, works like an int"), null-restricted types, eventually specialized generics (`List<int>`) | JEP 401 (value classes) has been in early-access builds. Oracle highlighted Valhalla in JDK 28 early access |
| **Leyden** | Faster startup and warmup via AOT caches from training runs | Shipping incrementally since 24 (483, 514, 515, 516) |
| **Loom** | Virtual threads ✅, scoped values ✅, structured concurrency | Structured concurrency still in preview; JEP 543 proposed for JDK 28 |
| **Panama** | FFM ✅, Vector API | Vector API waiting on Valhalla |
| **Amber** | Language productivity: records ✅, patterns ✅, sealed ✅ | Primitive patterns in preview; more pattern features coming |
| **Babylon** | Code reflection, to run Java on GPUs and ML runtimes | Research |

Value classes are worth understanding even before they ship. They're why wrapper classes are documented as "value-based", why `new Integer(…)` is deprecated for removal, and why you should never synchronize on an `Integer` or use `==` identity on value-based types.

---

## 10. Migration playbook: 8 → 11 → 17 → 21 → 25 🟣→🔵

| Hop | What breaks | Fix |
|---|---|---|
| **8 → 11** | Removed Java EE modules (`javax.xml.bind`, `javax.annotation`, JAX-WS), CORBA. Illegal reflective access warnings. Nashorn deprecated | Add Jakarta/standalone dependencies. Upgrade libraries (Lombok, Mockito, ByteBuddy, Spring) to versions aware of modern JDKs |
| **11 → 17** | **Strong encapsulation** (JEP 403): reflective access into JDK internals fails | Upgrade the offending libraries; use `--add-opens` only as a temporary, documented workaround |
| **17 → 21** | Usually smooth. Watch dynamic agent warnings and libraries that pin virtual threads | Adopt virtual threads deliberately ([Chapter 12](12-concurrency-advanced-juc-loom.md)) |
| **21 → 25** | **Security Manager can't be enabled** (24): code calling `System.setSecurityManager` throws. `Unsafe` memory-access and JNI warnings. 32-bit x86 gone | Remove the Security Manager dependency. Plan to migrate off `Unsafe` (VarHandle/FFM) |
| **25 → 27** | G1 now default on small machines. Compact headers on by default (native code or agents that assume header layout). Final-field-mutation warnings (JEP 500) | Re-baseline memory and GC metrics. Upgrade libraries that write to `final` fields reflectively |

**The process** ([Chapter 01 §20](01-java-platform-fundamentals.md#20-staff-lens-running-java-across-an-organisation-)): toolchain first (`--release` stays old), then the runtime, then the language level, rolled out by risk tier with before/after dashboards. Run `jdeps --jdk-internals` and the `jdeprscan` tool against your jars to find landmines before they go off.

---

## 11. Interview questions

<details>
<summary><b>Q1. 🟢 What were the most important features of Java 8?</b></summary>

Lambdas and method references, the Streams API, default and static interface methods, `Optional`, the `java.time` API, `CompletableFuture`, and the replacement of PermGen with Metaspace. Together they enabled a functional style and fixed the long-standing date/time and async problems.
</details>

<details>
<summary><b>Q2. 🟡 <code>Instant</code> vs <code>LocalDateTime</code> vs <code>ZonedDateTime</code>: when do you use each?</b></summary>

`Instant` is a point on the UTC timeline: use it for event timestamps and storage. `LocalDateTime` is a wall-clock value with no zone: use it for things like store hours. It can't identify a moment and is ambiguous during DST changes. `ZonedDateTime` combines local time with zone rules, for user-facing scheduling and DST-aware arithmetic. For *future* local events, store `LocalDateTime` + `ZoneId` rather than an `Instant`, because time-zone rules can change before the event.
</details>

<details>
<summary><b>Q3. 🟡 What's the difference between <code>Duration</code> and <code>Period</code>?</b></summary>

`Duration` is exact elapsed time (seconds and nanos). `Period` is a calendar amount (years, months, days). Across a DST transition, `zdt.plus(Period.ofDays(1))` keeps the wall-clock time, while `zdt.plus(Duration.ofDays(1))` adds exactly 24 hours and the wall-clock time shifts by an hour.
</details>

<details>
<summary><b>Q4. 🟡 What broke for most teams moving from Java 8 to 11, and from 11 to 17?</b></summary>

8 → 11: the Java EE and CORBA modules (JAXB, JAX-WS, `javax.annotation`) were removed from the JDK, so applications failed with `ClassNotFoundException`/`NoClassDefFoundError` until they added explicit dependencies. 11 → 17: JDK internals became strictly encapsulated (JEP 403), so libraries doing deep reflection into `java.*`/`sun.*` failed with `InaccessibleObjectException` until they were upgraded or given `--add-opens`.
</details>

<details>
<summary><b>Q5. 🔴 Explain pattern matching for <code>switch</code>: exhaustiveness, dominance, guards, null.</b></summary>

Cases can be type patterns (`case Circle c`) and record patterns (`case Point(var x, var y)`, nestable), optionally refined with `when` guards. Switches using patterns must be exhaustive. Over sealed hierarchies the compiler verifies coverage without a `default`, and guarded cases don't count towards coverage. Cases are checked top-down, and a case dominated by an earlier, more general one won't compile. `null` throws NPE unless there's an explicit `case null`. Since Java 22, `_` marks unnamed patterns and variables.
</details>

<details>
<summary><b>Q6. 🔴 What problem do scoped values solve that <code>ThreadLocal</code> doesn't?</b></summary>

`ThreadLocal` is mutable, has unbounded lifetime (it leaks in thread pools unless removed), and copying it to child threads (`InheritableThreadLocal`) is expensive. That's a bad fit for millions of virtual threads. Scoped values are immutable, bound for a dynamic scope only (automatically unbound when `run`/`call` returns), cheap to read, and efficiently inherited by structured-concurrency subtasks. They're final in Java 25.
</details>

<details>
<summary><b>Q7. 🔴 What did JEP 491 (Java 24) change for virtual threads, and why does it matter for adoption?</b></summary>

Before Java 24, a virtual thread blocking inside `synchronized` (or in `Object.wait()`) *pinned* its carrier thread, so legacy code and libraries full of `synchronized` could exhaust the small carrier pool and deadlock or stall. JEP 491 let virtual threads release the carrier while blocked on monitors. Java 25 is the first LTS with this fix, which makes it the safer target for large-scale virtual-thread adoption. Pinning still happens for native frames and some class-initialization cases.
</details>

<details>
<summary><b>Q8. 🔴 What are flexible constructor bodies and what bug do they help prevent?</b></summary>

Since Java 25 (JEP 513), a constructor can run statements before `super(...)`/`this(...)`, as long as they don't read `this`. That allows argument validation before delegating, and *assigning the subclass's own fields* before the superclass constructor runs. That's exactly what's needed when a superclass constructor calls an overridable method that reads subclass state (which previously saw `null`/0).
</details>

<details>
<summary><b>Q9. 🟣 What changed in Java 27 that could affect your production dashboards?</b></summary>

Compact object headers are on by default (JEP 534), so heap usage per object drops and GC behaviour shifts. G1 is now the default GC everywhere (JEP 523), so small containers that previously got Serial GC now get G1, with different pause and footprint characteristics. Re-baseline heap, RSS, GC pause and CPU metrics after the upgrade, and check any native code, agents or tools that assumed the old header layout.
</details>

<details>
<summary><b>Q10. 🔵 How would you justify upgrading a fleet from Java 21 to 25?</b></summary>

Risk reduction and capability. JEP 491 removes virtual-thread pinning on `synchronized`, which unblocks safe Loom adoption. Scoped values, flexible constructors and compact headers (opt-in, with measurable heap savings) arrive, along with Leyden AOT improvements for startup, generational Shenandoah, newer JFR profiling features, and 25 being the current LTS with a long support window. Costs: the Security Manager is gone, plus new warnings for `Unsafe` and JNI. The plan is toolchain → runtime → language level, by risk tier, with before/after SLO dashboards.
</details>

<details>
<summary><b>Q11. 🟡 Are String Templates available in Java?</b></summary>

No. They were previewed in Java 21 and 22 and withdrawn in Java 23 for redesign. For now, use `formatted`/`String.format`, `MessageFormat`, or concatenation. For SQL, use bind parameters. Templates would never have been a replacement for those anyway.
</details>

<details>
<summary><b>Q12. 🔴 What is Project Valhalla and why are wrapper classes "value-based" today?</b></summary>

Valhalla adds value classes: objects without identity that the JVM can flatten into fields and arrays (no headers, no pointers), improving memory density and cache locality, and eventually enabling generics over primitives. To prepare, the JDK designated classes like `Integer`, `Optional` and `LocalDate` as *value-based*: you shouldn't rely on their identity (`==`, `synchronized`, identity hash), and the wrapper constructors are deprecated for removal. Code that synchronizes on an `Integer` already gets warnings.
</details>

---

## 12. References
- [OpenJDK JEP index](https://openjdk.org/jeps/0) · [JDK 25](https://openjdk.org/projects/jdk/25/) · [JDK 26](https://openjdk.org/projects/jdk/26/) · [JDK 27](https://openjdk.org/projects/jdk/27/) project pages
- Key JEPs: [441](https://openjdk.org/jeps/441) · [440](https://openjdk.org/jeps/440) · [444](https://openjdk.org/jeps/444) · [491](https://openjdk.org/jeps/491) · [506](https://openjdk.org/jeps/506) · [512](https://openjdk.org/jeps/512) · [513](https://openjdk.org/jeps/513) · [519](https://openjdk.org/jeps/519) · [523](https://openjdk.org/jeps/523) · [534](https://openjdk.org/jeps/534)
- Brian Goetz, *Data-Oriented Programming in Java* (InfoQ) — records + sealed + patterns as a design style
- Stephen Colebourne's blog on `java.time` design and pitfalls

---

[⬅️ Prev: 07 · Lambdas & Streams](07-lambdas-streams-functional.md) · [🏠 Index](README.md) · [Next: 09 · JVM Internals ➡️](09-jvm-internals.md)
