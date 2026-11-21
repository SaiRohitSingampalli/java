#!/usr/bin/env bash
# Runs the fast, self-contained labs one after another, prints their output, and checks each exit status.
# Needs `java` (JDK 21 or newer) on the PATH. Takes about a minute and a half.
# Long-running, dependency-based and JDK-comparison labs are listed in labs.md; verify-labs.sh runs those too.
#
# Exit status: 0 if every lab ended as expected, 1 if any didn't. Each lab's expected exit status is
# explicit: `run` expects 0, and `run_expecting <status>` marks a lab that is meant to fail
# (none of the quick labs is, today). Labs with self-checks exit 1 when a check fails.
set -u
cd "$(dirname "$0")"

timeout_cmd=""
command -v timeout >/dev/null 2>&1 && timeout_cmd="timeout 300"   # a hung lab counts as a failure (status 124)

passed=0
failed=0
report=""

run_expecting() {
  local expected="$1" title="$2"; shift 2
  printf '\n=== %s\n$ %s\n' "$title" "$*"
  $timeout_cmd "$@" 2>&1          # unquoted on purpose: it expands to nothing when `timeout` isn't installed
  local status=$?
  if [ "$status" -eq "$expected" ]; then
    passed=$((passed + 1))
  else
    failed=$((failed + 1))
    report="${report}!!! ${title}: exit status ${status}, expected ${expected}"$'\n'
    printf '!!! %s: exit status %d, expected %d\n' "$title" "$status" "$expected"
  fi
}
run() { run_expecting 0 "$@"; }

run "Ch01 · class initialisation order"          java InitOrder.java
run "Ch01 · numeric conversions and overflow"     java Numbers.java
run "Ch02 · dispatch, hiding and overloading"     java Dispatch.java
run "Ch03 · string identity puzzles"              java StringEquality.java
run "Ch04 · try-with-resources close order"       java TryWithResources.java
run "Ch06 · HashMap buckets"                      java HashBuckets.java
run "Ch06 · the second-to-last-element quirk"     java IteratorQuirk.java
run "Ch07 · streams are vertical and lazy"        java VerticalStream.java
run "Ch08 · sealed types + pattern matching"      java ExprDemo.java
run "Ch11 · lost updates"                         java LostUpdate.java
run "Ch11 · the visibility bug"                   java StopFlag.java
run "Ch12 · AQS latch + fork/join"                java OneShotLatchAndForkJoin.java
run "Ch17 · enum state machine"                   java OrderStateDemo.java
run "Ch23 · payment idempotency (self-checking)"  java PaymentIdempotency.java
run "Ch24 · consistent hashing vs modulo"         java Ring.java
run "Ch24 · short-link codes (self-checking)"     java ShortCodes.java
run "Ch26 · 15 DSA pattern templates (tests)"     java Patterns.java
run "Ch26 · Java traps"                           java Traps.java
run "Ch26 · concurrency problems"                 java ConcurrencyProblems.java
run "Ch26 · LLD solutions"                        java LowLevelDesigns.java
run "Ch29 · Reactive Streams with java.util.concurrent.Flow" java FlowDemos.java
run "Ch32 · direct-memory leak"                   sh -c "javac -d \"${TMPDIR:-/tmp}/quick-ch32\" DirectLeak.java && java -Xmx256m -XX:MaxDirectMemorySize=64m -cp \"${TMPDIR:-/tmp}/quick-ch32\" DirectLeak"
run "Ch32 · connection-pool exhaustion"           java PoolExhaustion.java
run "Ch33 · per-key rate limiter"                 java KeyedRateLimiter.java
run "Ch34 · Redis Cluster hash slots"             java HashSlots.java
run "Ch35 · the tool-calling loop"                java ToolLoop.java
run "Ch37 · JVM flags across JDKs"                ./jvm-flags-check.sh
run "Ch37 · locale data drift"                    java LocaleDrift.java
run "Ch37 · default charset with no locale"      env -u LANG -u LC_ALL -u LC_CTYPE java DefaultCharset.java
run "Ch37 · canary gate statistics"               java CanaryGate.java
run "Ch38 · webhook fairness (self-checking)"     java WebhookFairness.java
run "Ch38 · webhook signatures and SSRF checks (self-checking)" java WebhookSigning.java
run "Ch39 · build vs buy cost model"              java CostModel.java

printf '\n=== Summary: %d of %d labs ended as expected\n' "$passed" "$((passed + failed))"
if [ "$failed" -gt 0 ]; then
  printf '%s' "$report"
  exit 1
fi
