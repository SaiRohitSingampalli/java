[⬅️ Prev: Part II](revision-part-02-the-jvm.md) · [📋 Revision index](revision.md) · [Next: Part IV · Platform APIs ➡️](revision-part-04-platform-apis.md)

# Part III · Concurrency — revision sheet
*Chapters [11 · Foundations & the JMM](11-concurrency-foundations-jmm.md) · [12 · j.u.c & Loom](12-concurrency-advanced-juc-loom.md)*

## Rules
- **Happens-before edges:** program order · unlock → later lock of the same monitor · volatile write → later read of that field · `start()` → the started thread's actions · a thread's actions → `join()` returning · constructor end → `final` fields of a safely published object.
- **`volatile`** gives visibility and ordering, **never atomicity**: `count++` on a volatile still loses updates.
- **Safe publication:** a static initialiser, a `volatile` or atomic reference, a lock, a concurrent collection, or an immutable object with `final` fields. Double-checked locking needs `volatile` (or use the holder idiom).
- **Deadlock** needs all four Coffman conditions (mutual exclusion, hold-and-wait, no preemption, circular wait). A global lock order breaks circular wait; `tryLock(timeout)` with back-off, releasing what you hold, breaks no preemption.
- **`wait()` belongs in a `while` loop inside `synchronized`**; prefer `notifyAll` or explicit `Condition`s.
- **`ThreadLocal` in pools:** always `remove()` in `finally`.
- **`ThreadPoolExecutor`:** core threads → queue → extra threads up to max **only when the queue is full** → rejection policy. `newFixedThreadPool` has an unbounded queue, so build your own with a bounded queue and a rejection policy.
- **Sizing:** CPU-bound ≈ `N_cpu` (+1). Blocking work: `N_cpu × U_target × (1 + W/C)`, then cap by downstream capacity (connection pools).
- **`CompletableFuture`:** `thenApply` maps, `thenCompose` flat-maps, `thenCombine` joins two; `…Async` without an executor uses the common pool; add `orTimeout`/`completeOnTimeout`.
- **Counters under contention:** `LongAdder`. Use `AtomicLong` when you need CAS or exact reads.
- **Virtual threads:** cheap, unmount on blocking, **never pooled**; limit access to scarce resources with a `Semaphore`. Pinning inside `synchronized` affects JDK 21–23 (fixed in 24, JEP 491); native frames still pin. Structured concurrency is still a preview in 25–27.

## Measured in the chapters
| Experiment | Result |
|---|---|
| Two threads × 1,000,000 `count++` | 1,792,260 · 2,000,000 · 1,247,677: lost updates, but not every run |
| Non-volatile stop flag | The worker *never* saw `stop = true` once the JIT compiled the loop |
| 10,000 / 100,000 virtual threads sleeping 1 s (2 cores) | ~1.1 s / ~1.3 s; 2,000 tasks on 200 platform threads took ~10 s |
| Blocking inside `synchronized`, 200 virtual threads | 5,049 ms on JDK 21 vs 81 ms on JDK 25 |

## Traps
- **Thread-starvation deadlock:** pool threads waiting on tasks queued to the same pool. The JVM's deadlock detector doesn't see it.
- **`synchronized` on a value-based object** (`Integer`, string literals) shares the lock with unrelated code.
- **Check-then-act on concurrent maps:** use `putIfAbsent`, `compute` or `merge`, not `containsKey` + `put`.
- **Recursive `computeIfAbsent`:** `HashMap` throws `ConcurrentModificationException`; `ConcurrentHashMap` throws `IllegalStateException: Recursive update`.
- **Parallel streams or the common pool for blocking I/O** starve everything else that shares it.

## Commands
- `jcmd <pid> Thread.print`, three times about 5 s apart; with virtual threads, `jcmd <pid> Thread.dump_to_file -format=json <file>`.
- JFR event `jdk.VirtualThreadPinned`; `ThreadMXBean.findDeadlockedThreads()` as a health metric.

## Say it in one breath
- "`volatile` gives visibility and ordering, never atomicity."
- "Happens-before is a promise about visibility, not about timing."
- "A thread pool grows beyond its core size only when the queue is full, so an unbounded queue means it never does."
- "Virtual threads make blocking cheap; they don't make your database bigger."

---

[⬅️ Prev: Part II](revision-part-02-the-jvm.md) · [📋 Revision index](revision.md) · [Next: Part IV · Platform APIs ➡️](revision-part-04-platform-apis.md)
