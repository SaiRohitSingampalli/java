[🏠 Index](README.md)

# 🧪 Labs: the programs behind the outputs

Every output you see in the chapters (timings, GC logs, thread dumps, `javap` listings, test results) came from a real run of one of these programs. Run them, change them, break them. That's how the material sticks.

Snippets that need Spring, Reactor, gRPC, GraphQL, Spring AI, LangChain4j, a Redis client library, the Cassandra driver or the AWS SDK aren't here; those chapters say so where it matters.

## Setup

- **JDK 21 and JDK 25.** Most labs run on JDK 21 or newer. Where a lab needs a specific version, it's marked. Any OpenJDK distribution works.
- **Run a lab** straight from its source file: `java InitOrder.java` (no `javac` needed). Every file lives in one folder, so run commands from the repository folder. A few labs leave files behind (`hot.jfr`, `gc.log`, and a `build/` folder from the startup benchmark); delete them when you're done.
- **Optional libraries:**
  - Jackson 2.12+ (`jackson-databind`, `jackson-core`, `jackson-annotations`) for `StartupApp.java`, `ApiDemos.java` and `Guardrails.java`;
  - `protoc` plus `protobuf-java` **of the same version** for `ProtoDemo.java` and its three `.proto` files;
  - a throwaway Redis 7+ or Valkey server for `RedisLabs.java` (the lab flushes it, and refuses to touch a server that holds data).

  Set `JACKSON_CP` to the three Jackson JARs, colon-separated (use `;` on Windows).
- **Everything fast at once:** `./run-quick.sh` runs 24 self-contained labs in about a minute.
- **Numbers vary by machine.** Timings, memory figures and race outcomes depend on your hardware and JDK build. Compare the *relative* effects with the chapter, not the exact digits.

## Index

### Chapter 01 · Platform fundamentals
| Lab | Shows | Run |
|---|---|---|
| `InitOrder.java` | Static and instance initialisation order; a virtual call from a constructor sees `name=null` | `java InitOrder.java` |
| `Numbers.java` | Lossy widening, overflow, `Math.abs(MIN_VALUE)`, shifts, `0.1 + 0.2`, NaN | `java Numbers.java` |

### Chapter 02 · OOP and the object model
| Lab | Shows | Run |
|---|---|---|
| `Dispatch.java` | Field and static-method hiding vs dynamic dispatch; three-phase overload resolution | `java Dispatch.java` (uncomment `m(null)` to see the ambiguity error) |

### Chapter 03 · Strings
| Lab | Shows | Run |
|---|---|---|
| `StringEquality.java` | Pooling, constant folding, `intern()` | `java StringEquality.java` |
| `Concat.java` | `a + b + c` compiles to one `invokedynamic` | `javac -d /tmp/ch03 Concat.java && javap -c -v -cp /tmp/ch03 Concat \| grep -A2 makeConcat` |
| `ReDoS.java` | Catastrophic backtracking timings (the last case takes about a minute) | `java ReDoS.java` |

### Chapter 04 · Exceptions
| Lab | Shows | Run |
|---|---|---|
| `TryWithResources.java` | Reverse close order and suppressed exceptions | `java TryWithResources.java` |

### Chapter 05 · Generics
| Lab | Shows | Run |
|---|---|---|
| `IntNode.java` | The synthetic bridge method | `javac -d /tmp/ch05 IntNode.java && javap -p -cp /tmp/ch05 IntNode` |

### Chapter 06 · Collections
| Lab | Shows | Run |
|---|---|---|
| `HashBuckets.java` | Colliding hash codes and bucket indexes | `java HashBuckets.java` |
| `IteratorQuirk.java` | Removing the second-to-last element: no exception, an element skipped | `java IteratorQuirk.java` |

### Chapter 07 · Lambdas and streams
| Lab | Shows | Run |
|---|---|---|
| `LambdaBytecode.java` | A lambda is `invokedynamic` + `LambdaMetafactory` + a private method | `javac -d /tmp/ch07 LambdaBytecode.java && javap -c -p -v -cp /tmp/ch07 LambdaBytecode` |
| `VerticalStream.java` | Elements flow one at a time; short-circuiting | `java VerticalStream.java` |

### Chapter 08 · Modern Java
| Lab | Shows | Run |
|---|---|---|
| `ExprDemo.java` | Sealed interfaces, records and nested patterns with guards | `java ExprDemo.java` |

