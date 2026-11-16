[⬅️ Prev: Part VIII](revision-part-08-architecture.md) · [📋 Revision index](revision.md) · [Next: Part X · Specialist Topics ➡️](revision-part-10-specialist-topics.md)

# Part IX · Interview Mastery — revision sheet
*Chapters [26 · Coding & LLD Patterns](26-coding-and-lld-interview-patterns.md) · [27 · Staff & Architect Playbook](27-staff-architect-interview-playbook.md) · [28 · Question Bank](28-question-bank.md)*

## Coding rounds
**Scored on:** problem solving, correctness (empty input, duplicates, negatives, overflow), time *and* space complexity (including boxing and recursion depth), code quality, communication, testing.

**A 45-minute script:** clarify (3–5) → examples and edge cases (2) → brute force and its complexity (2) → find the pattern (5) → code (15–20) → test by hand (5) → complexity and follow-ups (3).

**Constraints give the target** (about 10⁸ simple operations per second):
| n | Feasible | n | Feasible |
|---|---|---|---|
| ≤ 10 | O(n!) | ≤ 5,000 | O(n²) |
| ≤ 20 | O(2ⁿ · n) | ≤ 10⁶ | O(n log n) or O(n) |
| ≤ 500 | O(n³) | ≥ 10⁹ | O(log n) or O(1) |

**Problem wording → pattern:**
| The problem says… | Reach for |
|---|---|
| Sorted array, pairs, in place | Two pointers |
| Contiguous subarray or substring under a constraint | Sliding window |
| Subarray sum equals k, negatives allowed | Prefix sums + hash map |
| "The minimum X such that…" | Binary search on the answer |
| Next greater/smaller, spans | Monotonic stack |
| Max/min of every window | Monotonic deque |
| k largest, k most frequent, merge k lists, running median | Heap |
| Overlapping meetings, merging ranges | Sort + sweep |
| Fewest steps, level order | BFS |
| Generate all combinations or permutations | Backtracking |
| Prerequisites, build order | Topological sort (Kahn) |
| Connectivity, cycles in an undirected graph | Union-Find |
| Shortest path, non-negative weights | Dijkstra |
| Count the ways, minimum cost, overlapping subproblems | Dynamic programming |
| Prefix search, autocomplete | Trie |
| Linked-list cycle or middle in O(1) space | Fast & slow pointers |

**Java traps that break correct algorithms (all measured in Ch26):** subtraction comparators overflow · `==` on boxed values above 127 · `Math.abs(Integer.MIN_VALUE)` is negative → `Math.floorMod` · `-7 % 3 == -1` · `List<Integer>.remove(1)` removes index 1 · `Arrays.asList(new int[]{…})` has size 1 · `(lo + hi) / 2` overflows → `(lo + hi) >>> 1` · arrays as map keys use identity · recursive `computeIfAbsent` throws · the default stack held ~21K trivial frames.

**Concurrency problems → primitive:** strict turn-taking → `wait`/`notifyAll` in a `while` loop or a semaphore per turn · "B after A" → `CountDownLatch` or `CompletableFuture` · bounded producer–consumer → `ArrayBlockingQueue` (or `Lock` + two `Condition`s) · fixed groups (H₂O) → semaphores + `CyclicBarrier` · at most N at once → `Semaphore` · several locks → a global lock order or `tryLock` with back-off · hot counter → `LongAdder` · periodic work → `ScheduledExecutorService`.

## LLD / machine-coding rounds
1. Clarify scope and write the feature list down.
2. Model with records (values), enums (closed sets) and sealed types (closed variants).
3. API first, including failure behaviour.
4. Patterns only where variation is real: Strategy (pricing, eviction), State (elevator, vending machine), Observer, Command (undo).
5. **State the invariants and where they're enforced** (one lock, an atomic operation, a concurrent collection).
6. Inject `Clock` or `LongSupplier` and ID generators.
7. Run it: a `main` or two tests with visible output.

