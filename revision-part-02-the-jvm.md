[⬅️ Prev: Part I](revision-part-01-language-foundations.md) · [📋 Revision index](revision.md) · [Next: Part III · Concurrency ➡️](revision-part-03-concurrency.md)

# Part II · The JVM — revision sheet
*Chapters [09 · JVM Internals](09-jvm-internals.md) · [10 · Memory & GC](10-memory-and-garbage-collection.md)*

## Numbers to know
| Fact | Value |
|---|---|
| Object header | 12 bytes (8-byte mark word + 4-byte compressed class pointer); **8 bytes** with compact object headers (opt-in in JDK 25, the default in JDK 27) |
| Sizes with classic headers | 8-byte alignment; `new Object()` and `Integer` are 16 bytes, `Long` 24, `new byte[0]` 16 |
| Compressed oops | 4-byte references below 32 GB of heap. **The 32 GB cliff:** at `-Xmx32g` and above every reference doubles to 8 bytes |
| Compilation tiers | 0 interpreter · 1–3 C1 (3 = with full profiling) · 4 C2 |
| G1 | About 2,048 regions of 1–32 MB (up to 512 MB since JDK 18); **humongous** = at least half a region; default pause target `MaxGCPauseMillis=200` |
| Default max heap | 25% of RAM or of the container limit (`MaxRAMPercentage=25`); set 60–75% for a container dedicated to one JVM |
| Generational ZGC | Added in JDK 21, ZGC's default mode in 23, its only mode in 24; pauses typically under a millisecond |

## Rules
- **Class loading:** bootstrap → platform → application, parent-first delegation. A class's identity is (name, defining loader). Loading → linking (verify, prepare, resolve) → initialisation: `<clinit>` runs once, thread-safely, on first active use.
- **The JIT speculates:** inlining, devirtualisation, removing unlikely branches. **Deoptimisation** is the safety net when an assumption breaks (a new subclass loads, an "impossible" branch runs).
- **Escape analysis** lets C2 scalar-replace objects that don't escape (no allocation at all) and remove their locks.
- **Safepoints:** time-to-safepoint can stall every thread without showing up as GC time (`-Xlog:safepoint`).
- **Choosing a GC:** G1 by default; generational ZGC for low latency on large heaps (costs CPU and headroom); Parallel for batch throughput; Serial for tiny, latency-tolerant containers. Until JDK 27 small machines and containers silently got Serial; JEP 523 makes G1 the default everywhere.
- **Upgrading to JDK 27 moves baselines:** compact headers by default (JEP 534) and G1 everywhere (JEP 523) change heap, RSS and pause numbers. Re-baseline dashboards and alerts.
- **References:** soft (memory-sensitive caches, sparingly), weak (canonicalising maps, listeners), phantom/`Cleaner` (cleanup after death). Finalization is deprecated for removal.
- **Containers:** RSS = heap + metaspace + code cache + thread stacks + GC structures + direct buffers + native memory. Size the heap with that in mind.

## Which OutOfMemoryError?
| Message or symptom | Usually means | First tool |
|---|---|---|
| `Java heap space` | Live set exceeds `-Xmx`, or a leak | Two `GC.class_histogram`s, then a heap dump |
| `Metaspace` | Class or class-loader leak | `VM.classloader_stats`, GC-root paths to loaders |
| `Cannot reserve … direct buffer memory` | Off-heap buffers | NMT ("Other"), `BufferPoolMXBean` |
| `unable to create native thread` | Thread count or OS limits | Thread count, `ulimit`, stack size |
| `GC overhead limit exceeded` (Parallel GC) | Almost all time in GC, little reclaimed | GC log |
| Pod `OOMKilled`, exit code 137, no Java error | The kernel enforced the container limit on RSS | NMT diff, container metrics |

## Commands and flags
- `-Xlog:gc*:file=gc.log:time,uptime,level,tags`: always on in production.
- `jcmd <pid> GC.heap_info` · `GC.class_histogram` · `GC.heap_dump <file>` · `VM.flags` · `VM.native_memory summary` (with `-XX:NativeMemoryTracking=summary`).
- `javap -c -p -v <Class>`: bytecode, constant pool, bootstrap methods.
- `-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<dir>`: fail fast, keep the evidence.

## Say it in one breath
- "Most objects die young, so collect the young space often and cheaply by copying the few survivors."
- "G1 collects regions with the most garbage first, within a pause target; ZGC does almost everything concurrently with load barriers."
- "OOMKilled means the kernel, not the JVM: look at RSS and non-heap memory, not heap dashboards."
- "The JIT's best optimisations are speculative; deoptimisation makes them safe."

---

[⬅️ Prev: Part I](revision-part-01-language-foundations.md) · [📋 Revision index](revision.md) · [Next: Part III · Concurrency ➡️](revision-part-03-concurrency.md)
