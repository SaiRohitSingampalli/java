[⬅️ Prev: 13 · I/O & NIO](13-io-nio-networking-serialization.md) · [🏠 Index](README.md) · [Next: 15 · Modules & Build Tools ➡️](15-modules-and-build-tools.md)

# 14 · Reflection, Annotations, Proxies & Agents

> *Every "magic" thing a Java framework does (dependency injection, `@Transactional`, mocks, ORMs, APM agents) is built from the four tools in this chapter. Once you understand them, the magic becomes predictable, and so do its failure modes.*

---

## Table of contents
1. [Reflection: the runtime view of your code](#1-reflection-the-runtime-view-of-your-code-)
2. [Method handles & the cost of reflection](#2-method-handles--the-cost-of-reflection-)
3. [Annotations: metadata, retention, meta-annotations](#3-annotations-metadata-retention-meta-annotations-)
4. [Compile-time annotation processing](#4-compile-time-annotation-processing-)
5. [Dynamic proxies: JDK vs subclass proxies](#5-dynamic-proxies-jdk-vs-subclass-proxies-)
6. [Bytecode generation: ASM, ByteBuddy, Class-File API, hidden classes](#6-bytecode-generation-asm-bytebuddy-class-file-api-hidden-classes-)
7. [Java agents & instrumentation](#7-java-agents--instrumentation-)
8. [`ServiceLoader` and the SPI pattern](#8-serviceloader-and-the-spi-pattern-)
9. [Integrity by default: where the JDK is heading](#9-integrity-by-default-where-the-jdk-is-heading-)
10. [Staff lens](#10-staff-lens-)
11. [Interview questions](#11-interview-questions)
12. [References](#12-references)

---

## 1. Reflection: the runtime view of your code 🟡

```java
Class<?> c1 = Order.class;                         // class literal: no initialization
Class<?> c2 = order.getClass();                    // runtime class of an instance
Class<?> c3 = Class.forName("com.acme.Order");     // load AND initialize by name

for (Field f : Order.class.getDeclaredFields()) { ... }    // ALL fields declared here (any access), no inherited ones
for (Method m : Order.class.getMethods()) { ... }          // PUBLIC methods, including inherited ones

Constructor<Order> ctor = Order.class.getDeclaredConstructor(String.class);
Order o = ctor.newInstance("A-1");                 // (Class.newInstance() is deprecated: it leaked checked exceptions)

Method total = Order.class.getMethod("total");
Object result = total.invoke(o);                   // exceptions arrive wrapped in InvocationTargetException

Field secret = Order.class.getDeclaredField("internalNotes");
secret.setAccessible(true);                        // suppress access checks (subject to module rules!)
secret.set(o, "edited");
```

**`getX` vs `getDeclaredX`:** `getMethods()` returns *public* members, **including inherited** ones. `getDeclaredMethods()` returns *all* members **declared by this class only**. To walk private fields of the whole hierarchy, loop over `getSuperclass()`.

**Modern reflection additions:** `Class.isRecord()` / `getRecordComponents()` (16), `isSealed()` / `getPermittedSubclasses()` (17), `isHidden()` (15), plus generic signatures through `getGenericType()` and friends ([Chapter 05 §5](05-generics.md#5-type-erasure-bridge-methods--what-survives-)).

### Reflection and modules
Since Java 9, `setAccessible(true)` only works if the target package is **open** to your module. Since Java 17 (JEP 403), JDK internals are strictly closed:
```
java.lang.reflect.InaccessibleObjectException: Unable to make field private final byte[]
  java.lang.String.value accessible: module java.base does not "opens java.lang" to unnamed module @1b6d3586
```
Fix it by upgrading the library that does this. If you can't yet, add `--add-opens java.base/java.lang=ALL-UNNAMED` as a **documented, temporary** workaround. For your own modules, declare `opens com.acme.model;` (or `opens … to com.fasterxml.jackson.databind`) in `module-info.java`.

---

## 2. Method handles & the cost of reflection 🔴

**Why reflection was historically slow:** every `Method.invoke` boxes arguments into an `Object[]`, checks access, unwraps exceptions, and is opaque to the JIT. HotSpot used to start with a native accessor and, after about 15 calls, generate a bytecode accessor ("inflation").

**Since Java 18 (JEP 416), core reflection is implemented on top of method handles.** Performance is more uniform, and when the `Method` is in a `static final` field, the JIT can optimise through it.

**`MethodHandle`** (Java 7) is a typed, directly executable reference to a method, constructor or field accessor:
```java
private static final MethodHandle LENGTH;
static {
    try {
        LENGTH = MethodHandles.lookup()
                .findVirtual(String.class, "length", MethodType.methodType(int.class));
    } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
}

int n = (int) LENGTH.invokeExact("hello");   // 5. A static final MH is a constant to the JIT, so it inlines like a direct call
```
- **Access is checked once**, when the handle is looked up from a `Lookup`, rather than on every call.
- `invokeExact` requires an exact type match (cast the result exactly). `invoke` adapts types.
- **Constant method handles get inlined.** This is the engine behind `invokedynamic` (lambdas, string concatenation, records) ([Chapter 09 §6](09-jvm-internals.md#6-the-five-invoke-instructions-)).
- `MethodHandles.privateLookupIn(Target.class, lookup)` gives deep access to another module's class, *if that module opens the package to you*.

**Practical guidance for framework-style code:** look things up once, cache `Method`/`MethodHandle`/`VarHandle` objects in `static final` fields, and never call `getDeclaredMethods()` in a hot path (it copies arrays every time).

---

## 3. Annotations: metadata, retention, meta-annotations 🟡→🔴

An annotation is **structured metadata** attached to code. On its own it does nothing. Something has to *read* it: the compiler, an annotation processor, or a framework using reflection at runtime.

```java
@Retention(RetentionPolicy.RUNTIME)           // visible to reflection at runtime
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Documented
public @interface MaxLength {
    int value();                              // a single member named "value" allows @MaxLength(64)
    String message() default "too long";
}
```

| Retention | Kept in | Read by | Examples |
|---|---|---|---|
| `SOURCE` | Source only | Compiler and annotation processors | `@Override`, `@SuppressWarnings`, Lombok |
| `CLASS` (default) | Class file, not loaded at runtime | Bytecode tools | Some static-analysis annotations |
| `RUNTIME` | Class file **and** runtime | Reflection | `@Transactional`, `@Entity`, `@Test`, `@JsonProperty` |

**Meta-annotations:**
- `@Target` says where the annotation is allowed: `TYPE`, `METHOD`, `FIELD`, `PARAMETER`, `CONSTRUCTOR`, `TYPE_USE` (Java 8: anywhere a type is used, e.g. `List<@NonNull String>`), `RECORD_COMPONENT` (16), `MODULE`, and others.
- `@Inherited`: a **class-level** annotation is inherited by subclasses. It does **not** apply to methods or interfaces, a frequent misconception.
- `@Repeatable(Container.class)`: allows several instances on one element (Java 8).
- Member types are limited to primitives, `String`, `Class`, enums, other annotations, and arrays of those. Values must be compile-time constants.

**A runtime processor, in miniature:**
```java
static List<String> validate(Object target) throws IllegalAccessException {
    List<String> errors = new ArrayList<>();
    for (Field f : target.getClass().getDeclaredFields()) {
        MaxLength rule = f.getAnnotation(MaxLength.class);
        if (rule == null) continue;
        f.setAccessible(true);
        if (f.get(target) instanceof String s && s.length() > rule.value())
            errors.add(f.getName() + ": " + rule.message());
    }
    return errors;
}
```
That's Bean Validation (`jakarta.validation`) in a dozen lines, minus caching and composition.

🔴 **Fun fact:** at runtime, annotation instances are **JDK dynamic proxies**. `rule.getClass()` prints something like `jdk.proxy2.$Proxy12`, backed by an internal `AnnotationInvocationHandler`.

**Composed annotations (Spring).** Spring reads *meta-annotations*: `@RestController` is itself annotated with `@Controller` and `@ResponseBody`, so it behaves like both. You can build your own, such as `@TransactionalService = @Service + @Transactional`. Plain `getAnnotation` doesn't see meta-annotations. Spring's `MergedAnnotations` API does.

---

## 4. Compile-time annotation processing 🔴

**Annotation processors** run *inside javac*, in rounds. They inspect annotated elements through the `javax.lang.model` API and can **generate new source files** (they can't modify existing ones, officially).

```java
@SupportedAnnotationTypes("com.acme.GenerateBuilder")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class BuilderProcessor extends AbstractProcessor {
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        for (Element e : env.getElementsAnnotatedWith(GenerateBuilder.class)) {
            // inspect the record or class components, then write OrderBuilder.java via processingEnv.getFiler()
        }
        return true;
    }
}
```

**Who uses it:** MapStruct (mappers), Dagger and Micronaut (**dependency injection with no runtime reflection**), Immutables and AutoValue, Hibernate's JPA metamodel generator, Spring Boot's configuration-metadata processor, and Spring's `spring-context-indexer`.

**Why it matters architecturally:** moving work from runtime reflection to **build-time code generation** gives faster startup, less memory, errors at compile time instead of in production, and **GraalVM native-image compatibility** (the closed-world assumption dislikes reflection). This is the core idea behind Micronaut, Quarkus' build-time processing, and Spring's AOT engine.

> [!NOTE]
> **JDK 23 changed the default.** javac no longer runs annotation processors it discovers *implicitly* on the class path. You have to request processing explicitly (`-processor`, `--processor-path`, or `-proc:full`). Maven (`annotationProcessorPaths`) and Gradle (the `annotationProcessor` configuration) already pass processors explicitly, but hand-rolled builds and old IDE setups can silently stop generating code after an upgrade.

**Lombok is different.** It isn't a standard processor. It hooks into javac's **internal AST** to *modify* your classes (adding getters, builders, `equals`). That's powerful, but it depends on compiler internals, so it historically broke on JDK upgrades until a new release came out. For data carriers, **records** now remove most of the reason to use it.

---

## 5. Dynamic proxies: JDK vs subclass proxies 🔴

A **proxy** is an object that stands in for another and intercepts calls. That's how cross-cutting behaviour (transactions, caching, security, retries, metrics) gets added without changing the code being proxied.

### JDK dynamic proxies (interfaces only)
```java
@SuppressWarnings("unchecked")
static <T> T timed(T target, Class<T> iface) {
    return (T) Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[]{iface},
            (proxy, method, args) -> {
                long t0 = System.nanoTime();
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();                 // unwrap: callers expect the real exception
                } finally {
                    System.out.printf("%s took %d µs%n", method.getName(), (System.nanoTime() - t0) / 1_000);
                }
            });
}

OrderService service = timed(new DefaultOrderService(), OrderService.class);
service.place(order);     // → InvocationHandler → DefaultOrderService.place
```
At runtime, the JDK generates a class (`$Proxy42`) that **implements the interfaces**. Every method, including `equals`, `hashCode` and `toString`, calls `handler.invoke(proxy, method, args)`.

### Subclass proxies (CGLIB, ByteBuddy)
They generate a **subclass** of the target class at runtime and override every non-final method to add the interception. That works without interfaces, but:
- **`final` classes and `final` methods can't be proxied.** The interception is silently skipped for final methods 💣.
- **`private` methods can't be intercepted** (they aren't virtual).
- The constructor of the proxied class can run twice (once for the proxy instance). Objenesis tricks avoid that.

| | JDK `Proxy` | CGLIB / ByteBuddy subclass |
|---|---|---|
| Requires | Interface(s) | A non-final class |
| Mechanism | Generated class implementing the interfaces | Generated subclass overriding methods |
| Can't intercept | Methods not on the interface | `final`, `private`, `static` methods |
| Used by | Spring Data repositories, Feign and HTTP interface clients, Java annotations, Spring AOP with interfaces (optional) | **Spring AOP by default (Boot: `proxyTargetClass=true`)**, `@Configuration` classes, Hibernate lazy proxies, Mockito |

### The universal proxy pitfall: self-invocation 💣
```java
@Service
public class OrderService {
    public void placeAll(List<Order> orders) {
        orders.forEach(this::place);        // `this` is the RAW object, not the proxy: no transaction!
    }
    @Transactional
    public void place(Order o) { ... }
}
```
The proxy wraps the object from the **outside**. Calls arriving through the proxy are intercepted. Calls an object makes **to itself** go straight to `this` and bypass it. This one fact explains the majority of "`@Transactional` / `@Cacheable` / `@Async` doesn't work" questions ([Chapter 18](18-spring-internals.md)). Fixes: move the method to another bean, inject the proxy of yourself (`@Lazy` self-injection or `ObjectProvider`), or use AspectJ weaving, which modifies the bytecode itself instead of proxying.

💣 **Kotlin classes are `final` by default**, so Spring's subclass proxies can't extend them. The `kotlin-spring` (all-open) compiler plugin opens classes annotated with Spring stereotypes.

---

## 6. Bytecode generation: ASM, ByteBuddy, Class-File API, hidden classes 🔴

| Tool | Level | Used by |
|---|---|---|
| **ASM** | Low-level visitor over class files | Nearly everything underneath: Spring (repackaged), Gradle, Kotlin, Groovy, JaCoCo, older JDK internals |
| **ByteBuddy** | High-level fluent API on top of ASM | Mockito, Hibernate, many APM agents (via OpenTelemetry's instrumentation) |
| Javassist | Source-like strings | Older Hibernate, some frameworks |
| CGLIB | Subclass proxies | Unmaintained upstream; Spring maintains its own fork |
| **Class-File API** (`java.lang.classfile`) | JDK-standard parse, transform and generate | **Final in Java 24 (JEP 484)**; the JDK's own tools are migrating to it |

**Why the JDK built its own:** class-file formats now change every six months. Tools that bundled a specific ASM version broke on every new JDK until ASM shipped an update and the tool re-released. A JDK-bundled API is always in sync with the JDK it ships in.

```java
// Java 24+: list a class's methods with the Class-File API
ClassModel cm = ClassFile.of().parse(Path.of("Order.class"));
for (MethodModel m : cm.methods()) {
    System.out.println(m.methodName().stringValue() + m.methodType().stringValue());
}
```

**Hidden classes** (Java 15, JEP 371): `Lookup.defineHiddenClass(bytes, …)` defines classes that **can't be found by name**, can't be referenced by other classes' bytecode, and **can be unloaded independently** of their class loader. Lambdas and frameworks use them for generated proxies and accessors, which avoids class-loader leaks and Metaspace growth.

---

## 7. Java agents & instrumentation 🔴

A **Java agent** is a JAR whose code runs **before `main`** (static) or **is attached to a running JVM** (dynamic). It gets an `Instrumentation` instance that can **transform class bytecode as classes load**.

```java
public final class TimingAgent {
    public static void premain(String args, Instrumentation inst) {        // static: -javaagent:timing-agent.jar
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String className,
                                    Class<?> classBeingRedefined, ProtectionDomain pd, byte[] classBytes) {
                if (className == null || !className.startsWith("com/acme/")) return null;  // null = unchanged
                return addTimingProbes(classBytes);       // rewrite with ByteBuddy / ASM / Class-File API
            }
        }, /* canRetransform */ true);
    }
    public static void agentmain(String args, Instrumentation inst) { premain(args, inst); }  // dynamic attach
}
```
```
# MANIFEST.MF
Premain-Class: com.acme.agent.TimingAgent
Agent-Class: com.acme.agent.TimingAgent
Can-Retransform-Classes: true
```

**What `Instrumentation` can do:**
- `addTransformer`: rewrite classes as they load (or on retransform).
- `retransformClasses` / `redefineClasses`: change *already loaded* classes. **Only method bodies.** You can't add or remove fields or methods, or change signatures.
- `getObjectSize(obj)`: shallow size of an object (what JOL-style tools build on).
- `appendToBootstrapClassLoaderSearch`, `redefineModule`, and more.

**Who uses agents:** APM and tracing (**the OpenTelemetry Java agent**, Datadog, New Relic, Dynatrace), code coverage (**JaCoCo**), Mockito's inline mock maker (to mock final classes), hot-reload tools (JRebel), fault injection (Byteman), and security agents (RASP).

> [!WARNING]
> **Dynamic agent loading is being phased out.** Since Java 21 (JEP 451), attaching an agent to a *running* JVM prints a warning. A future release will disallow it unless the JVM starts with `-XX:+EnableDynamicAgentLoading`. Agents loaded at startup with `-javaagent` aren't affected. Tools that self-attach (some mocking setups, profilers) need that flag or a switch to `-javaagent`.

🧠 **Agent governance is a staff concern.** An APM agent rewrites your entire application at load time. It adds startup time, memory and CPU overhead, it can conflict with other agents, and it has to support every JDK you upgrade to. **Your JDK upgrade schedule is gated by your agents' compatibility.** Track agent versions alongside JDK versions, and load-test with the agent attached.

---

## 8. `ServiceLoader` and the SPI pattern 🟡

The **Service Provider Interface** pattern decouples an API from its implementations, which are discovered at runtime:
```java
// API module defines an interface
public interface PaymentGateway { boolean supports(String method); Receipt charge(Payment p); }

// Provider JAR declares an implementation in:
//   META-INF/services/com.acme.spi.PaymentGateway   (contents: com.acme.stripe.StripeGateway)
// or, as a module:  provides com.acme.spi.PaymentGateway with com.acme.stripe.StripeGateway;

// Consumer discovers implementations (as a module it also declares: uses com.acme.spi.PaymentGateway;)
ServiceLoader<PaymentGateway> loader = ServiceLoader.load(PaymentGateway.class);
PaymentGateway gw = loader.stream()                     // Java 9: lazy providers, no instantiation yet
        .map(ServiceLoader.Provider::get)
        .filter(g -> g.supports("card"))
        .findFirst()
        .orElseThrow();
```
**In the wild:** JDBC drivers (`DriverManager` finds them automatically, which is why `Class.forName("org.postgresql.Driver")` stopped being necessary), SLF4J 2.x logging backends, Jackson modules (`findAndRegisterModules`), `java.net.spi`, charset providers. Spring Boot's `AutoConfiguration.imports` is a similar but *Spring-specific* discovery file ([Chapter 18](18-spring-internals.md)).

💣 `ServiceLoader.load(Class)` uses the **thread context class loader**. In containers and plugin systems, the wrong TCCL means "no providers found".

---

## 9. Integrity by default: where the JDK is heading 🟣

The JDK has a stated principle: *integrity by default*. Code shouldn't be able to break other code's invariants, or the platform's, without explicit, visible permission from whoever launches the application.

| Change | Release | Affects |
|---|---|---|
| Strong encapsulation of JDK internals | 16–17 (JEPs 396, 403) | Deep reflection into `java.*` and `sun.*` |
| Warnings for dynamic agent loading | 21 (JEP 451) | Self-attaching tools |
| `sun.misc.Unsafe` memory access deprecated, then warned | 23–24 (JEPs 471, 498) | Low-level libraries (being migrated to `VarHandle`/FFM) |
| Restricted JNI and FFM require `--enable-native-access` | 24 (JEP 472) | Native libraries |
| Security Manager permanently disabled | 24 (JEP 486) | Legacy sandboxing |
| Warnings for mutating `final` fields reflectively | 26 (JEP 500) | Serialization and DI libraries, mocking |

**What it means in practice:** each of these surfaces as a *warning* first, then an *error* in a later release. Treat JVM warnings in CI logs as **deprecation notices for your dependency graph**. Pin the flags you need explicitly, and document why, so the next person upgrading can remove them.

---

## 10. Staff lens 🟣

1. **Know the cost of the magic.** Reflection- and proxy-heavy frameworks trade startup time, memory and debuggability for convenience. For serverless and native targets, prefer build-time approaches (Spring AOT, Micronaut, Quarkus, Dagger, MapStruct).
2. **Teach the proxy model org-wide.** Self-invocation, final methods, private methods and proxy-vs-target identity cause a steady stream of "works locally, not in production" bugs. One internal guide saves hundreds of hours.
3. **Keep the domain model free of framework magic.** Annotations are fine as *metadata* at the edges (persistence, serialization, web). Business rules inside annotation processors or aspects are invisible to readers and hard to test.
4. **Govern agents and `--add-opens` flags.** Keep an inventory with owners and justifications. Each one is a liability at the next JDK upgrade.
5. **Plan the integrity transition.** Libraries that write `final` fields, use `Unsafe` or call JNI need upgrades or replacements on a timeline, not when the warning becomes an error.

---

## 11. Interview questions

<details>
<summary><b>Q1. 🟢 What's the difference between <code>getMethods()</code> and <code>getDeclaredMethods()</code>?</b></summary>

`getMethods()` returns all *public* methods, including inherited ones (from superclasses and interfaces). `getDeclaredMethods()` returns all methods of any access level *declared in that class only*, without inherited ones. The same distinction applies to fields and constructors.
</details>

<details>
<summary><b>Q2. 🔴 Why can <code>setAccessible(true)</code> throw <code>InaccessibleObjectException</code> on modern JDKs?</b></summary>

JPMS strong encapsulation: deep reflection requires the target package to be *opened* to the caller's module. Since JDK 17 (JEP 403), JDK internals aren't open to application code, so reflecting into, say, `String.value` fails. Fix it by upgrading the library, by using supported APIs (`VarHandle`, `MethodHandles.privateLookupIn` with proper opens), or temporarily with `--add-opens`.
</details>

<details>
<summary><b>Q3. 🔴 Reflection vs <code>MethodHandle</code>: why are method handles faster?</b></summary>

Method handles check access once, at lookup time, are strongly typed (`invokeExact`), and, when held in `static final` fields, are constants the JIT can inline like direct calls. Classic reflection boxed arguments and checked access per call, and was harder to optimise. Since JDK 18, core reflection itself is built on method handles, which narrows the gap. Cache handles rather than looking them up repeatedly.
</details>

<details>
<summary><b>Q4. 🟡 Explain annotation retention policies.</b></summary>

`SOURCE`: discarded after compilation, read by the compiler and processors (`@Override`). `CLASS` (the default): stored in the class file but not available through reflection. `RUNTIME`: stored and readable via reflection (`@Transactional`, `@Entity`). Frameworks that act at runtime need `RUNTIME`.
</details>

<details>
<summary><b>Q5. 🟡 Does <code>@Inherited</code> make method annotations inherited?</b></summary>

No. It only affects *class-level* annotations, and only through the superclass chain, not interfaces. Frameworks like Spring implement their own lookup algorithms that walk interfaces, bridge methods and meta-annotations, which is why some annotations "inherit" in Spring but not with plain reflection.
</details>

<details>
<summary><b>Q6. 🔴 How do annotation processors differ from Lombok?</b></summary>

Standard annotation processors (JSR 269) run during compilation, read program elements through `javax.lang.model`, and generate *new* sources or resources. They can't modify existing classes. Lombok hooks into javac internals to modify the AST of your classes directly. That's powerful, but it couples it to compiler internals and has caused breakages across JDK versions.
</details>

<details>
<summary><b>Q7. 🔴 JDK dynamic proxy vs CGLIB/ByteBuddy proxy?</b></summary>

A JDK proxy generates a class that implements given *interfaces* and routes every call to an `InvocationHandler`, so it can only proxy interface methods. Subclass proxies generate a subclass of the concrete class and override its methods, so no interface is needed, but `final` classes and methods, and `private` and `static` methods, can't be intercepted. Spring Boot uses subclass (CGLIB) proxies by default.
</details>

<details>
<summary><b>Q8. 🔴 Why doesn't <code>@Transactional</code> work when a method calls another method in the same class?</b></summary>

Spring applies it with a proxy around the bean. External calls go through the proxy, which starts the transaction. Internal calls (`this.method()`) go directly to the target object, bypassing the proxy and its advice. Fix it by moving the method to another bean, self-injecting the proxy, using `TransactionTemplate` programmatically, or switching to AspectJ weaving.
</details>

<details>
<summary><b>Q9. 🔴 What can a Java agent do, and what are its limits?</b></summary>

With `Instrumentation`, an agent can transform bytecode as classes load, retransform or redefine loaded classes, measure object sizes, and add JARs to class-loader search paths. Retransformation can only change method bodies: no adding or removing fields or methods, and no signature changes. Agents start via `-javaagent` (premain) or dynamic attach (agentmain), and dynamic attach now warns (JEP 451) and will eventually require an explicit opt-in.
</details>

<details>
<summary><b>Q10. 🟡 How does <code>ServiceLoader</code> discover implementations?</b></summary>

It reads `META-INF/services/<fully-qualified interface name>` files on the class path (each listing implementation classes), or `provides … with …` declarations from named modules, and instantiates providers lazily. `stream()` exposes `Provider` objects so you can filter by type before instantiating. It uses the thread context class loader by default, which matters in containers.
</details>

<details>
<summary><b>Q11. 🔴 Why did the JDK add the Class-File API when ASM exists?</b></summary>

The class-file format changes every six months, and every tool that bundled its own ASM version had to wait for an ASM update plus its own release before supporting a new JDK. A JDK-bundled API (final in Java 24, JEP 484) always matches the JDK it ships with, so the platform and its tools can evolve together.
</details>

<details>
<summary><b>Q12. 🟣 Your org is evaluating a move to GraalVM native images. What reflection-related work is needed?</b></summary>

Native image assumes a closed world: reflection, dynamic proxies, resources and serialization must be known at build time. That means reachability metadata (configuration or annotations), framework AOT support (Spring AOT, Micronaut and Quarkus build-time processing), replacing runtime-reflection libraries with code generation where possible (MapStruct instead of reflective mappers), the tracing agent during test runs to collect metadata, and extensive testing, since missing metadata fails at runtime. Budget for CI build time and for the debugging and profiling differences.
</details>

---

## 12. References
- [`java.lang.reflect`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/reflect/package-summary.html) and [`java.lang.invoke`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/invoke/package-summary.html) documentation
- JEPs: [416 Reimplement Core Reflection with Method Handles](https://openjdk.org/jeps/416) · [371 Hidden Classes](https://openjdk.org/jeps/371) · [484 Class-File API](https://openjdk.org/jeps/484) · [451 Disallow Dynamic Loading of Agents](https://openjdk.org/jeps/451) · [500 Prepare to Make Final Mean Final](https://openjdk.org/jeps/500)
- Rafael Winterhalter's ByteBuddy documentation and talks
- Spring Framework reference: *Proxying Mechanisms*

---

[⬅️ Prev: 13 · I/O & NIO](13-io-nio-networking-serialization.md) · [🏠 Index](README.md) · [Next: 15 · Modules & Build Tools ➡️](15-modules-and-build-tools.md)
