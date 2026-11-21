#!/usr/bin/env bash
# Runs every lab on one or more JDKs, checks each exit status against what the lab is meant to do, and prints
# a lab-by-JDK matrix. This is how the "Verified:" line in each lab's header is earned.
#
# Usage:   ./verify-labs.sh [--quick] [--with-bench] [JDK_HOME ...]
#          ./verify-labs.sh /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-25-openjdk-amd64
#          With no JDK_HOME, it uses the `java` on your PATH.
#   --quick       skip the slow labs (each over about 30 seconds: ReDoS, HotThread, PoolDeadlock, VtDecision ...)
#   --with-bench  also run startup-bench.py once (3 runs per configuration); needs a JDK 21 and a JDK 25 home
#
# Optional dependencies (labs that need a missing one are reported as skipped, not failed):
#   JACKSON_CP     jackson-databind, jackson-core and jackson-annotations JARs, colon-separated
#   GUAVA_JAR      a Guava JAR, and PROTOBUF_JAR a protobuf-java JAR (Chapter 37's Unsafe probe and inventory)
#   protoc         on the PATH, the same version as PROTOBUF_JAR (Chapter 31's ProtoDemo)
#   REDIS_PORT     the port of a THROWAWAY Redis 7+ or Valkey server; RedisLabs flushes it
#   timeout        GNU timeout (or gtimeout on macOS): needed to stop the labs that hang by design
#   VERIFY_OUT     where to keep each lab's output (default: a new folder under ${TMPDIR:-/tmp})
#
# Exit status: 0 if every lab that ran ended as expected and no quoted output has drifted, 1 otherwise.
# Expected statuses are explicit: 0 for most labs, 124 for labs that hang by design and are stopped by `timeout`.
# At the end, check_quotes.py (needs python3) compares the outputs the chapters quote with what the labs printed.
set -u
cd "$(dirname "$0")"

quick=0; bench=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --quick) quick=1; shift ;;
    --with-bench) bench=1; shift ;;
    -h|--help) sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    --) shift; break ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
if [ "$#" -eq 0 ]; then
  javabin=$(command -v java) || { echo "no java on the PATH; pass a JDK home" >&2; exit 2; }
  set -- "$(dirname "$(dirname "$javabin")")"
fi

timeout_cmd=""
if command -v timeout >/dev/null 2>&1; then timeout_cmd=timeout
elif command -v gtimeout >/dev/null 2>&1; then timeout_cmd=gtimeout; fi

base="${VERIFY_OUT:-${TMPDIR:-/tmp}/verify-labs-$(date +%Y%m%d-%H%M%S)}"
if command -v locale >/dev/null 2>&1 && [ "$(locale charmap 2>/dev/null)" != "UTF-8" ]; then
  echo "note: the locale isn't UTF-8, so labs print non-ASCII characters as '?' (the quote check allows for that)"
fi
mkdir -p "$base"
original_path="$PATH"
JACKSON_CP="${JACKSON_CP:-}"; GUAVA_JAR="${GUAVA_JAR:-}"; PROTOBUF_JAR="${PROTOBUF_JAR:-}"; REDIS_PORT="${REDIS_PORT:-}"
passed=0; failed=0; skipped=0

missing_need() {   # prints why a lab can't run here (the first missing dependency), or nothing
  local need
  for need in $(printf '%s' "$1" | tr ',' ' '); do
    case "$need" in
      -) ;;
      jackson) [ -n "$JACKSON_CP" ] || { echo "no JACKSON_CP"; return; } ;;
      unsafe-libs) { [ -n "$GUAVA_JAR" ] && [ -n "$PROTOBUF_JAR" ]; } || { echo "no Guava/protobuf JARs"; return; } ;;
      protoc) { command -v protoc >/dev/null 2>&1 && [ -n "$PROTOBUF_JAR" ]; } || { echo "no protoc/PROTOBUF_JAR"; return; } ;;
      redis) [ -n "$REDIS_PORT" ] || { echo "no REDIS_PORT"; return; } ;;
      timeout) [ -n "$timeout_cmd" ] || { echo "no timeout command"; return; } ;;
      slow) [ "$quick" -eq 0 ] || { echo "slow: --quick"; return; } ;;
    esac
  done
}

