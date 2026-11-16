[📋 Revision index](revision.md) · [Next: Part II · The JVM ➡️](revision-part-02-the-jvm.md)

# Part I · Language Foundations — revision sheet
*Chapters [01](01-java-platform-fundamentals.md) · [02](02-oop-and-object-model.md) · [03](03-strings.md) · [04](04-exceptions.md) · [05](05-generics.md) · [06](06-collections.md) · [07](07-lambdas-streams-functional.md) · [08](08-modern-java-8-to-27.md)*

## Numbers to know
| Fact | Value |
|---|---|
| Class-file major versions | 52 (Java 8) · 55 (11) · 61 (17) · 65 (21) · 69 (25) · 71 (27). Target older runtimes with `--release N` |
| LTS releases | 8, 11, 17, 21, **25** (current); next is **29** (September 2027). Feature releases every March and September |
| `float` / `double` significand | 24 / 53 bits. "Widening" `int` 123_456_789 to `float` and back gives 123_456_792; above 2⁵³, `double` can't represent every integer |
| `Integer` cache | −128 … 127. Outside it, `==` on boxed values compares references |
| `HashMap` | Load factor 0.75, power-of-two table, index `(n − 1) & (h ^ h >>> 16)`. A bin treeifies at 8 (only if the table is ≥ 64), back to a list at 6 |
| `ArrayList` growth | About 1.5× per resize; `add` at the end is amortised O(1) |

## Rules
- **Always pass-by-value.** Object references are copied; reassigning a parameter never affects the caller.
- **Initialisation order:** parent statics → child statics (once per class) → for each `new`: the `super()` chain → field initialisers and instance blocks in textual order → the constructor body. Overridable methods called from a constructor see default field values (`null`).
- **Overloading is resolved at compile time** in three phases (no boxing or varargs → boxing → varargs), choosing the most specific method. **Overriding is resolved at run time.** Fields and static methods are *hidden*, never overridden.
- **`equals`/`hashCode`:** equal objects must have equal hash codes. Never mutate a field used in them while the object is a map key.
- **Records** are shallowly immutable: copy mutable components (`List.copyOf`) in the compact constructor. **Sealed + records + pattern `switch`** give exhaustive, `default`-free switches.
- **Strings:** compare with `equals`. Constant expressions are folded by javac. Since JDK 9 a `String` is a `byte[]` plus a coder (compact strings), and `+` compiles to one `invokedynamic` (`makeConcatWithConstants`).
- **try-with-resources** closes in reverse order; exceptions from `close()` become *suppressed*. Never `return` from `finally`.
- **Generics are erased:** no `new T[]`, no `instanceof List<String>`. PECS: `? extends` to read, `? super` to write. Bridge methods keep overriding working after erasure.
- **Collections:** use `ArrayDeque` for stacks and queues. Fail-fast iterators are best-effort. Delete during iteration with `removeIf` or `Iterator.remove`. `ConcurrentHashMap` forbids `null`.
- **Streams** are lazy and vertical (each element passes through every stage), and short-circuit. Parallel streams share the JVM-wide common `ForkJoinPool`.
- **Context per request:** scoped values (final in JDK 25) instead of `ThreadLocal`, especially with virtual threads.

## Traps
| Trap | What happens → fix |
|---|---|
| `(a, b) -> a - b` comparator | Overflows and mis-sorts → `Integer.compare`, `Comparator.comparingInt` |
| `Integer` compared with `==` | `1000 == 1000` is false → `equals` |
| `new BigDecimal(0.1)` | Exact binary value `0.1000000000000000055…` → `BigDecimal.valueOf(0.1)` or `new BigDecimal("0.1")` |
| `list.remove(1)` on a `List<Integer>` | Removes *index* 1 → `remove(Integer.valueOf(1))` |
| `Arrays.asList(arr)` | Fixed-size and writes through to the array: `add`/`remove` throw → `new ArrayList<>(…)` or `List.of` |
| `"a.b.c".split(".")` | An empty array (`.` is a regex) → `split("\\.")` or `Pattern.quote(".")` |
| `toLowerCase()` without a locale | Turkish dotless ı → `toLowerCase(Locale.ROOT)` for identifiers |
| Removing the second-to-last element in a for-each | No exception, last element silently skipped → `removeIf` |
| `optional.orElse(expensive())` | Always evaluated → `orElseGet` |
| Nested or ambiguous regex quantifiers | ReDoS: `^(\w+\s?){1,100}$` took ~66 s on 10 words plus `!` (JDK 21) → unambiguous patterns, input-length limits |

## Say it in one breath
- "Java compiles to bytecode; the JVM interprets it, JIT-compiles hot code with C1 then C2, and deoptimises when a speculation fails."
- "The compiler picks the overload from static types; the JVM picks the override from the runtime type."
- "`HashMap`: spread the hash, mask by size minus one, chain, treeify long chains, resize at 75%."
- "Records carry values with shallow immutability; sealed types make switches exhaustive and new subtypes a compile error."
- "Erasure is why generics can't create arrays or check `instanceof` on type arguments, and why bridge methods exist."

---

[📋 Revision index](revision.md) · [Next: Part II · The JVM ➡️](revision-part-02-the-jvm.md)