Classic builds:
- **LRU:** `HashMap` + a doubly linked list with sentinel head and tail, for O(1) `get` and `put`.
- **TTL store:** lazy expiry on read plus a periodic sweep; `remove(key, expectedEntry)` so you never delete a newer value; `nanoTime` durations compared by subtraction; an injected clock.
- **Parking lot:** no explicit locks. `ConcurrentLinkedQueue.poll()` hands out spots atomically, `ConcurrentHashMap.remove()` lets exactly one caller close a ticket, and pricing is a Strategy.
- **Elevator:** the LOOK algorithm (keep going while stops lie ahead, then reverse) with two `TreeSet`s; a dispatcher scores cars when there are several.
- **Pub-sub:** a buffer and a (virtual) thread per subscriber, so a slow one can't slow the rest; the overflow policy (drop newest, drop oldest, block, disconnect) is a product decision; at-most-once unless you add a log plus offsets.

Graded on modelling, correctness, extensibility, readability, testability and **pragmatism** (no interface per class).

## Staff and architect rounds
- **What changes:** scope (several teams or a domain), horizon (2–4 quarters), output (decisions, alignment, unblocked teams), framing ambiguity, influence without authority. The technical bar doesn't drop; a second bar is added. Know your archetype: Tech Lead, Architect, Solver or Right Hand.
- **Every answer should make three things explicit:** **scope** (how far it reached), **impact** (what measurably changed), and **how** (the judgment and influence).
- **Project deep-dive:** context → problem with numbers → constraints → 2–3 options → decision → execution (your part vs the team's) → results → hindsight. Prepare 2–3 projects: technical depth, breadth and influence, recovery from a failure. Be precise about "I" and "we".
- **STAR+L:** situation (2 sentences), task, **action (most of the answer)**, measured result, learning. 2–3 minutes, then let them drill. Keep a bank of 8–10 stories covering influence, disagreement, failure, ambiguity, raising the bar, growing others, saying no, long migrations, deliberate debt and changing your mind.
- **Judgment phrases:** "The constraint that dominates is ___, so I'm optimising for ___ at the cost of ___." · two-way vs one-way doors · "What would change my mind is ___." · "The last responsible moment is ___."
- **Decision matrix:** agree the weights first (that's where the real disagreement lives); the matrix makes reasoning reviewable rather than deciding for you. Record it in an ADR.
- **Strategy (Rumelt):** diagnosis → guiding policy → coherent actions. A list of goals isn't a strategy.
- **Influence:** earn credibility by delivering; make the right thing the easy thing; bring data; pre-wire decisions; write it down; disagree and commit; give credit away.
- **Fleet Java upgrade:** inventory (`jdeps --jdk-internals`, `jdeprscan --for-removal`; risky bytecode and agent libraries first) → pave the road (base images, BOM, **nightly build on the next JDK**, OpenRewrite `UpgradeToJava25`) → decouple the risk (build with the new JDK at `--release` old → run on it → bump the target → adopt features) → waves with canaries (p99, GC, CPU, RSS, errors) → make it stick (dashboard, CI gate, support policy, automate the next upgrade).
- **Breaking points:** 8 → 11 Java EE modules removed · 11 → 17 strong encapsulation, CMS gone · 17 → 21 UTF-8 by default, dynamic-agent warnings · 21 → 25 Security Manager disabled, `Unsafe` memory-access warnings · Boot 3 `jakarta.*`, Boot 4 Jackson 3.
- **Virtual threads vs reactive:** default to **virtual threads with Spring MVC** for request/response on JDK 21+ (preferably 24+), bounding concurrency toward every dependency; reactive where you need streaming or end-to-end backpressure, or where the codebase and team already are. Load-test either way.
- **Sinking failure modes:** technology without impact, no numbers, hidden "I", credit-taking, decisions presented as obvious, one hammer, blaming others, rambling, being shallow on your own project, neglecting the coding round.
- **Ask them:** the biggest technical challenges for the next 12–18 months; how staff scope is defined; a recent contentious decision; what success looks like after a year.

## Using the question bank
Answer out loud for 60 seconds before expanding; mark ✅ / 🟨 / ❌; reread the chapter for every 🟨 and ❌, then retry after two days and again after a week. Staff candidates should be confident on every 🔴 and give structured answers to 🟣.

## Say it in one breath
- "That's 10¹⁰ operations, too slow; I need O(n log n)."
- "Let me state the invariant and where it's enforced."
- "The team built X; I designed the consistency model and wrote the migration plan."
- "I can go deeper on X or Y."

---

[⬅️ Prev: Part VIII](revision-part-08-architecture.md) · [📋 Revision index](revision.md) · [Next: Part X · Specialist Topics ➡️](revision-part-10-specialist-topics.md)