# lab TITLE EXPECTED_STATUS TIME_LIMIT_SECONDS MIN_JDK NEEDS COMMAND...
lab() {
  local title="$1" expected="$2" limit="$3" min="$4" needs="$5"; shift 5
  local result missing file status start end
  count=$((count + 1))
  missing=$(missing_need "$needs")
  if [ "$feature" -lt "$min" ]; then
    result="skipped (needs JDK $min+)"; skipped=$((skipped + 1))
  elif [ -n "$missing" ]; then
    result="skipped ($missing)"; skipped=$((skipped + 1))
  else
    file="$out/$(printf '%02d' "$count")-$(printf '%s' "$title" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9' '-' | sed 's/--*/-/g; s/-$//').txt"
    start=$(date +%s)
    if [ -n "$timeout_cmd" ]; then "$timeout_cmd" "$limit" "$@" > "$file" 2>&1; status=$?
    else "$@" > "$file" 2>&1; status=$?; fi
    end=$(date +%s)
    if [ "$status" -eq "$expected" ]; then
      result="ok, $((end - start)) s"; passed=$((passed + 1))
    else
      result="FAILED: exit $status, expected $expected"; failed=$((failed + 1))
    fi
  fi
  printf '%s\t%s\n' "$title" "$result" >> "$results"
  printf '  %-50s %s\n' "$title" "$result"
}

