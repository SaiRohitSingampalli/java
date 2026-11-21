#!/usr/bin/env bash
# Lab:       Which JVM flags from your deployment manifests stop a JVM from starting, and which are silently ignored
# Chapters:  37 (fleet JDK upgrade: inventory and rollback limits), 27 §7
# Run:       ./jvm-flags-check.sh                       (checks the built-in list with the `java` on your PATH)
#            ./jvm-flags-check.sh "-XX:Foo" "-Dbar=1"   (checks your own flags; one quoted argument per flag set)
# Needs:     a JDK on the PATH; run it once with the JDK you're leaving and once with the one you're moving to
# Verified:  JDK 17.0.21, 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
# Sources:   JEP 486 (Security Manager permanently disabled): https://openjdk.org/jeps/486
#            JEP 490 (ZGC non-generational mode removed): https://openjdk.org/jeps/490
#            JEP 471 (sun.misc.Unsafe memory access deprecated, with the --sun-misc-unsafe-memory-access option):
#            https://openjdk.org/jeps/471
# Output:    one line per flag set: whether the JVM starts, and the first warning or error it prints.
set -u
if [ "$#" -gt 0 ]; then flags=("$@"); else
  flags=("-XX:+UseConcMarkSweepGC" "--illegal-access=permit" "-XX:+UseBiasedLocking" "-XX:+UseZGC -XX:-ZGenerational"
         "-Djava.security.manager=allow" "-XX:+UseCompactObjectHeaders" "-XX:AOTCache=app.aot"
         "--sun-misc-unsafe-memory-access=deny" "--enable-native-access=ALL-UNNAMED")
fi
printf 'JDK %s\n' "$(java -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
for f in "${flags[@]}"; do
  # shellcheck disable=SC2086   # a flag set may hold several flags
  out=$(java $f -version 2>&1); status=$?
  note=$(printf '%s\n' "$out" | grep -v -E '^openjdk version|^OpenJDK Runtime Environment|^OpenJDK 64-Bit Server VM \(build' | head -1 | cut -c1-110)
  if [ "$status" -ne 0 ]; then verdict="WON'T START"; elif [ -n "$note" ]; then verdict="starts, but"; else verdict="starts"; fi
  printf '  %-38s %-12s %s\n' "$f" "$verdict" "$note" | sed 's/[[:space:]]*$//'
done
