#!/usr/bin/env python3
"""Chapter 30 startup benchmark: default CDS vs AppCDS (JDK 21) vs AOT cache (JDK 25).

Lab:       Startup time and memory of StartupApp.java under six JVM configurations, from CDS off to the AOT cache
Chapters:  30 (startup and footprint) §3 and §4
Run:       JDK21=<jdk 21 home> JDK25=<jdk 25 home> JACKSON_CP=<jars> python3 startup-bench.py
Needs:     Python 3.8+, a JDK 21 and a JDK 25, Jackson 2.12+; Linux (it reads peak RSS in kilobytes, Linux's unit)
Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-18 (./verify-labs.sh --with-bench)
Sources:   JEP 341 (default CDS archives), JEP 350 (dynamic CDS), JEP 483, 514 and 515 (the AOT cache)
Output:    a table of wall-clock time, time to main and RSS (medians); timings vary by machine and load, so
           compare the configurations, not the absolute numbers. Leaves a build/ folder next to the script.

Environment variables (defaults are Ubuntu package paths):
  JDK21       home of a JDK 21   (default /usr/lib/jvm/java-21-openjdk-amd64)
  JDK25       home of a JDK 25   (default /usr/lib/jvm/java-25-openjdk-amd64)
  JACKSON_CP  colon-separated jackson-databind, jackson-core and jackson-annotations jars
  RUNS        measured runs per configuration (default 15)
"""
import os, re, statistics, subprocess, time

HERE = os.path.dirname(os.path.abspath(__file__))
JDK21 = os.environ.get('JDK21', '/usr/lib/jvm/java-21-openjdk-amd64')
JDK25 = os.environ.get('JDK25', '/usr/lib/jvm/java-25-openjdk-amd64')
JACKSON = os.environ.get('JACKSON_CP', '/usr/share/java/jackson-databind.jar:/usr/share/java/jackson-core.jar:/usr/share/java/jackson-annotations.jar')
RUNS = int(os.environ.get('RUNS', '15'))
ENV = {k: v for k, v in os.environ.items() if k != 'JAVA_TOOL_OPTIONS'}
WORK = os.path.join(HERE, 'build')
CP = os.path.join(WORK, 'app.jar') + ':' + JACKSON          # same classpath, same order, for training and every run

def run(cmd):
    t0 = time.perf_counter()
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=ENV, cwd=WORK)
    out = p.stdout.read().decode()
    _, _, usage = os.wait4(p.pid, 0)
    return (time.perf_counter() - t0) * 1000, usage.ru_maxrss / 1024, out

os.makedirs(os.path.join(WORK, 'classes'), exist_ok=True)
subprocess.run([f'{JDK21}/bin/javac', '--release', '21', '-cp', JACKSON, '-d', os.path.join(WORK, 'classes'), os.path.join(HERE, 'StartupApp.java')], check=True, env=ENV)
subprocess.run([f'{JDK21}/bin/jar', 'cf', os.path.join(WORK, 'app.jar'), '-C', os.path.join(WORK, 'classes'), '.'], check=True, env=ENV)

for f in ('app21.jsa', 'app25.aot'):
    if os.path.exists(os.path.join(WORK, f)): os.remove(os.path.join(WORK, f))
run([f'{JDK21}/bin/java', '-XX:ArchiveClassesAtExit=app21.jsa', '-cp', CP, 'StartupApp'])     # training run (JDK 21)
run([f'{JDK25}/bin/java', '-XX:AOTCacheOutput=app25.aot', '-cp', CP, 'StartupApp'])          # training run (JDK 25, JEP 514)

configs = [
    ('JDK 21', 'CDS off (-Xshare:off)', [f'{JDK21}/bin/java', '-Xshare:off']),
    ('JDK 21', 'default CDS (JDK classes only)', [f'{JDK21}/bin/java']),
    ('JDK 21', 'AppCDS archive', [f'{JDK21}/bin/java', '-XX:SharedArchiveFile=app21.jsa']),
    ('JDK 25', 'CDS off (-Xshare:off)', [f'{JDK25}/bin/java', '-Xshare:off']),
    ('JDK 25', 'default CDS (JDK classes only)', [f'{JDK25}/bin/java']),
    ('JDK 25', 'AOT cache (-XX:AOTCache)', [f'{JDK25}/bin/java', '-XX:AOTCache=app25.aot']),
]
results = {name + jdk: ([], [], []) for jdk, name, _ in configs}
for jdk, name, prefix in configs: run(prefix + ['-cp', CP, 'StartupApp'])                     # warm the OS page cache
for _ in range(RUNS):                                                                         # interleave to spread out noise
    for jdk, name, prefix in configs:
        wall, rss, out = run(prefix + ['-cp', CP, 'StartupApp'])
        if 'error' in out.lower(): print('!!', jdk, name, out.strip()[:300])
        m = re.search(r'served in (\d+) ms', out)
        w, r, rd = results[name + jdk]; w.append(wall); r.append(rss); rd.append(int(m.group(1)) if m else -1)
print(f"{'JDK':7}{'configuration':34}{'wall p50':>10}{'in main p50':>13}{'RSS p50':>9}")
for jdk, name, _ in configs:
    w, r, rd = results[name + jdk]
    print(f"{jdk:7}{name:34}{statistics.median(w):8.0f} ms{statistics.median(rd):10.0f} ms{statistics.median(r):6.0f} MB")