all_labs() {
  local w="$out/work"
  mkdir -p "$w"
  lab "01 InitOrder"                             0  60 21 -        java InitOrder.java
  lab "01 Numbers"                               0  60 21 -        java Numbers.java
  lab "02 Dispatch"                              0  60 21 -        java Dispatch.java
  lab "03 StringEquality"                        0  60 21 -        java StringEquality.java
  lab "03 Concat (javac, javap)"                 0  60 21 -        sh -c "javac -d '$w/ch03' Concat.java && javap -c -v -cp '$w/ch03' Concat | grep -A2 makeConcat"
  lab "03 ReDoS"                                 0 300 21 slow     java ReDoS.java
  lab "04 TryWithResources"                      0  60 21 -        java TryWithResources.java
  lab "05 IntNode (javac, javap)"                0  60 21 -        sh -c "javac -d '$w/ch05' IntNode.java && javap -p -cp '$w/ch05' IntNode"
  lab "06 HashBuckets"                           0  60 21 -        java HashBuckets.java
  lab "06 IteratorQuirk"                         0  60 21 -        java IteratorQuirk.java
  lab "07 LambdaBytecode (javac, javap)"         0  60 21 -        sh -c "javac -d '$w/ch07' LambdaBytecode.java && javap -c -p -v -cp '$w/ch07' LambdaBytecode"
  lab "07 VerticalStream"                        0  60 21 -        java VerticalStream.java
  lab "08 ExprDemo"                              0  60 21 -        java ExprDemo.java
  lab "09 Bytecode (javac, javap)"               0  60 21 -        sh -c "javac -d '$w/ch09' Bytecode.java && javap -c -p -cp '$w/ch09' Bytecode"
  lab "09 FieldOffsets"                          0  60 21 -        java FieldOffsets.java
  lab "09 FieldOffsets, compact headers"         0  60 25 -        java -XX:+UseCompactObjectHeaders FieldOffsets.java
  lab "10 Churn (GC log)"                        0 120 21 -        java -Xmx64m -Xlog:gc* Churn.java
  lab "10 Humongous (GC log)"                    0  60 21 -        java -Xmx64m -XX:G1HeapRegionSize=1m -Xlog:gc Humongous.java
  lab "11 LostUpdate"                            0  60 21 -        java LostUpdate.java
  lab "11 StopFlag"                              0  60 21 -        java StopFlag.java
  lab "11 Deadlock (hangs by design)"          124   5 21 timeout  java Deadlock.java
  lab "12 OneShotLatchAndForkJoin"               0  60 21 -        java OneShotLatchAndForkJoin.java
  lab "12 VirtualThreads"                        0 120 21 -        java VirtualThreads.java
  lab "17 OrderStateDemo"                        0  60 21 -        java OrderStateDemo.java
  lab "21 Hot (JFR recording)"                   0 120 21 -        java -XX:StartFlightRecording=duration=30s,filename="$w/hot.jfr",settings=profile Hot.java
  lab "21 jfr view hot-methods"                  0  60 21 -        jfr view hot-methods "$w/hot.jfr"
  lab "23/36 PaymentIdempotency (self-checking)" 0 120 21 -        java PaymentIdempotency.java
  lab "24 Ring"                                  0  60 21 -        java Ring.java
  lab "24 ShortCodes (self-checking)"            0  60 21 -        java ShortCodes.java
  lab "26 Patterns (self-checking)"              0  60 21 -        java Patterns.java
  lab "26 Traps"                                 0  60 21 -        java Traps.java
  lab "26 ConcurrencyProblems"                   0 120 21 -        java ConcurrencyProblems.java
  lab "26 LowLevelDesigns"                       0  60 21 -        java LowLevelDesigns.java
  lab "26 RecursiveComputeIfAbsent"              0  60 21 -        java RecursiveComputeIfAbsent.java
  lab "29 FlowDemos"                             0  60 21 -        java FlowDemos.java
  lab "30 StartupApp"                            0  60 21 jackson  java -cp "$JACKSON_CP" StartupApp.java
  lab "30 StartupApp, native memory summary"     0  60 21 jackson  java -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -cp "$JACKSON_CP" StartupApp.java
  lab "30 Footprint, headers off"                0 120 25 -        java -Xmx2g -XX:-UseCompactObjectHeaders Footprint.java
  lab "30 Footprint, compact headers"            0 120 25 -        java -Xmx2g -XX:+UseCompactObjectHeaders Footprint.java
  lab "31 ApiDemos"                              0  60 21 jackson  java -cp "$JACKSON_CP" ApiDemos.java
  lab "31 ProtoDemo (protoc, javac, java)"       0 120 21 protoc   sh -c "rm -rf '$w/pb' && mkdir -p '$w/pb/classes' && protoc --proto_path=. --java_out='$w/pb' order_v1.proto order_v2.proto order_v3.proto && javac -cp '$PROTOBUF_JAR' -d '$w/pb/classes' \$(find '$w/pb' -name '*.java') ProtoDemo.java && java -cp '$PROTOBUF_JAR:$w/pb/classes' ProtoDemo"
  lab "32 DirectLeak (javac, java)"              0  60 21 -        sh -c "javac -d '$w/ch32-direct' DirectLeak.java && java -Xmx256m -XX:MaxDirectMemorySize=64m -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -cp '$w/ch32-direct' DirectLeak"
  lab "32 PoolDeadlock (hangs 60 s, then exits)" 0 120 21 slow     java PoolDeadlock.java
  lab "32 Pinning, synchronized"                 0  60 21 -        java Pinning.java synchronized
  lab "32 Pinning, ReentrantLock"                0  60 21 -        java Pinning.java ReentrantLock
  lab "32 MetaspaceLeak (javac, java)"           0 120 21 -        sh -c "javac -d '$w/ch32' MetaspaceLeak.java Plugin.java && java -XX:MaxMetaspaceSize=48m -cp '$w/ch32' MetaspaceLeak"
  lab "32 SessionLeak (runs until stopped)"    124   5 21 timeout  java -Xmx512m SessionLeak.java
  lab "32 GcThrash"                              0 120 21 -        java -Xmx128m -Xlog:gc:file="$w/gc.log" GcThrash.java
  lab "32 PoolExhaustion"                        0  60 21 -        java PoolExhaustion.java
  lab "32 HotThread (busy for 2 min, then exits)" 0 180 21 slow    java HotThread.java
  lab "33 KeyedRateLimiter (self-checking)"      0  60 21 -        java KeyedRateLimiter.java
  lab "34 HashSlots"                             0  60 21 -        java HashSlots.java
  lab "34 RedisLabs"                             0 600 21 redis,slow java RedisLabs.java all localhost "$REDIS_PORT"
  lab "35 VectorSearch"                          0 180 21 slow     java -Xmx2g VectorSearch.java
  lab "35 SimdDot"                               0 180 21 -        java --add-modules jdk.incubator.vector -Xmx1g SimdDot.java
  lab "35 Guardrails"                            0  60 21 jackson  java -cp "$JACKSON_CP" Guardrails.java
  lab "35 ToolLoop"                              0  60 21 -        java ToolLoop.java
  lab "37 fleet-inventory.sh"                    0 300 21 unsafe-libs,slow ./fleet-inventory.sh "$GUAVA_JAR" "$PROTOBUF_JAR"
  lab "37 jvm-flags-check.sh"                    0 120 17 -        ./jvm-flags-check.sh
  lab "37 UnsafeProbe"                           0  60 17 unsafe-libs java -cp "$GUAVA_JAR:$PROTOBUF_JAR" UnsafeProbe.java
  lab "37 UnsafeProbe, access denied"            0  60 23 unsafe-libs java --sun-misc-unsafe-memory-access=deny -cp "$GUAVA_JAR:$PROTOBUF_JAR" UnsafeProbe.java
  lab "37 LocaleDrift"                           0  60 17 -        java LocaleDrift.java
  lab "37 LocaleDrift, COMPAT provider"          0  60 17 -        java -Djava.locale.providers=COMPAT LocaleDrift.java
  lab "37 DefaultCharset, no locale"             0  60 17 -        env -u LANG -u LC_ALL -u LC_CTYPE java DefaultCharset.java
  lab "37 DefaultCharset, LANG=C.UTF-8"          0  60 17 -        env -u LC_ALL -u LC_CTYPE LANG=C.UTF-8 java DefaultCharset.java
  lab "37 CanaryGate"                            0 120 21 -        java CanaryGate.java
  lab "38 WebhookFairness (self-checking)"       0 120 21 -        java WebhookFairness.java
  lab "38 WebhookSigning (self-checking)"        0  60 21 -        java WebhookSigning.java
  lab "39 VtDecision"                            0 180 21 slow     java VtDecision.java
  lab "39 CostModel"                             0  60 21 -        java CostModel.java
}