### Chapter 09 · JVM internals
| Lab | Shows | Run |
|---|---|---|
| `Bytecode.java` | Stack-machine bytecode, the desugared enhanced `for` | `javac -d /tmp/ch09 Bytecode.java && javap -c -p -cp /tmp/ch09 Bytecode` |
| `FieldOffsets.java` | Field reordering and header size (JDK 24+ prints `Unsafe` deprecation warnings) | `java FieldOffsets.java`. On JDK 25, add `-XX:+UseCompactObjectHeaders` and compare |

### Chapter 10 · Memory and GC
| Lab | Shows | Run |
|---|---|---|
| `Churn.java` | Young collections in a G1 log | `java -Xmx64m -Xlog:gc* Churn.java` |
| `Humongous.java` | `G1 Humongous Allocation` collections | `java -Xmx64m -XX:G1HeapRegionSize=1m -Xlog:gc Humongous.java` |

### Chapter 11 · Concurrency foundations
| Lab | Shows | Run |
|---|---|---|
| `LostUpdate.java` | `count++` loses updates (run it a few times) | `java LostUpdate.java` |
| `StopFlag.java` | A non-volatile flag the worker never sees (needs the JIT; takes ~4 s) | `java StopFlag.java` |
| `Deadlock.java` | A lock-ordering deadlock | `java Deadlock.java`, then in another terminal `jcmd` (lists JVMs) and `jcmd <pid> Thread.print`. Stop with Ctrl+C |

### Chapter 12 · Advanced concurrency and Loom
| Lab | Shows | Run |
|---|---|---|
| `OneShotLatchAndForkJoin.java` | A synchroniser built on AQS; a fork/join sum | `java OneShotLatchAndForkJoin.java` |
| `VirtualThreads.java` | 10k and 100k virtual threads vs 200 platform threads (~13 s) | `java VirtualThreads.java` |

### Chapter 17 · Design patterns
| Lab | Shows | Run |
|---|---|---|
| `OrderStateDemo.java` | An enum state machine that rejects illegal transitions | `java OrderStateDemo.java` |

### Chapter 21 · Performance and troubleshooting
| Lab | Shows | Run |
|---|---|---|
| `Hot.java` | CPU and allocation hotspots found with JFR (runs 15 s). JDK 21 reproduces the chapter's profile; on JDK 25 the concatenation cost shows up inside `Hot.render` instead | `java -XX:StartFlightRecording=duration=30s,filename=hot.jfr,settings=profile Hot.java`, then `jfr view hot-methods hot.jfr` and `jfr view allocation-by-site hot.jfr` |

### Chapter 24 · System design
| Lab | Shows | Run |
|---|---|---|
| `Ring.java` | Consistent hashing vs `hash % N`: balance and keys moved | `java Ring.java` |

### Chapter 26 · Coding and LLD
| Lab | Shows | Run |
|---|---|---|
| `Patterns.java` | The 15 pattern templates, with tests | `java Patterns.java` |
| `Traps.java` | Comparator overflow, `Integer` cache, recursion depth and other traps | `java Traps.java` |
| `ConcurrencyProblems.java` | Turn-taking, bounded buffer, H₂O, dining philosophers, a thread pool, a crawler | `java ConcurrencyProblems.java` |
| `LowLevelDesigns.java` | LRU, TTL cache, parking lot, elevator, pub-sub broker | `java LowLevelDesigns.java` |
| `RecursiveComputeIfAbsent.java` | `ConcurrentHashMap` rejects recursive `computeIfAbsent` | `java RecursiveComputeIfAbsent.java` |

### Chapter 29 · Reactive Java
| Lab | Shows | Run |
|---|---|---|
| `FlowDemos.java` | The Reactive Streams protocol traced, backpressure, hot vs cold, blocking an event loop | `java FlowDemos.java` |

### Chapter 30 · Startup and footprint
| Lab | Shows | Run |
|---|---|---|
| `StartupApp.java` + `startup-bench.py` | CDS vs AppCDS (JDK 21) vs AOT cache (JDK 25), measured | `JDK21=<jdk21 home> JDK25=<jdk25 home> JACKSON_CP=<jars> python3 startup-bench.py` (Linux/macOS) |
| `Footprint.java` | Bytes per object with and without compact headers (**JDK 25**) | `java -Xmx2g -XX:-UseCompactObjectHeaders Footprint.java`, then the same with `-XX:+UseCompactObjectHeaders` |
| Native memory | Where JVM memory goes | `java -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -cp "$JACKSON_CP" StartupApp.java` |

