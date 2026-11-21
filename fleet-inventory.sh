#!/usr/bin/env bash
# Lab:       Inventory for a JDK upgrade: which libraries use JDK internals or APIs deprecated for removal
# Chapters:  37 (fleet JDK upgrade case study), 27 §7
# Run:       ./fleet-inventory.sh path/to/lib/*.jar          (uses jdeps and jdeprscan from the JDK on your PATH: use the TARGET JDK)
# Needs:     a JDK 21+ (bin/jdeps, bin/jdeprscan), bash, awk
# Verified:  JDK 25.0.5 on Linux x86-64 against Ubuntu's library JARs, 2026-11-20
# Sources:   jdeps: https://docs.oracle.com/en/java/javase/25/docs/specs/man/jdeps.html
#            jdeprscan: https://docs.oracle.com/en/java/javase/25/docs/specs/man/jdeprscan.html
# Output:    one row per JAR. Static findings only: run your tests with --sun-misc-unsafe-memory-access=deny
#            as well (see UnsafeProbe.java), because a library may fall back gracefully or fail at runtime.
set -u
if [ "$#" -eq 0 ]; then echo "usage: $0 file.jar [file.jar ...]" >&2; exit 2; fi
release=$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')
classpath=$(IFS=:; echo "$*")                     # every JAR on the class path, so jdeprscan can resolve references
printf 'Target release: %s\n\n' "$release"
printf '%-34s %-26s %s\n' "JAR" "JDK internals (jdeps)" "Deprecated for removal (jdeprscan --for-removal)"
for jar in "$@"; do
  internals=$(jdeps --jdk-internals --multi-release "$release" -q "$jar" 2>/dev/null \
              | awk '/JDK internal API/ {print $3}' | sort -u | paste -sd, -)
  deprecated=$(jdeprscan --release "$release" --for-removal --class-path "$classpath" "$jar" 2>/dev/null \
               | sed -nE 's#.*deprecated [a-z ]*((java|javax|jdk|sun)/[A-Za-z0-9_/$]+(::[A-Za-z0-9_$<>]+)?).*#\1#p' | sed 's#/#.#g' \
               | sort | uniq -c | sort -rn | awk '{printf "%s%s x%d", (NR>1?", ":""), $2, $1}')
  printf '%-34s %-26s %s\n' "$(basename "$jar")" "${internals:-none}" "${deprecated:-none}"
done