runs=0
for jdk in "$@"; do
  if [ ! -x "$jdk/bin/java" ]; then echo "not a JDK home: $jdk" >&2; exit 2; fi
  PATH="$jdk/bin:$original_path"; export PATH
  settings=$(java -XshowSettings:properties -version 2>&1)
  version=$(printf '%s\n' "$settings" | awk -F'= ' '/^ *java.version =/ {print $2; exit}')
  feature=$(printf '%s\n' "$settings" | awk -F'= ' '/^ *java.specification.version =/ {print $2; exit}')
  runs=$((runs + 1))
  out="$base/jdk-$version"; results="$base/$runs.tsv"; count=0
  mkdir -p "$out"; : > "$results"
  printf '%s\n' "JDK $version" > "$base/$runs.name"
  printf '\n=== JDK %s (%s); output in %s\n' "$version" "$jdk" "$out"
  all_labs
done
PATH="$original_path"; export PATH

if [ "$bench" -eq 1 ]; then
  jdk21=""; jdk25=""
  for jdk in "$@"; do
    f=$("$jdk/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/^ *java.specification.version =/ {print $2; exit}')
    [ "$f" = 21 ] && jdk21="$jdk"; [ "$f" = 25 ] && jdk25="$jdk"
  done
  printf '\n=== startup-bench.py (3 runs per configuration)\n'
  if [ -z "$jdk21" ] || [ -z "$jdk25" ] || [ -z "$JACKSON_CP" ]; then
    echo "  skipped: needs a JDK 21 home, a JDK 25 home and JACKSON_CP"; skipped=$((skipped + 1))
  elif JDK21="$jdk21" JDK25="$jdk25" RUNS=3 python3 startup-bench.py > "$base/startup-bench.txt" 2>&1; then
    echo "  ok"; passed=$((passed + 1))
  else
    echo "  FAILED: see $base/startup-bench.txt"; failed=$((failed + 1))
  fi
fi

printf '\n=== Matrix\n'
line=$(printf '  %-50s' "lab"); i=1
while [ "$i" -le "$runs" ]; do line="$line $(printf '%-34s' "$(cat "$base/$i.name")")"; i=$((i + 1)); done
printf '%s\n' "$line" | sed 's/ *$//'
row=1
while IFS="$(printf '\t')" read -r title _; do
  line=$(printf '  %-50s' "$title"); i=1
  while [ "$i" -le "$runs" ]; do
    line="$line $(printf '%-34s' "$(sed -n "${row}p" "$base/$i.tsv" | cut -f2)")"; i=$((i + 1))
  done
  printf '%s\n' "$line" | sed 's/ *$//'
  row=$((row + 1))
done < "$base/1.tsv"

printf '\n=== Quoted outputs\n'
if command -v python3 >/dev/null 2>&1; then
  python3 check_quotes.py "$base" > "$base/quotes.txt" 2>&1; quotes=$?
  sed 's/^/  /' "$base/quotes.txt"
  [ "$quotes" -eq 0 ] || failed=$((failed + 1))
else
  echo "  skipped (needs python3)"
fi

printf '\n=== Summary: %d ended as expected, %d failed, %d skipped. Output: %s\n' "$passed" "$failed" "$skipped" "$base"
[ "$failed" -eq 0 ]
