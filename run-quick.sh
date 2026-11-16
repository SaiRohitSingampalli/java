#!/usr/bin/env bash
# Runs the fast, self-contained labs one after another and prints their output.
# Needs `java` (JDK 21 or newer) on the PATH. Takes about a minute.
# Long-running, dependency-based and JDK-comparison labs are listed in labs.md instead.
set -u
cd "$(dirname "$0")"
run() { local title="$1"; shift; printf '\n=== %s\n$ %s\n' "$title" "$*"; "$@" 2>&1; }

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
run "Ch24 · consistent hashing vs modulo"         java Ring.java
run "Ch26 · 15 DSA pattern templates (tests)"     java Patterns.java
run "Ch26 · Java traps"                           java Traps.java
run "Ch26 · concurrency problems"                 java ConcurrencyProblems.java
run "Ch26 · LLD solutions"                        java LowLevelDesigns.java
run "Ch29 · Reactive Streams with java.util.concurrent.Flow" java FlowDemos.java
run "Ch32 · direct-memory leak"                   java -Xmx256m -XX:MaxDirectMemorySize=64m DirectLeak.java
run "Ch32 · connection-pool exhaustion"           java PoolExhaustion.java
run "Ch33 · per-key rate limiter"                 java KeyedRateLimiter.java
run "Ch34 · Redis Cluster hash slots"             java HashSlots.java
run "Ch35 · the tool-calling loop"                java ToolLoop.java
