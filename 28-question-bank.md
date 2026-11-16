[⬅️ Prev: 27 · Staff & Architect Playbook](27-staff-architect-interview-playbook.md) · [🏠 Index](README.md) · [Next: 29 · Reactive Java ➡️](29-reactive-java-reactor-webflux.md)

# 28 · The Question Bank

> *Rapid-fire revision across the whole book. Each answer is the version you'd give in the first 30–60 seconds of an interview, before the follow-ups. If an answer surprises you, the linked chapter has the depth.*

---

## How to use this bank

1. **Answer out loud before you expand.** Give yourself 60 seconds per question.
2. **Mark each one:** ✅ confident · 🟨 shaky · ❌ missed.
3. **Re-read the linked chapter** for every 🟨 and ❌, then retry those questions **two days later** and again **a week later**. Spaced repetition is what turns recognition into recall.
4. **Calibrate by level:**

| Target role | You should be confident on |
|---|---|
| Mid-level | All 🟢, most 🟡 |
| Senior | All 🟢/🟡, most 🔴 |
| Staff | All 🔴, with structured answers to 🟣 |
| Architect | Everything, plus the trade-off follow-ups in each chapter's own question set |

## Contents
- [Part I · Language Foundations](#part-i--language-foundations) (Q1–Q35)
- [Part II · The JVM](#part-ii--the-jvm) (Q36–Q47)
- [Part III · Concurrency](#part-iii--concurrency) (Q48–Q61)
- [Part IV · Platform APIs](#part-iv--platform-apis) (Q62–Q72)
- [Part V · Design](#part-v--design) (Q73–Q80)
- [Part VI · Ecosystem](#part-vi--ecosystem) (Q81–Q95)
- [Part VII · Production](#part-vii--production) (Q96–Q105)
- [Part VIII · Architecture](#part-viii--architecture) (Q106–Q117)
- [Part IX · Interview Mastery](#part-ix--interview-mastery) (Q118–Q123)
- [Part X · Specialist Topics](#part-x--specialist-topics) (Q124–Q136)
- [Part XI · Practice Lab](#part-xi--practice-lab) (Q137–Q143)
- [Part XII · Data & AI](#part-xii--data--ai) (Q144–Q159)

---

## Part I · Language Foundations

<details>
<summary><b>Q1. 🟢 JDK vs JRE vs JVM?</b></summary>

The **JVM** executes bytecode: it's a specification, and HotSpot is the main implementation. The **JRE** is the JVM plus the class libraries needed to run programs. The **JDK** adds the development tools (`javac`, `jar`, `jdeps`, `jlink`, `jcmd`, `jfr`). Oracle stopped shipping a standalone JRE with JDK 11; today you build a trimmed runtime with `jlink` (some vendors still publish JRE builds). → [Ch 01](01-java-platform-fundamentals.md)
</details>

<details>
<summary><b>Q2. 🟢 Is Java pass-by-value or pass-by-reference?</b></summary>

Always pass-by-value. For objects, the value copied is the *reference*. Reassigning the parameter inside a method doesn't affect the caller's variable, but mutating the object it points to is visible to the caller. → [Ch 01](01-java-platform-fundamentals.md)
</details>

<details>
<summary><b>Q3. 🟡 Why is <code>0.1 + 0.2 != 0.3</code>, and how should you represent money?</b></summary>

IEEE 754 binary floating point can't represent 0.1 exactly, so tiny representation errors accumulate. For money, use `BigDecimal` (created from a `String` or `BigDecimal.valueOf`, never `new BigDecimal(0.1)`) with an explicit scale and rounding mode, or `long` minor units with a currency. → [Ch 01](01-java-platform-fundamentals.md)
</details>

<details>
<summary><b>Q4. 🟡 What does <code>(int) 3_000_000_000L</code> evaluate to?</b></summary>

`-1294967296`. A narrowing primitive conversion keeps only the low 32 bits (3,000,000,000 − 2³² = −1,294,967,296), and no exception is thrown. Use `Math.toIntExact` when you want overflow to fail loudly. → [Ch 01](01-java-platform-fundamentals.md)
</details>

<details>
<summary><b>Q5. 🔴 What are the class-file major versions for Java 21 and 25, and why do they matter?</b></summary>

65 for Java 21 and 69 for Java 25 (52 = Java 8, 55 = 11, 61 = 17, 71 = 27). An older JVM rejects newer class files with `UnsupportedClassVersionError`. Compile with `--release N` to target an older runtime. Unlike `-source`/`-target`, it also checks that you only use APIs that exist in that release. → [Ch 01](01-java-platform-fundamentals.md)
</details>

<details>
<summary><b>Q6. 🟢 Overloading vs overriding: when is each resolved?</b></summary>

**Overloading** is resolved at compile time from the *static* types of the arguments, in three phases: without boxing or varargs, then with boxing, then with varargs. **Overriding** is resolved at run time from the *dynamic* type of the receiver (virtual dispatch). → [Ch 02](02-oop-and-object-model.md)
</details>

<details>
<summary><b>Q7. 🟡 State the <code>equals</code>/<code>hashCode</code> contract and what breaks if you violate it.</b></summary>

`equals` must be reflexive, symmetric, transitive and consistent, with `x.equals(null) == false`, and equal objects **must** have equal hash codes. Violate it and hash-based collections misbehave: lookups miss, duplicates appear, and mutating a field used in `hashCode` after insertion "loses" the entry. → [Ch 02](02-oop-and-object-model.md)
</details>

<details>
<summary><b>Q8. 🟡 Why can't you subclass a value class, add a field, and keep <code>equals</code> correct?</b></summary>

With `instanceof`-based equality you break symmetry (point equals colorPoint, but not the reverse). With `getClass()`-based equality you break Liskov substitution (a subclass instance never equals its parent). No implementation satisfies the contract and substitutability together. Favour composition, or make value classes final (records are). → [Ch 02](02-oop-and-object-model.md)
</details>

<details>
<summary><b>Q9. 🟡 What does a record give you, and what doesn't it?</b></summary>

It gives you private final fields, a canonical constructor, accessors, and `equals`/`hashCode`/`toString` derived from the components; the class is implicitly final. It does **not** give deep immutability: a `List` component stays mutable unless you defensively copy it in a compact constructor (`items = List.copyOf(items)`). You also can't declare extra instance fields. → [Ch 02](02-oop-and-object-model.md)
</details>

<details>
<summary><b>Q10. 🔴 What's the payoff of sealed interfaces + records + pattern matching?</b></summary>

Algebraic data types in Java. A sealed hierarchy is closed, so a `switch` over it can be **exhaustive without a `default`**, and adding a new subtype turns every incomplete switch into a compile error, instead of a runtime surprise. Records add deconstruction patterns for concise, type-safe handling. → [Ch 02](02-oop-and-object-model.md)
</details>

<details>
<summary><b>Q11. 🟢 Why are Strings immutable?</b></summary>

Security (class names, file paths and URLs can't change after being checked), thread safety without locks, a cacheable `hashCode` (strings make good map keys), and safe sharing through the string pool. → [Ch 03](03-strings.md)
</details>

<details>
<summary><b>Q12. 🟡 <code>new String("a") == "a"</code>? And <code>"a" + "b" == "ab"</code>?</b></summary>

The first is `false`: `new` creates a distinct heap object, while the literal comes from the pool. The second is `true`, because two literals are constant-folded at compile time into one interned `"ab"`. Concatenation involving a non-final variable happens at run time and produces a new object. → [Ch 03](03-strings.md)
</details>

<details>
<summary><b>Q13. 🟡 What are compact strings?</b></summary>

Since JDK 9 (JEP 254), a `String` stores its characters in a `byte[]` plus a *coder* flag: LATIN1 (1 byte per char) when every character fits, otherwise UTF16 (2 bytes). That roughly halves the memory of mostly-ASCII text without any API change. → [Ch 03](03-strings.md)
</details>

<details>
<summary><b>Q14. 🔴 What is ReDoS, and how do you defend against it in Java?</b></summary>

Regular-expression denial of service: patterns with nested or overlapping quantifiers can backtrack exponentially in a backtracking engine like `java.util.regex`. In our JDK 21 measurement, a 10-word input took about 66 s. Defences: avoid nested quantifiers, use possessive quantifiers or atomic groups, bound the input length, add a timeout by wrapping the input in a `CharSequence`, and never compile untrusted patterns (or use a linear-time engine such as RE2/J). → [Ch 03](03-strings.md)
</details>

<details>
<summary><b>Q15. 🟢 Checked vs unchecked exceptions: when do you use each?</b></summary>

Checked exceptions (subclasses of `Exception` that aren't `RuntimeException`) must be declared or caught. Unchecked ones (`RuntimeException`, `Error`) don't have to be. Use unchecked exceptions for programming errors and for most domain failures in modern APIs. Reserve checked exceptions for genuinely recoverable conditions that the caller must consciously handle. → [Ch 04](04-exceptions.md)
</details>

<details>
<summary><b>Q16. 🟡 In try-with-resources, what happens if both the body and <code>close()</code> throw?</b></summary>

The body's exception propagates, and the `close()` exception is attached as a **suppressed** exception (`getSuppressed()`). Resources are closed in reverse order of declaration, and every resource is closed even if an earlier `close()` throws. → [Ch 04](04-exceptions.md)
</details>

<details>
<summary><b>Q17. 🔴 Why can stack traces disappear in production logs?</b></summary>

HotSpot's `OmitStackTraceInFastThrow` optimisation (on by default) replaces frequently thrown *implicit* exceptions in compiled code (NPE, `ArrayIndexOutOfBounds`, `ClassCast`, `Arithmetic`) with preallocated instances that have no stack trace. Look for the first occurrences earlier in the logs, or run with `-XX:-OmitStackTraceInFastThrow` while debugging. → [Ch 04](04-exceptions.md)
</details>

<details>
<summary><b>Q18. 🟡 What happens with a <code>return</code> inside <code>finally</code>?</b></summary>

It overrides the value returned by `try`, and it **silently discards** any exception thrown in `try` or `catch`. Never return (or throw) from `finally`. → [Ch 04](04-exceptions.md)
</details>

<details>
<summary><b>Q19. 🟢 What is type erasure?</b></summary>

The compiler checks generic types, then erases them to their bounds (usually `Object`) and inserts casts. At run time, `List<String>` and `List<Integer>` are the same `List.class`, so you can't do `instanceof List<String>`, `new T()` or `T.class`. → [Ch 05](05-generics.md)
</details>

<details>
<summary><b>Q20. 🟡 Explain PECS.</b></summary>

*Producer Extends, Consumer Super.* If a parameter produces values you read, use `? extends T`. If it consumes values you write, use `? super T`. The canonical example is `Collections.copy(List<? super T> dest, List<? extends T> src)`. → [Ch 05](05-generics.md)
</details>

<details>
<summary><b>Q21. 🔴 Why can't you write <code>new T[n]</code> or <code>new List&lt;String&gt;[n]</code>?</b></summary>

Arrays are *reified* and *covariant*: they check element types at run time. Generic types are erased, so the array couldn't enforce its element type, which leads to heap pollution and `ClassCastException`s far from the cause. Use `List<List<String>>`, or `Array.newInstance(componentClass, n)` when you have a `Class<T>`. → [Ch 05](05-generics.md)
</details>

<details>
<summary><b>Q22. 🔴 What is a bridge method?</b></summary>

A synthetic method that `javac` generates so polymorphism survives erasure. When `IntNode extends Node<Integer>` overrides `setData(Integer)`, the erased parent method is `setData(Object)`, a different signature. So the compiler adds a bridge, `setData(Object)`, which casts to `Integer` and delegates, and calls through a `Node` reference still dispatch correctly. You can see it with `javap -p IntNode.class`. → [Ch 05](05-generics.md)
</details>

<details>
<summary><b>Q23. 🟢 <code>ArrayList</code> vs <code>LinkedList</code>?</b></summary>

`ArrayList` is a contiguous array: O(1) random access, amortised O(1) append, excellent cache locality. `LinkedList` allocates a node per element: O(n) indexed access and poor locality. `ArrayList` wins almost every real workload; for queues and stacks, use `ArrayDeque`. → [Ch 06](06-collections.md)
</details>

<details>
<summary><b>Q24. 🟡 How does <code>HashMap</code> pick a bucket, and when does it resize?</b></summary>

It spreads the hash with `h ^ (h >>> 16)`, then indexes with `(n - 1) & hash`; the table size is a power of two, so the mask replaces a modulo. It resizes (doubling) when `size > capacity × loadFactor` (0.75 by default), splitting each bucket into a "low" and a "high" list without rehashing. → [Ch 06](06-collections.md)
</details>

<details>
<summary><b>Q25. 🔴 When does a <code>HashMap</code> bin become a red-black tree?</b></summary>

When one bin reaches **8** nodes *and* the table capacity is at least **64**. With a smaller table, it resizes instead. A tree bin turns back into a list at **6** nodes. Trees bound worst-case lookups to O(log n) for colliding `Comparable` keys, which defends against hash-flooding attacks. → [Ch 06](06-collections.md)
</details>

<details>
<summary><b>Q26. 🟡 Fail-fast vs weakly consistent iterators?</b></summary>

Fail-fast iterators (`ArrayList`, `HashMap`) check a `modCount` and throw `ConcurrentModificationException`, on a best-effort basis, if the collection is structurally modified other than through the iterator. Iterators of concurrent collections never throw: `ConcurrentHashMap`'s are *weakly consistent* (they reflect some updates made during iteration), and `CopyOnWriteArrayList`'s iterate a *snapshot*. → [Ch 06](06-collections.md)
</details>

<details>
<summary><b>Q27. 🔴 How does <code>ConcurrentHashMap</code> (Java 8+) achieve thread safety?</b></summary>

It inserts into an empty bin with a CAS, locks only the bin's head node (`synchronized`) for other updates, and reads lock-free via volatile semantics. Resizing is cooperative: threads help transfer bins, leaving forwarding nodes behind. Size is counted with striped counter cells (the `LongAdder` idea). Null keys and values are forbidden, because `null` would make `get` ambiguous. → [Ch 06](06-collections.md)
</details>

<details>
<summary><b>Q28. 🟢 What makes a stream pipeline lazy?</b></summary>

Intermediate operations (`filter`, `map`) only describe stages. Nothing executes until a terminal operation (`collect`, `forEach`, `reduce`) pulls elements through, **one element at a time through all the stages**, which also enables short-circuiting (`findFirst`, `limit`). → [Ch 07](07-lambdas-streams-functional.md)
</details>

<details>
<summary><b>Q29. 🟡 How are lambdas compiled and linked?</b></summary>

The lambda body becomes a private synthetic method. The creation site becomes an `invokedynamic` instruction, bootstrapped by `LambdaMetafactory`, which on first execution spins a hidden class implementing the functional interface. Non-capturing lambdas yield a single reused instance; capturing lambdas allocate (unless escape analysis removes the allocation). → [Ch 07](07-lambdas-streams-functional.md)
</details>

<details>
<summary><b>Q30. 🟡 Name four <code>Optional</code> anti-patterns.</b></summary>

`Optional` as a field or parameter type. `get()` without a check. `Optional.of(possiblyNull)`. `orElse(expensiveCall())`, which evaluates eagerly (use `orElseGet`). Also: returning `null` from a method declared to return `Optional`, and using `Optional` for collections (return an empty collection instead). → [Ch 07](07-lambdas-streams-functional.md)
</details>

<details>
<summary><b>Q31. 🔴 When do parallel streams actually help?</b></summary>

When N × Q is large (many elements, or expensive work per element), the source splits well (arrays, `ArrayList`, ranges, not `LinkedList` or I/O), the operations are stateless and associative, there's no blocking I/O, and sharing the JVM-wide common `ForkJoinPool` is acceptable. Otherwise it's slower than sequential. Measure with JMH. → [Ch 07](07-lambdas-streams-functional.md)
</details>

<details>
<summary><b>Q32. 🟡 What's the current LTS, and the release cadence?</b></summary>

JDK 25 (September 2025) is the current LTS; the next is planned to be JDK 29 (September 2027). Feature releases come every six months, in March and September. JDK 27 shipped in September 2026. → [Ch 08](08-modern-java-8-to-27.md)
</details>

<details>
<summary><b>Q33. 🟡 What does pattern matching for <code>switch</code> add?</b></summary>

Type patterns (`case Circle c`), record deconstruction (`case Point(int x, int y)`), guards (`case X x when x.size() > 10`), explicit `case null`, and exhaustiveness checking over sealed hierarchies. The result is safer and more concise than `instanceof` chains or visitors. → [Ch 08](08-modern-java-8-to-27.md)
</details>

<details>
<summary><b>Q34. 🔴 Why prefer <code>ScopedValue</code> over <code>ThreadLocal</code> with virtual threads?</b></summary>

Scoped values (final in JDK 25) are immutable bindings with a bounded lifetime (`ScopedValue.where(KEY, v).run(...)`). They're cheap to inherit by structured-concurrency subtasks and can't leak beyond their scope. `ThreadLocal`s are mutable and unbounded in lifetime (a leak risk in pools), and copying inheritable ones across millions of virtual threads is expensive. → [Ch 08](08-modern-java-8-to-27.md)
</details>

<details>
<summary><b>Q35. 🟡 What do compact source files and instance main methods change?</b></summary>

Since JDK 25 (JEP 512), a source file can contain just `void main() { IO.println("Hi"); }`, with no class declaration and an implicit `import module java.base`. It lowers the ceremony for scripts and teaching. Real applications are unaffected. → [Ch 08](08-modern-java-8-to-27.md)
</details>

---

## Part II · The JVM

<details>
<summary><b>Q36. 🟢 What are the built-in class loaders, and how does delegation work?</b></summary>

**Bootstrap** loads core modules such as `java.base`, **Platform** loads the other JDK modules, and **Application** (system) loads the class path and module path. Each loader asks its parent first and loads a class itself only if the parent can't, so core classes can't be spoofed by application code. → [Ch 09](09-jvm-internals.md)
</details>

<details>
<summary><b>Q37. 🟡 What are the phases of class loading?</b></summary>

**Loading** (find the bytes, create the `Class`) → **Linking**: *verification* (bytecode safety), *preparation* (static fields get default values), *resolution* (symbolic references become direct, often lazily) → **Initialization**: `<clinit>` runs static initialisers exactly once, thread-safely, on first active use. → [Ch 09](09-jvm-internals.md)
</details>

<details>
<summary><b>Q38. 🟡 What's in a HotSpot object header, and how big is the smallest object?</b></summary>

An 8-byte mark word (identity hash, GC age, lock state) plus a 4-byte compressed class pointer: 12 bytes, with fields packed after it. Objects are aligned to 8 bytes, so the minimum is 16 bytes. Compact object headers (JEP 519, a product option in JDK 25 and the default from JDK 27) shrink the header to 8 bytes. → [Ch 09](09-jvm-internals.md)
</details>

<details>
<summary><b>Q39. 🔴 Explain tiered compilation.</b></summary>

Code starts in the interpreter (level 0). Hot methods are compiled by C1 with profiling (level 3), and the hottest get recompiled by C2 (level 4) using the collected profile to speculate (inlining, devirtualisation, eliminated branches). Levels 1–2 are C1 variants for special cases. If a speculative assumption breaks (a new subclass loads, an "impossible" branch runs), the JVM **deoptimises** back to the interpreter and recompiles later. → [Ch 09](09-jvm-internals.md)
</details>

<details>
<summary><b>Q40. 🔴 What is escape analysis, and what does it enable?</b></summary>

C2 proves whether an object can escape the method or thread that created it. If it can't, C2 can **scalar-replace** it (keep its fields in registers, so there's no heap allocation at all) and **elide its locks**. That's why many short-lived wrapper objects cost nothing once code is hot, and why microbenchmarks need care. → [Ch 09](09-jvm-internals.md)
</details>

<details>
<summary><b>Q41. 🔴 What is a safepoint, and why should you care?</b></summary>

A point where every Java thread is stopped in a known state, so the JVM can perform operations such as some GC phases, deoptimisation, class redefinition or thread dumps. **Time-to-safepoint** (waiting for a thread stuck in a long counted loop, say) adds latency that GC logs don't show. Diagnose with `-Xlog:safepoint` or JFR safepoint events. → [Ch 09](09-jvm-internals.md)
</details>

<details>
<summary><b>Q42. 🟢 Why are collectors generational?</b></summary>

The weak generational hypothesis: most objects die young. Collecting a small young generation often, by copying out the few survivors, is cheap, because the cost is proportional to *live* objects, not dead ones. Long-lived objects get promoted and are collected less often. → [Ch 10](10-memory-and-garbage-collection.md)
</details>

<details>
<summary><b>Q43. 🟡 What's a TLAB?</b></summary>

A thread-local allocation buffer: a private chunk of Eden per thread. Most allocations are just a pointer bump inside the TLAB, with no synchronisation, which is why allocation in Java is very cheap. Large objects may bypass the TLAB (and in G1, huge ones go to humongous regions). → [Ch 10](10-memory-and-garbage-collection.md)
</details>

<details>
<summary><b>Q44. 🟡 What are G1 humongous objects, and why do they hurt?</b></summary>

Objects of at least half a G1 region are allocated directly into contiguous old-generation regions. They're expensive to allocate, can fragment the heap, and can trigger premature collections. Fix them by avoiding giant arrays and buffers (chunk them), or by increasing `-XX:G1HeapRegionSize`. → [Ch 10](10-memory-and-garbage-collection.md)
</details>

<details>
<summary><b>Q45. 🔴 What does generational ZGC give you?</b></summary>

Concurrent marking, relocation and reference processing, using coloured pointers and load barriers, so pauses are typically well under a millisecond, regardless of heap size. It has been generational since JDK 21 (JEP 439); that mode became the default in JDK 23 and the only mode in JDK 24. You pay in CPU and memory headroom for the concurrent work. → [Ch 10](10-memory-and-garbage-collection.md)
</details>

<details>
<summary><b>Q46. 🔴 Distinguish heap OOM, Metaspace OOM, "unable to create native thread" and a container OOMKill.</b></summary>

*Java heap space:* live data exceeds `-Xmx`, usually a leak or an undersized heap. *Metaspace:* class metadata keeps growing, usually a classloader leak or runaway proxy or class generation. *Native thread:* OS thread limits or memory for thread stacks are exhausted. *OOMKilled:* the process RSS (heap + metaspace + code cache + thread stacks + direct buffers + native memory) exceeded the container limit, and the kernel killed it. The JVM never threw anything. → [Ch 10](10-memory-and-garbage-collection.md)
</details>

<details>
<summary><b>Q47. 🔴 How do you size the heap for a container?</b></summary>

Use `-XX:MaxRAMPercentage` (often 60–75%) rather than a fixed `-Xmx`, and leave headroom for non-heap memory. Measure the real footprint with Native Memory Tracking (`-XX:NativeMemoryTracking=summary`, then `jcmd <pid> VM.native_memory summary`) under production-like load, and set the container limit from that. → [Ch 10](10-memory-and-garbage-collection.md)
</details>

---

## Part III · Concurrency

<details>
<summary><b>Q48. 🟢 What states can a Java thread be in?</b></summary>

`NEW`, `RUNNABLE` (including running native code or blocking in I/O), `BLOCKED` (waiting to enter a monitor), `WAITING` (`wait`, `join`, `park` without a timeout), `TIMED_WAITING` (`sleep`, or a timed `wait`/`join`/`park`), and `TERMINATED`. → [Ch 11](11-concurrency-foundations-jmm.md)
</details>

<details>
<summary><b>Q49. 🟡 What does <code>volatile</code> guarantee, and what doesn't it?</b></summary>

It guarantees visibility and ordering: a write to a volatile field *happens-before* every subsequent read of it, and surrounding accesses can't be reordered across it in ways that break that. It does **not** make compound actions atomic, so `count++` on a volatile still loses updates. Use atomics or locks for those. → [Ch 11](11-concurrency-foundations-jmm.md)
</details>

<details>
<summary><b>Q50. 🟡 Name the main happens-before rules.</b></summary>

Program order within a thread. Monitor unlock → subsequent lock of the same monitor. Volatile write → subsequent read of the same field. `Thread.start()` → actions in the started thread. Actions in a thread → another thread's successful `join()` on it. Constructor completion → final-field visibility for properly published objects. And transitivity across all of these. → [Ch 11](11-concurrency-foundations-jmm.md)
</details>

<details>
<summary><b>Q51. 🔴 Why is double-checked locking broken without <code>volatile</code>?</b></summary>

The write of the reference can become visible before the writes made inside the constructor (reordering is allowed), so another thread can see a non-null but partially constructed object. Making the field `volatile` fixes it. The holder-class idiom (lazy initialisation through class initialisation) avoids the problem entirely. → [Ch 11](11-concurrency-foundations-jmm.md)
</details>

<details>
<summary><b>Q52. 🔴 What is safe publication, and how do you achieve it?</b></summary>

Making an object *and its state* correctly visible to other threads. Options: initialise it in a static initialiser; store it in a `volatile` field or an `AtomicReference`; store it in a field guarded by a lock; put it into a concurrent collection; or make it properly immutable with all fields `final` (and don't let `this` escape the constructor). → [Ch 11](11-concurrency-foundations-jmm.md)
</details>

<details>
<summary><b>Q53. 🟡 Why must <code>wait()</code> be called in a loop, inside <code>synchronized</code>?</b></summary>

Inside `synchronized`, because you must own the monitor, or you get `IllegalMonitorStateException`. In a loop, because of spurious wakeups, and because the condition may change between the `notify` and the moment the woken thread reacquires the lock. Always recheck the condition: `while (!condition) wait();`. → [Ch 11](11-concurrency-foundations-jmm.md)
</details>

<details>
<summary><b>Q54. 🔴 How do you diagnose and fix a deadlock in production?</b></summary>

Take a thread dump (`jcmd <pid> Thread.print`). HotSpot reports "Found one Java-level deadlock" with the cycle of threads and locks. Health checks can call `ThreadMXBean.findDeadlockedThreads()`. Fix it with a global lock ordering, `tryLock` with timeouts, or by shrinking lock scopes so locks are never nested. → [Ch 11](11-concurrency-foundations-jmm.md)
</details>

<details>
<summary><b>Q55. 🟡 What is AQS?</b></summary>

`AbstractQueuedSynchronizer`: an `int` state, a FIFO queue of waiting threads (a CLH-queue variant), and park/unpark-based blocking. `ReentrantLock`, `Semaphore`, `CountDownLatch` and `ReentrantReadWriteLock` are built on it. A synchronizer implements `tryAcquire`/`tryRelease` (or the shared variants) on top of the state. → [Ch 12](12-concurrency-advanced-juc-loom.md)
</details>

<details>
<summary><b>Q56. 🟡 <code>AtomicLong</code> vs <code>LongAdder</code>?</b></summary>

`AtomicLong` is one CAS-updated cell; under heavy contention threads keep retrying failed CASes. `LongAdder` stripes updates across cells and sums them on read, giving much higher write throughput under contention. Its `sum()` isn't an atomic snapshot, though, so use `AtomicLong` when you need compare-and-set semantics or exact reads. → [Ch 12](12-concurrency-advanced-juc-loom.md)
</details>

<details>
<summary><b>Q57. 🟡 Why is <code>Executors.newFixedThreadPool(n)</code> risky in a server?</b></summary>

It uses an **unbounded** `LinkedBlockingQueue`. Under overload, the queue grows without limit, so latency climbs and memory eventually runs out, instead of the pool pushing back. Build a `ThreadPoolExecutor` with a bounded queue, a deliberate rejection policy and named threads. → [Ch 12](12-concurrency-advanced-juc-loom.md)
</details>

<details>
<summary><b>Q58. 🔴 <code>thenApply</code> vs <code>thenCompose</code> vs <code>thenCombine</code>?</b></summary>

`thenApply` maps the result with a synchronous function. `thenCompose` flat-maps it into another `CompletableFuture` (a dependent asynchronous step). `thenCombine` joins two *independent* futures. The `…Async` variants run on an executor, by default the common pool, so pass your own executor for blocking work. → [Ch 12](12-concurrency-advanced-juc-loom.md)
</details>

<details>
<summary><b>Q59. 🔴 What is virtual-thread pinning, and is it still a problem?</b></summary>

Pinning is when a virtual thread can't unmount from its carrier while blocked, so it ties up a platform thread. The big case, blocking inside `synchronized`, was fixed in JDK 24 (JEP 491). Pinning remains possible with native frames (JNI or foreign calls that call back into Java and block). Detect it with the JFR `jdk.VirtualThreadPinned` event. → [Ch 12](12-concurrency-advanced-juc-loom.md)
</details>

<details>
<summary><b>Q60. 🔴 Should you pool virtual threads?</b></summary>

No. They're cheap to create, and pooling them reintroduces the limits they remove. Create one per task (`newVirtualThreadPerTaskExecutor`), and limit concurrency toward scarce resources (database connections, rate-limited APIs) with a `Semaphore` or a bounded connection pool. → [Ch 12](12-concurrency-advanced-juc-loom.md)
</details>

<details>
<summary><b>Q61. 🟣 What does structured concurrency add?</b></summary>

Subtasks are scoped to a block: the scope waits for all of them. If one fails (depending on the joiner policy), the others are cancelled, and errors and cancellation propagate reliably. Thread dumps show the parent–child structure. In JDK 27, `StructuredTaskScope` is still a preview API (seventh preview). → [Ch 12](12-concurrency-advanced-juc-loom.md)
</details>

---

## Part IV · Platform APIs

<details>
<summary><b>Q62. 🟢 Streams vs Readers/Writers?</b></summary>

`InputStream`/`OutputStream` move **bytes**; `Reader`/`Writer` move **characters**, decoded and encoded with a charset. Always state the charset explicitly. UTF-8 has been the default since JDK 18 (JEP 400), but code may still run on older JDKs or depend on platform settings. → [Ch 13](13-io-nio-networking-serialization.md)
</details>

<details>
<summary><b>Q63. 🟡 What does an NIO <code>Selector</code> do?</b></summary>

It multiplexes many non-blocking channels on one thread. You register channels with interest sets (`OP_ACCEPT`, `OP_READ`, `OP_WRITE`), and `select()` returns the keys that are ready. That's the foundation of event-loop servers such as Netty. → [Ch 13](13-io-nio-networking-serialization.md)
</details>

<details>
<summary><b>Q64. 🟡 What is zero-copy with <code>FileChannel.transferTo</code>?</b></summary>

On Linux it maps to `sendfile`: data moves from the page cache to the socket inside the kernel, without being copied into user space and back. That saves CPU and memory bandwidth. Kafka relies on it to serve consumers. → [Ch 13](13-io-nio-networking-serialization.md)
</details>

<details>
<summary><b>Q65. 🔴 Why is Java deserialization of untrusted data dangerous?</b></summary>

Deserialisation instantiates classes and runs their `readObject` and related logic for whatever classes are on the class path. Attackers chain "gadget" classes into remote code execution. Never deserialise untrusted bytes. If you must, enforce `ObjectInputFilter` allow-lists (JEPs 290 and 415), and prefer schema-based formats (JSON with explicit types, Protobuf). → [Ch 13](13-io-nio-networking-serialization.md)
</details>

<details>
<summary><b>Q66. 🟡 Core reflection vs <code>MethodHandle</code>?</b></summary>

A `MethodHandle` is checked once at lookup time, is strongly typed, and can be inlined by the JIT when held in a `static final` field. Reflection re-checks access on each call. Since JDK 18 (JEP 416), core reflection is itself implemented on method handles, so the gap has narrowed, but `MethodHandle`s remain the tool for performance-sensitive dynamic invocation. → [Ch 14](14-reflection-annotations-proxies.md)
</details>

<details>
<summary><b>Q67. 🟡 JDK dynamic proxies vs subclass proxies (ByteBuddy/CGLIB)?</b></summary>

JDK proxies implement **interfaces** only (`Proxy` plus an `InvocationHandler`). Subclass proxies can proxy concrete classes, but not `final` classes or methods. Spring Boot defaults to class-based (CGLIB) proxies. → [Ch 14](14-reflection-annotations-proxies.md)
</details>

<details>
<summary><b>Q68. 🔴 Why doesn't <code>@Transactional</code> apply to a call from another method in the same class?</b></summary>

Spring AOP is proxy-based. `this.method()` calls the target object directly and bypasses the proxy, so no advice runs. Move the method to another bean, call it through an injected proxy of the same bean, or use AspectJ weaving. → [Ch 14](14-reflection-annotations-proxies.md)
</details>

<details>
<summary><b>Q69. 🔴 What can a Java agent do?</b></summary>

Through `premain`/`agentmain` it receives an `Instrumentation` instance. It can transform class bytes as they load (`ClassFileTransformer`), retransform or redefine classes, and measure object sizes. APM tools, profilers and Mockito's inline mock maker use it. Loading agents dynamically into a running JVM produces warnings since JDK 21 (JEP 451); declare agents with `-javaagent` instead. → [Ch 14](14-reflection-annotations-proxies.md)
</details>

<details>
<summary><b>Q70. 🟡 How does Maven choose between conflicting dependency versions?</b></summary>

**Nearest wins**: the version closest to your project in the dependency tree is used (the first declared, on ties), **not** the highest. Control versions with `dependencyManagement` or BOMs, inspect with `mvn dependency:tree -Dverbose`, and enforce with the Enforcer plugin's dependency-convergence rule. → [Ch 15](15-modules-and-build-tools.md)
</details>

<details>
<summary><b>Q71. 🟡 Gradle <code>api</code> vs <code>implementation</code>?</b></summary>

`api` exposes the dependency on consumers' compile class path; it becomes part of your public contract. `implementation` keeps it internal, which means less recompilation, faster builds and no accidental coupling. Default to `implementation`. → [Ch 15](15-modules-and-build-tools.md)
</details>

<details>
<summary><b>Q72. 🔴 What does <code>jlink</code> give you?</b></summary>

A custom runtime image containing only the modules your application needs, for smaller container images and a smaller attack surface. Find the module set with `jdeps --print-module-deps`, then run `jlink --add-modules … --strip-debug --no-header-files --no-man-pages`. → [Ch 15](15-modules-and-build-tools.md)
</details>

---

## Part V · Design

<details>
<summary><b>Q73. 🟡 Explain the Dependency Inversion Principle with an example.</b></summary>

High-level policy shouldn't depend on low-level details; both depend on abstractions owned by the policy side. The domain defines a `PaymentGateway` port, and a `StripePaymentGateway` adapter in the infrastructure layer implements it. Source-code dependencies point *inward*, even though calls flow outward. → [Ch 16](16-design-principles-clean-architecture.md)
</details>

<details>
<summary><b>Q74. 🟡 What does Liskov substitution mean in practice?</b></summary>

A subtype must honour its parent's contract: it can't strengthen preconditions, weaken postconditions or break invariants. The classic violation is `Square extends Rectangle`: `setWidth` changing the height breaks code written against `Rectangle`. If substitution surprises callers, prefer composition or a different abstraction. → [Ch 16](16-design-principles-clean-architecture.md)
</details>

<details>
<summary><b>Q75. 🔴 Describe hexagonal architecture in one paragraph.</b></summary>

The domain and use cases sit at the centre and define **ports**: inbound interfaces (use cases) and outbound ones (repositories, gateways, publishers). **Adapters** on the outside implement or call those ports (REST controllers, JPA repositories, Kafka producers). Dependencies point inward, so the core is testable without frameworks and infrastructure can be swapped without touching business rules. → [Ch 16](16-design-principles-clean-architecture.md)
</details>

<details>
<summary><b>Q76. 🔴 What is a DDD aggregate?</b></summary>

A cluster of entities and value objects that's modified as a unit through a single **root**, which enforces the invariants. The rules: one transaction modifies one aggregate, other aggregates are referenced by ID, and aggregates are kept small. Consistency between aggregates is eventual, through domain events (often via an outbox). → [Ch 16](16-design-principles-clean-architecture.md)
</details>

<details>
<summary><b>Q77. 🟢 What's the best way to write a singleton in Java?</b></summary>

A single-element `enum`, which is safe against serialisation and reflection attacks, or the holder-class idiom for lazy initialisation. In Spring applications, use a singleton-scoped bean and inject it rather than reaching for global state. → [Ch 17](17-design-patterns.md)
</details>

<details>
<summary><b>Q78. 🟡 What does the Strategy pattern look like in modern Java?</b></summary>

A functional interface implemented with lambdas or method references, or an enum whose constants carry behaviour. In Spring, inject a `Map<String, PaymentStrategy>` of beans and select by key. That removes `if/else` ladders without class explosion. → [Ch 17](17-design-patterns.md)
</details>

<details>
<summary><b>Q79. 🔴 Visitor vs sealed types with pattern matching?</b></summary>

Both make adding *operations* easy and adding *types* hard (the expression problem). Sealed types with `switch` do it with far less boilerplate (no `accept` methods), deconstruct records directly, and get compiler-checked exhaustiveness: a new variant breaks every unhandled switch at compile time. In Java 21+ they replace most visitors. → [Ch 17](17-design-patterns.md)
</details>

<details>
<summary><b>Q80. 🟡 Where is the Decorator pattern in the JDK?</b></summary>

In `java.io`: `new BufferedInputStream(new FileInputStream(f))` and `new BufferedReader(new InputStreamReader(in, UTF_8))`. Also `Collections.unmodifiableList` and `synchronizedList`, which wrap a collection and add behaviour behind the same interface. → [Ch 17](17-design-patterns.md)
</details>

---

## Part VI · Ecosystem

<details>
<summary><b>Q81. 🟡 What's the Spring bean lifecycle, in order?</b></summary>

Instantiate → inject dependencies → `Aware` callbacks → `BeanPostProcessor.postProcessBeforeInitialization` → `@PostConstruct` → `afterPropertiesSet` → custom init method → `postProcessAfterInitialization` (where AOP proxies are created) → in use → `@PreDestroy` → `destroy()` → custom destroy method. → [Ch 18](18-spring-internals.md)
</details>

<details>
<summary><b>Q82. 🟡 How does Spring Boot auto-configuration work?</b></summary>

Boot loads the auto-configuration classes listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Each one is guarded by conditions such as `@ConditionalOnClass`, `@ConditionalOnMissingBean` and `@ConditionalOnProperty`, so your own beans win. Run with `--debug` (or check the `conditions` actuator endpoint) to see why something did or didn't apply. → [Ch 18](18-spring-internals.md)
</details>

<details>
<summary><b>Q83. 🔴 Why didn't <code>@Transactional</code> roll back when a checked exception was thrown?</b></summary>

By default Spring rolls back only on unchecked exceptions (`RuntimeException` and `Error`). Checked exceptions commit. Use `@Transactional(rollbackFor = Exception.class)`, or throw unchecked domain exceptions. → [Ch 18](18-spring-internals.md)
</details>

<details>
<summary><b>Q84. 🔴 What causes <code>UnexpectedRollbackException</code>?</b></summary>

An inner `REQUIRED` method (which joins the existing transaction) throws, and that marks the shared transaction rollback-only. The outer method catches the exception and tries to commit, so Spring rolls back and throws `UnexpectedRollbackException`. Fix it by not swallowing the exception, or use `REQUIRES_NEW` for the inner work if it's truly independent (mind the extra connection). → [Ch 18](18-spring-internals.md)
</details>

<details>
<summary><b>Q85. 🔴 How does Spring handle circular dependencies, and what changed in Boot 2.6?</b></summary>

Setter and field injection cycles between singletons can be resolved through the three-level cache of early references. Constructor-injection cycles can't. Since Spring Boot 2.6, circular references are **prohibited by default** (`spring.main.allow-circular-references=false`); the right fix is to redesign, not to flip the flag. → [Ch 18](18-spring-internals.md)
</details>

<details>
<summary><b>Q86. 🟢 What is the N+1 query problem?</b></summary>

Loading N parent entities and then lazily loading an association for each one, for N + 1 queries in total. Fix it with fetch joins, `@EntityGraph`, batch fetching (`hibernate.default_batch_fetch_size`) or DTO projections, and catch regressions with query-count assertions in tests. → [Ch 19](19-persistence-jdbc-jpa-hibernate.md)
</details>

<details>
<summary><b>Q87. 🟡 Which anomalies does each isolation level prevent?</b></summary>

READ UNCOMMITTED allows dirty reads. READ COMMITTED prevents dirty reads (PostgreSQL's default). REPEATABLE READ also prevents non-repeatable reads (MySQL InnoDB's default; PostgreSQL implements it as snapshot isolation). SERIALIZABLE prevents everything, including write skew. Real engines differ from the SQL standard in details, so know your database's behaviour. → [Ch 19](19-persistence-jdbc-jpa-hibernate.md)
</details>

<details>
<summary><b>Q88. 🟡 Optimistic vs pessimistic locking?</b></summary>

**Optimistic:** a `@Version` column is checked on update (`… WHERE id = ? AND version = ?`). A conflict raises `OptimisticLockException`, and you retry. Best for low contention. **Pessimistic:** `SELECT … FOR UPDATE` locks rows until commit. Best for high contention on short transactions; watch for deadlocks and lock waits. → [Ch 19](19-persistence-jdbc-jpa-hibernate.md)
</details>

<details>
<summary><b>Q89. 🔴 What is write skew?</b></summary>

Two transactions read overlapping data, each makes a *disjoint* write based on what it read, and both commit, which jointly violates an invariant. The classic example is two on-call doctors both signing off. Snapshot isolation allows it. Prevent it with SERIALIZABLE, by locking the rows you read (`FOR UPDATE`), or by materialising the conflict into a row both transactions must update. → [Ch 19](19-persistence-jdbc-jpa-hibernate.md)
</details>

<details>
<summary><b>Q90. 🔴 How do you size a database connection pool?</b></summary>

Start small. HikariCP's guidance, roughly `cores × 2 + effective spindles`, is a starting point. Little's law (connections ≈ throughput × time each connection is held) gives the requirement. More connections often make things *slower*, because of database contention. Then measure pool wait time and database load, and remember that the total across all instances must fit the database's limits. → [Ch 19](19-persistence-jdbc-jpa-hibernate.md)
</details>

<details>
<summary><b>Q91. 🔴 Why disable Open Session in View?</b></summary>

It keeps the persistence context (and often a connection) open through view rendering or serialisation. That hides lazy-loading N+1s in the web layer and holds connections longer under load. Spring Boot enables it by default and logs a warning. Set `spring.jpa.open-in-view=false` and fetch what each use case needs explicitly. → [Ch 19](19-persistence-jdbc-jpa-hibernate.md)
</details>

<details>
<summary><b>Q92. 🟡 Stub vs mock vs spy vs fake?</b></summary>

A **stub** returns canned answers. A **mock** also verifies interactions. A **spy** wraps a real object, with partial stubbing and verification. A **fake** is a working lightweight implementation, such as an in-memory repository. Prefer fakes and real collaborators for domain logic; reserve mocks for boundaries. → [Ch 20](20-testing-and-quality.md)
</details>

<details>
<summary><b>Q93. 🟡 Why Testcontainers instead of H2 for persistence tests?</b></summary>

It tests against the real engine, with its SQL dialect, locking behaviour, constraints, JSON types and migrations. H2's compatibility modes differ in subtle ways that hide bugs. With Spring Boot 3.1+, `@ServiceConnection` wires the container into the application context automatically. → [Ch 20](20-testing-and-quality.md)
</details>

<details>
<summary><b>Q94. 🔴 Why do Spring integration test suites get slow, and how do you fix them?</b></summary>

The test context cache is keyed by configuration, so every different combination of `@MockitoBean`s, profiles or properties boots a new context. Standardise test configuration in shared base classes, use slices (`@WebMvcTest`, `@DataJpaTest`), reuse containers, and watch the context count in the logs. → [Ch 20](20-testing-and-quality.md)
</details>

<details>
<summary><b>Q95. 🔴 What does mutation testing measure that coverage doesn't?</b></summary>

Whether your tests would *detect* bugs. Tools like PIT mutate the code (flip conditions, remove calls, change return values) and check that some test fails. Surviving mutants expose weak assertions. Coverage only shows which code was executed, not whether anything was verified. → [Ch 20](20-testing-and-quality.md)
</details>

---

## Part VII · Production

<details>
<summary><b>Q96. 🟡 State Little's law and use it.</b></summary>

L = λ × W: average concurrency equals arrival rate times average time in the system. 500 requests/s × 0.2 s = 100 requests in flight, which sizes thread pools, connection pools and queue capacities, and explains why latency spikes cause concurrency (and memory) spikes. → [Ch 21](21-performance-engineering-troubleshooting.md)
</details>

<details>
<summary><b>Q97. 🔴 What is coordinated omission?</b></summary>

A closed-loop load generator waits for each response before sending the next request, so during a stall it stops sending and under-records latency in exactly the worst periods. Use open-model generators that measure from the *intended* start time (wrk2, Gatling's open model, HdrHistogram's correction). → [Ch 21](21-performance-engineering-troubleshooting.md)
</details>

<details>
<summary><b>Q98. 🔴 How do you find a CPU hotspot in production safely?</b></summary>

JFR, which is low-overhead and built in: `jcmd <pid> JFR.start duration=60s filename=app.jfr`, then `jfr view hot-methods app.jfr`. Or async-profiler, which also avoids safepoint bias. Read flame graphs top-down from the widest frames, and confirm fixes with a benchmark and a production comparison. → [Ch 21](21-performance-engineering-troubleshooting.md)
</details>

<details>
<summary><b>Q99. 🔴 Throughput is fine but p99 latency spikes. What do you check?</b></summary>

GC pauses and allocation rate. Time-to-safepoint. Lock contention (JFR monitor events). Pool saturation (time waiting for a thread or connection). Downstream latency and retries. CPU throttling from container CFS quotas. Noisy neighbours. Correlate the spike times across these signals before you change anything. → [Ch 21](21-performance-engineering-troubleshooting.md)
</details>

<details>
<summary><b>Q100. 🟣 How do you read thread dumps of a "hung" service?</b></summary>

Take three dumps about 5 s apart, and look for what doesn't change. Many threads `BLOCKED` on one monitor means contention. All pool threads `WAITING` on a pool or semaphore means exhaustion (often database connections). There may be a deadlock section. Threads parked in socket reads point to missing timeouts. Fix the cause (a timeout, a pool size, lock scope), not the symptom. → [Ch 21](21-performance-engineering-troubleshooting.md)
</details>

<details>
<summary><b>Q101. 🟡 Liveness vs readiness probes?</b></summary>

**Liveness** asks "should this process be restarted?" It must not depend on downstream systems, or one database outage restarts your whole fleet. **Readiness** asks "should this instance receive traffic right now?" Use a **startup** probe for slow-starting JVMs, so liveness doesn't kill them mid-boot. → [Ch 22](22-production-readiness-observability-resilience.md)
</details>

<details>
<summary><b>Q102. 🟡 Why add jitter to retry back-off?</b></summary>

Without jitter, clients that failed together retry together, in synchronised waves that keep the dependency overloaded (a thundering herd). "Full jitter", a random delay between 0 and the back-off cap, spreads the retries out. Combine it with retry budgets and idempotency. → [Ch 22](22-production-readiness-observability-resilience.md)
</details>

<details>
<summary><b>Q103. 🔴 Describe the circuit-breaker states.</b></summary>

**CLOSED:** calls pass through, and failures and slow calls are measured. Past a threshold the breaker goes **OPEN** and fails fast for a wait period. Then it goes **HALF_OPEN** and allows a few trial calls: success closes it, failure reopens it. It protects both the caller (fast failure) and the callee (time to recover). → [Ch 22](22-production-readiness-observability-resilience.md)
</details>

<details>
<summary><b>Q104. 🔴 What is metric cardinality, and why does it matter?</b></summary>

The number of unique label combinations, each of which is a separate time series. Tags like user IDs or raw URLs explode storage cost, memory and query time. Use bounded tags (route templates, status classes, region), and put high-cardinality detail in logs and traces instead. → [Ch 22](22-production-readiness-observability-resilience.md)
</details>

<details>
<summary><b>Q105. 🟣 What are burn-rate alerts?</b></summary>

Alerts on how fast the error budget is being consumed, rather than on raw thresholds. For a 30-day 99.9% SLO, a 14.4× burn rate over 1 hour means 2% of the monthly budget is gone. Multi-window alerts (a long window plus a short confirmation window) are both fast and precise, and page only for problems users actually feel. → [Ch 22](22-production-readiness-observability-resilience.md)
</details>

---

## Part VIII · Architecture

<details>
<summary><b>Q106. 🟡 CAP vs PACELC?</b></summary>

CAP: during a network **P**artition, choose **C**onsistency or **A**vailability. PACELC adds: **E**lse, when there's no partition, choose between **L**atency and **C**onsistency. That second trade-off is the one you face every day, so PACELC is the more useful lens. → [Ch 23](23-distributed-systems-microservices.md)
</details>

<details>
<summary><b>Q107. 🟡 Why use a transactional outbox?</b></summary>

To avoid dual writes, where the database commit and the broker publish can fail independently. Write the event into an outbox table *in the same transaction* as the state change, then a relay or CDC process publishes it. You get at-least-once publication, so consumers must be idempotent. → [Ch 23](23-distributed-systems-microservices.md)
</details>

<details>
<summary><b>Q108. 🔴 What does "exactly-once" in Kafka really give you?</b></summary>

The idempotent producer removes duplicates caused by retries per partition, and transactions make writes to several partitions, plus the consumer offset commit, atomic. Together that's exactly-once for read-process-write pipelines *inside* Kafka. Side effects in external systems still need idempotent consumers or deduplication. → [Ch 23](23-distributed-systems-microservices.md)
</details>

<details>
<summary><b>Q109. 🔴 Saga orchestration vs choreography?</b></summary>

**Orchestration:** a coordinator sends commands and compensations. The flow is explicit and easy to monitor and change, at the cost of a central component. **Choreography:** services react to each other's events. It's loosely coupled, but the flow is implicit and harder to trace as it grows. Both need compensating actions, idempotency and timeouts. → [Ch 23](23-distributed-systems-microservices.md)
</details>

<details>
<summary><b>Q110. 🔴 Why do distributed locks need fencing tokens?</b></summary>

A lock holder can pause (a GC pause, a network delay) past its lease expiry while another client acquires the lock, so both act. With fencing, the lock service issues monotonically increasing tokens, and the protected resource rejects writes carrying an older token than one it has already seen. → [Ch 23](23-distributed-systems-microservices.md)
</details>

<details>
<summary><b>Q111. 🟡 How many QPS is 1 billion requests per day?</b></summary>

About 12K on average (10⁹ / 86,400 ≈ 11.6K). With a peak-to-average ratio of 2–5×, design for roughly 25–60K QPS, and check whether traffic is regional or bursty. → [Ch 24](24-system-design-for-java-architects.md)
</details>

<details>
<summary><b>Q112. 🔴 Token bucket vs sliding-window rate limiting?</b></summary>

A token bucket allows bursts up to its capacity, then sustains the refill rate. That's ideal for APIs. A sliding-window counter approximates exact per-window counting with O(1) memory and avoids the 2× edge bursts of fixed windows. Distributed versions keep state in Redis with atomic scripts and need a fail-open or fail-closed policy. → [Ch 24](24-system-design-for-java-architects.md)
</details>

<details>
<summary><b>Q113. 🟣 How do you generate IDs in a sharded system?</b></summary>

Snowflake-style 64-bit IDs (timestamp | worker | sequence) are compact, k-sorted and need no coordination per ID. UUIDv7 is time-ordered and needs no worker assignment. Handle clocks moving backwards, and avoid sequential public IDs, which leak business volume and invite enumeration. → [Ch 24](24-system-design-for-java-architects.md)
</details>

<details>
<summary><b>Q114. 🟡 What's the most common web vulnerability class, and how do you prevent it systematically?</b></summary>

Broken access control, OWASP's #1. Deny by default, enforce ownership and tenancy **in the data access itself** (`findByIdAndOwnerId`), use method security for complex rules, return 404 for resources the caller can't see, and test cross-user and cross-tenant access automatically. → [Ch 25](25-security-for-java-engineers.md)
</details>

<details>
<summary><b>Q115. 🔴 What's wrong with <code>new Random()</code> for session tokens?</b></summary>

`java.util.Random` is a predictable linear congruential generator: after seeing a few outputs, an attacker can predict the rest. Use `SecureRandom` with at least 128 bits of randomness, encoded with base64url, and compare secrets in constant time (`MessageDigest.isEqual`). → [Ch 25](25-security-for-java-engineers.md)
</details>

<details>
<summary><b>Q116. 🔴 What made Log4Shell so severe?</b></summary>

Log4j 2 performed JNDI lookups on strings *inside logged messages*, so any attacker-controlled input that got logged could load remote code: unauthenticated RCE. It was present transitively in a vast amount of software. The lessons: SBOM-based inventory, the ability to patch the whole fleet fast, egress restrictions, and treating "features that interpret data" with suspicion. → [Ch 25](25-security-for-java-engineers.md)
</details>

<details>
<summary><b>Q117. 🟣 What is "harvest now, decrypt later", and what does the JDK offer?</b></summary>

Adversaries record encrypted traffic today, hoping to decrypt it with a future quantum computer, which matters for long-lived secrets. JDK 24 added the post-quantum algorithms ML-KEM and ML-DSA (JEPs 496 and 497), and JDK 27 adds hybrid post-quantum key exchange for TLS 1.3 (JEP 527). Inventory where you depend on RSA and ECDH, and plan for crypto-agility. → [Ch 25](25-security-for-java-engineers.md)
</details>

---

## Part IX · Interview Mastery

<details>
<summary><b>Q118. 🟡 How do input constraints point you to the right algorithm?</b></summary>

Assume roughly 10⁸ simple operations per second. n ≤ 20 allows exponential approaches (subsets, bitmasks), n ≤ 5,000 allows O(n²), n ≤ 10⁶ needs O(n log n) or O(n), and larger inputs need O(log n) or math. Say the budget out loud before choosing. → [Ch 26](26-coding-and-lld-interview-patterns.md)
</details>

<details>
<summary><b>Q119. 🔴 Why use a min-heap of size k to find the k largest elements?</b></summary>

The heap's root is the smallest of the current top k, so each new element is compared with it in O(1), and if it's bigger it replaces the root in O(log k). That's O(n log k) time and O(k) memory, better than sorting when k ≪ n, and it works on streams. → [Ch 26](26-coding-and-lld-interview-patterns.md)
</details>

<details>
<summary><b>Q120. 🔴 How do you test time-dependent code (TTL caches, pricing, rate limiters) deterministically?</b></summary>

Inject time: a `java.time.Clock` for wall-clock logic, or a `LongSupplier` for monotonic `nanoTime`-style durations. Advance a fake clock in tests. That makes them instant, deterministic and able to cover edge cases (exact expiry, DST transitions) that `Thread.sleep` tests can't. → [Ch 26](26-coding-and-lld-interview-patterns.md)
</details>

<details>
<summary><b>Q121. 🟣 What are the common staff-engineer archetypes?</b></summary>

From Will Larson: **Tech Lead** (guides a team's execution), **Architect** (owns direction across a domain), **Solver** (goes deep on the hardest problems) and **Right Hand** (extends an executive's reach). Frame your stories in the archetype that matches your experience. → [Ch 27](27-staff-architect-interview-playbook.md)
</details>

<details>
<summary><b>Q122. 🟣 What's the kernel of a good technical strategy?</b></summary>

Rumelt's kernel: a **diagnosis** of the situation, backed by evidence; a **guiding policy** that says how you'll approach it; and **coherent actions** that reinforce each other. A list of goals or initiatives without a diagnosis isn't a strategy. → [Ch 27](27-staff-architect-interview-playbook.md)
</details>

<details>
<summary><b>Q123. 🟣 One-way vs two-way door decisions?</b></summary>

Two-way doors are cheap to reverse: decide quickly, measure, adjust. One-way doors are expensive or impossible to reverse: invest in analysis, prototypes and review in proportion. Saying which kind of door a decision is, and calibrating your process to match, is a core staff-level judgment signal. → [Ch 27](27-staff-architect-interview-playbook.md)
</details>

---

## Part X · Specialist Topics

<details>
<summary><b>Q124. 🟡 What is backpressure, in one sentence?</b></summary>

A subscriber signals how many items it can take (`request(n)`), and the publisher may never send more. When a producer can't slow down, you must choose an explicit overflow strategy (bounded buffer, drop, keep latest, or fail) instead of letting an unbounded buffer absorb it. → [Ch 29](29-reactive-java-reactor-webflux.md)
</details>

<details>
<summary><b>Q125. 🔴 <code>publishOn</code> vs <code>subscribeOn</code>?</b></summary>

`subscribeOn` moves the *subscription*, and therefore where a cold source emits: its position doesn't matter, and the one closest to the source wins. `publishOn` moves every signal *after* it onto another scheduler. Asynchronous sources such as Netty still emit on their own threads. → [Ch 29](29-reactive-java-reactor-webflux.md)
</details>

<details>
<summary><b>Q126. 🔴 Why is one blocking call on an event loop so damaging?</b></summary>

Event-loop threads are few and each serves many connections, so a blocking call stalls everyone sharing that thread. In our measurement, one 200 ms block delayed 166 unrelated requests (p99 1 ms → 190 ms). Offload with `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`, and catch violations with BlockHound. → [Ch 29](29-reactive-java-reactor-webflux.md)
</details>

<details>
<summary><b>Q127. 🔴 Why does <code>Mono.just(remoteCall())</code> surprise people?</b></summary>

`Mono.just` takes a value, so `remoteCall()` runs immediately at assembly time, even if nobody ever subscribes, and only once even if several subscribers do. Use `Mono.fromCallable` or `Mono.defer` to make the call lazy and per subscription. → [Ch 29](29-reactive-java-reactor-webflux.md)
</details>

<details>
<summary><b>Q128. 🔴 How does reactive code carry request context without <code>ThreadLocal</code>s?</b></summary>

Reactor's `Context` is attached to the subscription and flows upstream: write it with `contextWrite`, read it with `deferContextual`. For MDC, tracing and security, the Micrometer context-propagation library restores `ThreadLocal`s around operators (in Spring Boot, `spring.reactor.context-propagation=auto`). → [Ch 29](29-reactive-java-reactor-webflux.md)
</details>

<details>
<summary><b>Q129. 🟡 CDS vs the AOT cache?</b></summary>

CDS stores parsed class metadata in a mapped archive (the default archive covers JDK classes; AppCDS adds yours). The AOT cache (JDK 24+) also stores classes loaded and *linked*, and on JDK 25 JIT method profiles too. In our measurement it cut startup by 44% versus default CDS. → [Ch 30](30-startup-footprint-aot-native-image.md)
</details>

<details>
<summary><b>Q130. 🔴 What silently disables an AOT cache, and how do you catch it?</b></summary>

A different JDK build or classpath (even reordered JARs, directories or wildcards). The JVM logs errors and starts without the cache, so it's simply slow again. Run a CI smoke test with `-XX:AOTMode=on`, which makes the JVM refuse to start instead. → [Ch 30](30-startup-footprint-aot-native-image.md)
</details>

<details>
<summary><b>Q131. 🔴 What does GraalVM Native Image's closed-world assumption imply?</b></summary>

Only code reachable at build time is compiled in, so reflection, proxies, resources, JNI and serialisation need metadata, there's no runtime class loading or Java agents, and in Spring the bean graph is fixed at build time. In return: millisecond startup, low RSS and no warmup, at the cost of long builds and usually lower peak throughput. → [Ch 30](30-startup-footprint-aot-native-image.md)
</details>

<details>
<summary><b>Q132. 🔴 What do compact object headers save?</b></summary>

They shrink the header from 12 to 8 bytes (opt-in in JDK 25, the default in JDK 27). In our test, `Point(int, int)` went from 24 to 16 bytes and `OrderLine` from 32 to 24, for a 25% smaller heap. Classes whose saved bytes don't cross an 8-byte alignment boundary (like `Integer`) don't shrink. → [Ch 30](30-startup-footprint-aot-native-image.md)
</details>

<details>
<summary><b>Q133. 🔴 Why can offset pagination return duplicates or skip rows?</b></summary>

Offsets count positions, and concurrent inserts or deletes shift positions between page requests. In our demo, an insert duplicated an order and a delete skipped one. Keyset pagination ("rows after this sort key", with a unique tie-breaker) is stable, and stays fast on deep pages. → [Ch 31](31-api-design-between-services.md)
</details>

<details>
<summary><b>Q134. 🔴 What do <code>If-Match</code> and 412 give you?</b></summary>

Optimistic concurrency over HTTP. The client sends the ETag it read, and if the resource has changed since, the server answers 412 instead of letting a stale write overwrite someone else's update. Requiring the header (428 when it's missing) makes the protection mandatory. → [Ch 31](31-api-design-between-services.md)
</details>

<details>
<summary><b>Q135. 🔴 Why must you never reuse a Protobuf field number?</b></summary>

The wire format identifies fields by number, so old readers interpret the new field as the old one. In our demo, a reused number turned a 15% discount into `quantity=15`, with no error. Retire numbers with `reserved`, and enforce the rules in CI with a breaking-change checker. → [Ch 31](31-api-design-between-services.md)
</details>

<details>
<summary><b>Q136. 🟡 Why is a default Jackson <code>ObjectMapper</code> a compatibility hazard?</b></summary>

It fails on unknown properties by default, so a producer *adding* a field breaks the consumer, and unknown enum values fail too. Configure tolerant reading (ignore unknown fields, default enums to `UNKNOWN`) in shared client libraries. → [Ch 31](31-api-design-between-services.md)
</details>

---

## Part XI · Practice Lab

<details>
<summary><b>Q137. 🔴 A pod is OOMKilled but the heap looks healthy. What's the first suspect, and the tool to confirm it?</b></summary>

Non-heap memory. The kernel enforces RSS, not heap. Direct buffers are a classic cause: the default limit equals the max heap, so off-heap use can double RSS. Enable Native Memory Tracking and diff `jcmd VM.native_memory summary` over time; in our reproduction, direct buffers showed up under "Other". → [Ch 32](32-production-troubleshooting-scenarios.md)
</details>

<details>
<summary><b>Q138. 🔴 What is the signature of a thread-starvation deadlock?</b></summary>

Every thread in a bounded pool is `WAITING` in `Future.get` or `join` for tasks queued behind them in the same pool, CPU is idle, and the JVM reports *no* deadlock, because there's no lock cycle. Fix it by not blocking a pool on its own tasks: separate pools, non-blocking composition, or virtual threads. → [Ch 32](32-production-troubleshooting-scenarios.md)
</details>

<details>
<summary><b>Q139. 🔴 Low CPU, rising latency, and it started when virtual threads were enabled on JDK 21. What's your hypothesis?</b></summary>

Pinning. Blocking inside `synchronized` keeps the virtual thread on its carrier, capping concurrency at about the core count. Our reproduction took 5,049 ms on JDK 21 versus 81 ms on JDK 25. Confirm with the JFR `jdk.VirtualThreadPinned` event, and fix with JDK 24+, `ReentrantLock`, or newer libraries. → [Ch 32](32-production-troubleshooting-scenarios.md)
</details>

<details>
<summary><b>Q140. 🔴 How do you read two class histograms to find a leak?</b></summary>

Ignore the usual leaders (`byte[]`, `String`, `HashMap$Node`) and look for *your own* class growing between snapshots. Our `Session` class went from 548K to 1.2M instances in 4 seconds, with map nodes growing in lockstep, which points at an unbounded map. Confirm the retaining field with a heap dump's dominator tree. → [Ch 32](32-production-troubleshooting-scenarios.md)
</details>

<details>
<summary><b>Q141. 🔴 The connection pool is exhausted, but the database is idle. Why?</b></summary>

Connections are held during non-database work, typically a remote call inside a `@Transactional` method. Capacity is pool size divided by hold time. In our reproduction, that caused 113 timeouts out of 300 requests, and moving the call out caused none. Fix the transaction boundaries; don't just grow the pool. → [Ch 32](32-production-troubleshooting-scenarios.md)
</details>

<details>
<summary><b>Q142. 🟣 In a debugging interview, what separates a staff answer from a senior one?</b></summary>

Both diagnose and fix the instance with evidence. The staff answer goes on to the *class* of problem: inventory where else it can happen, publish guidance, add fleet telemetry and alerts, change the paved-road default, and record the decision, all without being prompted. → [Ch 33](33-annotated-mock-interviews.md)
</details>

<details>
<summary><b>Q143. 🟣 What makes a behavioural story "staff-shaped"?</b></summary>

Scope beyond your team, a diagnosis backed by data, visible influence mechanics (a written proposal, pre-wiring sceptics, changing the design based on feedback), adoption made easy (a mechanism, not a memo), measured results, and a learning that changed how you work. → [Ch 33](33-annotated-mock-interviews.md)
</details>

---

## Part XII · Data & AI

<details>
<summary><b>Q144. 🟡 Redis executes commands on one thread. What follows from that?</b></summary>

Every single command is atomic, and any slow command delays every other client. In the lab, `KEYS *` over a million keys made another client wait 282 ms, and `DEL` of a 2-million-field hash blocked everyone for 589 ms. Use `SCAN` (worst wait 4.5 ms) and `UNLINK` (under a millisecond; memory freed in the background), and find big keys before they find you. → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q145. 🔴 Pipelining, <code>MULTI</code>/<code>EXEC</code> and Lua: what does each give you?</b></summary>

Pipelining removes round trips but gives no atomicity: at about 0.8 ms RTT, 1,174 operations a second one at a time against 413,720 pipelined. `MULTI`/`EXEC` runs queued commands as one block, with no reads in between and no rollback; with `WATCH` it becomes optimistic locking. A Lua script runs read-decide-write atomically on the server, and blocks everyone while it runs. → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q146. 🔴 Four services increment a Redis counter with <code>GET</code> then <code>SET</code>. What happens?</b></summary>

Lost updates: in the lab, 10,000 increments produced 3,286. Let the server do the whole operation (`INCR`, `HINCRBY`, or compare-and-set with `SET … IFEQ` on Redis 8.4+), use a Lua script for logic no command covers, or `WATCH`/`MULTI`/`EXEC` with retries (correct, but 13,284 retries under that contention). → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q147. 🔴 A Redis cache starts failing writes with "OOM command not allowed". Why?</b></summary>

`maxmemory` was reached under `noeviction` (the default), or under a `volatile-*` policy while the keys had no TTL, which evicts nothing (the lab showed both failing at about 24,000 keys). For a pure cache use `allkeys-lru` or `allkeys-lfu`, give every key a TTL with jitter anyway, and size memory with per-key overhead in mind (about 1.4 KB per 1 KB value in the lab). → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q148. 🔴 Is a Redis lock safe?</b></summary>

For efficiency, if done right: `SET key token NX PX ttl`, released by compare-and-delete (Lua, or `DELEX … IFEQ` on 8.4+), never a plain `DEL`, which in the lab deleted the next holder's lock. For correctness, no: a client that pauses past its lease still thinks it holds the lock. The protected resource must reject stale fencing tokens, or use a database mechanism. → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q149. 🔴 What breaks when you move from one Redis node to Redis Cluster?</b></summary>

Keys are spread over 16,384 hash slots, so multi-key commands, transactions and scripts across slots fail with `CROSSSLOT`; hash tags such as `{user:42}:cart` keep related keys in one slot, at the risk of concentrating a big tenant on one shard. Clients must follow `MOVED` and `ASK` redirects and keep a slot map, and pipelines are split per node. → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q150. 🔴 How do you design a Cassandra table?</b></summary>

From the query, one table per access pattern. The partition key decides where data lives and must spread load and bound partition size (add a time bucket); clustering columns decide the order inside a partition. Do the size arithmetic, read and write at `LOCAL_QUORUM` with RF = 3 for read-your-writes, and never use it as a queue: deletes leave tombstones that every read must scan. → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q151. 🔴 A DynamoDB table throttles while most of its capacity is unused. Why?</b></summary>

A hot partition key: one partition serves at most 3,000 RCU and 1,000 WCU, whatever the table's total. Typical causes are a status or date as the partition key, a global counter, or a low-cardinality GSI. Use higher-cardinality keys, write sharding (`OPEN#0` … `OPEN#9`), caching for hot reads and sparse indexes. → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q152. 🟣 When is DynamoDB single-table design right?</b></summary>

When access patterns are known and stable, and related items should come back in one `Query` (a customer and their newest orders in one item collection, other lookups through overloaded GSIs). It's a mistake for exploratory or analytical queries, or for entities owned by different teams. The principle is "keys designed for access patterns", not "one table". → [Ch 34](34-data-stores-redis-cassandra-dynamodb.md)
</details>

<details>
<summary><b>Q153. 🟡 What is RAG, and why not fine-tune instead?</b></summary>

Retrieval-augmented generation retrieves the passages relevant to a question from your own data, puts them in the prompt, and asks the model to answer from them, with citations. It suits knowledge that is private, changes often or must be traceable: updating it means re-indexing documents. Fine-tuning changes behaviour and style, and is a poor way to teach facts that change. → [Ch 35](35-ai-features-spring-ai-langchain4j-rag.md)
</details>

<details>
<summary><b>Q154. 🔴 What does HNSW trade, and which knob do you turn per query?</b></summary>

Recall for speed. It's a layered proximity graph that compares the query with a small fraction of the vectors; `efSearch` sets how many candidates the search keeps. In the lab (50,000 vectors), `efSearch` 40 gave recall@10 of 0.995 in 66 µs, against 2,761 µs for an exact scan; `efSearch` 10 dropped recall to 0.846. The graph costs memory and build time. → [Ch 35](35-ai-features-spring-ai-langchain4j-rag.md)
</details>

<details>
<summary><b>Q155. 🔴 A tenant-filtered vector search returns 3 results when you asked for 10. Why?</b></summary>

The index searched first and the filter ran afterwards, so most nearest neighbours belonged to other tenants: in the lab, top-40 then filtering returned 0.4 results on average. Filter inside the index search, partition by tenant, or scan the tenant's rows exactly (88 µs for 500 rows, perfect recall). → [Ch 35](35-ai-features-spring-ai-langchain4j-rag.md)
</details>

<details>
<summary><b>Q156. 🔴 How do you get model output your code can trust?</b></summary>

Constrain the format at the source (JSON-schema structured output), parse strictly, repair cheap problems such as code fences, then validate business rules in code, and re-prompt with the specific error a limited number of times. In the lab, two replies parsed perfectly and were still wrong: a refund above the order total, and a different order. Treat IDs in the output as untrusted input. → [Ch 35](35-ai-features-spring-ai-langchain4j-rag.md)
</details>

<details>
<summary><b>Q157. 🟣 How do you protect an AI agent against prompt injection?</b></summary>

Assume the model will be fooled, and limit what a fooled model can do: least-privilege tools acting with the user's permissions, policies in code based on your data, human approval for irreversible actions, untrusted content processed without tools, and never private data, untrusted content and external communication in one agent (the lethal trifecta). Detection is only a speed bump: a regex blocklist caught 2 of 8 attacks in the lab and blocked a real customer. → [Ch 35](35-ai-features-spring-ai-langchain4j-rag.md)
</details>

<details>
<summary><b>Q158. 🔴 Your code runs the tool-calling loop. What must it enforce?</b></summary>

Reject unknown tools (models invent them); apply policy from your own data, not the model's words; validate arguments before asking anyone to approve; ask a person for irreversible or expensive actions; cap steps, tokens and cost; and log every call with its decision. The model never executes anything: your code does. → [Ch 35](35-ai-features-spring-ai-langchain4j-rag.md)
</details>

<details>
<summary><b>Q159. 🔴 How do you test an LLM feature?</b></summary>

Plain unit tests for everything around the model (tools, parsers, guardrails, policies), with a scripted model stub for the loop. An evaluation set of real questions with expected answers and, for RAG, expected passages, scored per stage (retrieval recall, accuracy, faithfulness via an LLM judge that people spot-check), run in CI on every prompt, model or retrieval change. In production: task success, feedback, guardrail triggers and cost. → [Ch 35](35-ai-features-spring-ai-langchain4j-rag.md)
</details>

---

## Where to go next
- Re-run every 🟨 and ❌ after two days, then after a week. The [flashcards](flashcards.md) put this bank and every chapter's questions into Anki, which schedules the repeats for you.
- In the last week, go through the [revision sheets](revision.md), one part a day.
- For each 🔴/🟣 miss, read the full question set in that chapter. Those answers go two or three follow-ups deeper than this bank.
- Do at least two timed mock interviews (one design, one coding) before the real thing, using the format and scoring sheet in [Chapter 33](33-annotated-mock-interviews.md), and record yourself answering three behavioural prompts from [Chapter 27](27-staff-architect-interview-playbook.md).

---

[⬅️ Prev: 27 · Staff & Architect Playbook](27-staff-architect-interview-playbook.md) · [🏠 Index](README.md) · [Next: 29 · Reactive Java ➡️](29-reactive-java-reactor-webflux.md)
