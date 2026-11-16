[⬅️ Prev: Part III](revision-part-03-concurrency.md) · [📋 Revision index](revision.md) · [Next: Part V · Design ➡️](revision-part-05-design.md)

# Part IV · Platform APIs — revision sheet
*Chapters [13 · I/O, NIO, Networking & Serialization](13-io-nio-networking-serialization.md) · [14 · Reflection, Annotations, Proxies & Agents](14-reflection-annotations-proxies.md) · [15 · Modules & Build Tools](15-modules-and-build-tools.md)*

## I/O and networking
- **Bytes vs characters:** streams move bytes, readers and writers move characters. Always name the charset; UTF-8 has been the default since JDK 18 (JEP 400).
- **NIO buffers:** `position ≤ limit ≤ capacity`. `flip()` after writing, before reading; `clear()` to start over; `compact()` to keep unread bytes.
- **Selectors** multiplex many non-blocking channels on one thread: the basis of Netty and event loops.
- **Zero-copy:** `FileChannel.transferTo` uses `sendfile` on Linux. **mmap** (`MappedByteBuffer`) suits random access to large files. **Direct buffers** live off-heap and count against `MaxDirectMemorySize`.
- **Files safely:** write to a temporary file, then `Files.move(…, ATOMIC_MOVE)`. Prevent path traversal by normalising, then checking `startsWith(base)`. Close the stream returned by `Files.lines`.
- **`HttpClient`** (JDK 11+): HTTP/2, synchronous and asynchronous; always set connect and request timeouts.
- **Serialisation:** never deserialise untrusted bytes with Java serialisation (gadget chains lead to RCE). Use `ObjectInputFilter` allow-lists if you must; prefer JSON or Protobuf. Jackson 3 lives in `tools.jackson` packages.

## Reflection, proxies, agents
- **`MethodHandle`** is access-checked once and JIT-friendly when held in a `static final`. Core reflection has been built on method handles since JDK 18.
- **Annotation retention:** `SOURCE` (compiler and processors), `CLASS` (bytecode tools), `RUNTIME` (reflection). Since **JDK 23**, annotation processors on the class path no longer run implicitly; use `-proc:full` or `--processor-path`.
- **Proxies:** JDK proxies need interfaces; ByteBuddy/CGLIB subclass proxies can't handle `final`. **Self-invocation bypasses Spring's proxy**, so `@Transactional` and `@Async` silently don't apply.
- **Class-File API** (final in JDK 24) for reading and writing bytecode without ASM.
- **Agents:** `premain` (`-javaagent`) or `agentmain` (dynamic attach, which warns since JDK 21). They transform classes as they load.
- **Strong encapsulation** (JDK 16/17): deep reflection into JDK internals needs `--add-opens`.
- **`ServiceLoader`** with `META-INF/services` or `provides`/`uses`: plugin discovery without frameworks.

## Modules and builds
- **`module-info`:** `requires` (dependencies), `exports` (public API), `opens` (deep reflection, for frameworks), `uses`/`provides` (services).
- **Smaller runtimes:** `jdeps --print-module-deps app.jar`, then `jlink --add-modules … --strip-debug --no-header-files --no-man-pages`.
- **Maven picks the *nearest* version, not the highest.** Control it with BOMs and `dependencyManagement`, inspect with `mvn dependency:tree -Dverbose`, enforce with the Enforcer plugin's `dependencyConvergence`.
- **Gradle:** default to `implementation`; use `api` only for types that appear in your public signatures.
- **Conflicting transitive versions:** shade *with relocation*, or align versions with a platform BOM.
- **Containers:** extracting Spring Boot's executable JAR (`java -Djarmode=tools -jar app.jar extract --layers`) gives cacheable Docker layers, and extraction is the first step of the AOT-cache workflow ([Ch30](30-startup-footprint-aot-native-image.md)).
- **Supply chain:** reproducible builds, SBOMs (CycloneDX or SPDX), checksum and signature verification.

## Say it in one breath
- "Readers and writers decode with a charset; never rely on the platform default for data that crosses machines."
- "Java serialisation of untrusted data is remote code execution waiting to happen."
- "Maven's nearest-wins rule surprises everyone once; a BOM ends the surprises."

---

[⬅️ Prev: Part III](revision-part-03-concurrency.md) · [📋 Revision index](revision.md) · [Next: Part V · Design ➡️](revision-part-05-design.md)