### Chapter 31 · API design between services
| Lab | Shows | Run |
|---|---|---|
| `ApiDemos.java` | ETags (304/412/428), offset vs cursor pagination, Jackson evolution failures | `java -cp "$JACKSON_CP" ApiDemos.java` |
| `ProtoDemo.java` + `order_v1.proto`, `order_v2.proto`, `order_v3.proto` | Unknown fields survive; a reused field number corrupts data | `protoc --proto_path=. --java_out=/tmp/pb order_v1.proto order_v2.proto order_v3.proto && javac -cp protobuf-java.jar -d /tmp/pb/classes $(find /tmp/pb -name '*.java') ProtoDemo.java && java -cp /tmp/pb/classes:protobuf-java.jar ProtoDemo` |

### Chapter 32 · Troubleshooting scenarios
| Lab | Case | Run |
|---|---|---|
| `DirectLeak.java` | 1: OOMKilled while the heap looks healthy | `java -Xmx256m -XX:MaxDirectMemorySize=64m -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics DirectLeak.java` |
| `PoolDeadlock.java` | 2: thread-starvation deadlock (exits after 60 s) | `java PoolDeadlock.java`, then `jcmd <pid> Thread.print` while it hangs |
| `Pinning.java` | 3: virtual-thread pinning | Run with **JDK 21** and **JDK 25**: `java Pinning.java synchronized` and `java Pinning.java ReentrantLock`. On JDK 21, add `-Djdk.tracePinnedThreads=short` |
| `MetaspaceLeak.java` + `Plugin.java` | 4: a class-loader leak | `javac -d /tmp/ch32 MetaspaceLeak.java Plugin.java && java -XX:MaxMetaspaceSize=48m -cp /tmp/ch32 MetaspaceLeak` |
| `SessionLeak.java` | 5: a heap leak found by histogram diffs (runs until stopped) | `java -Xmx512m SessionLeak.java`, then `jcmd <pid> GC.class_histogram` twice, a few seconds apart |
| `GcThrash.java` | 6: the GC death spiral | `java -Xmx128m -Xlog:gc:file=gc.log GcThrash.java`, then read `gc.log` |
| `PoolExhaustion.java` | 7: pool timeouts while the database is idle | `java PoolExhaustion.java` |
| `HotThread.java` | 8: one thread at 100% CPU (exits after 120 s) | `java HotThread.java`, then `top -H -p <pid>` and `jcmd <pid> Thread.print` |

### Chapter 33 · Mock interviews
| Lab | Shows | Run |
|---|---|---|
| `KeyedRateLimiter.java` | The per-key token bucket from the coding mock, with deterministic tests | `java KeyedRateLimiter.java` |

### Chapter 34 · Data stores
Start a throwaway server first: `redis-server --port 6380 --save '' --appendonly no --daemonize yes`. The experiments talk to it with a small RESP client written in the lab, so no client library is needed.

| Lab | Shows | Run |
|---|---|---|
| `RedisLabs.java` | Pipelining with and without network latency; `KEYS` vs `SCAN`; `DEL` vs `UNLINK`; lost updates and four fixes; eviction policies; a lock whose lease expires; streams with a crashed consumer | `java RedisLabs.java all localhost 6380` (or one experiment: `pipeline`, `keys`, `unlink`, `counter`, `eviction`, `lock`, `streams`) |
| `HashSlots.java` | Redis Cluster key-to-slot mapping and hash tags | `java HashSlots.java`; to check it against a server: `redis-server --port 7000 --cluster-enabled yes --save '' --daemonize yes`, then `java HashSlots.java 7000` |

### Chapter 35 · AI features
No model and no network: these labs cover the parts of an AI feature that are plain Java.

| Lab | Shows | Run |
|---|---|---|
| `VectorSearch.java` | Exact search vs an HNSW index (recall and latency as `efSearch` grows), int8 and 1-bit quantisation with re-scoring, filtered search (about 20 s) | `java -Xmx2g VectorSearch.java` |
| `SimdDot.java` | Brute-force dot products: scalar loop, four accumulators, Vector API. Try it on **JDK 21** and **JDK 25** | `java --add-modules jdk.incubator.vector -Xmx1g SimdDot.java`, then with `5000` as the argument for an in-cache run |
| `Guardrails.java` | Structured output (parse, repair, validate), a prompt-injection blocklist and how it fails, PII redaction | `java -cp "$JACKSON_CP" Guardrails.java` |
| `ToolLoop.java` | The tool-calling loop with a scripted model: argument checks, policy, approval, unknown tools, a step limit | `java ToolLoop.java` |

---

[🏠 Index](README.md)
