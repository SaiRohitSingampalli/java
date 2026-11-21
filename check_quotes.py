#!/usr/bin/env python3
"""Checks that the lab output quoted in the chapters still matches what the labs print.

A quoted block is checked when the line just above its opening fence is a marker such as
    <!-- lab-output: LocaleDrift.java -->                  any JDK that ran it will do
    <!-- lab-output: CanaryGate.java, JDK 25 -->           only JDK 25's output counts
    <!-- lab-output: LocaleDrift.java, JDK 17, 21 and 25 -->  the block quotes all three
Only labs whose output is deterministic carry markers. Every line of a marked block must appear in that lab's
output, with these allowances: blank lines, `$ ` command lines (and their `\\` continuations) and `…` elisions
are skipped; a line may carry an annotation after two or more spaces (`←`, `<-`, `//` or `#`); clock times
in log lines are masked on both sides; and non-ASCII characters may come out as `?` when the labs ran without
a UTF-8 locale.

Usage: python3 check_quotes.py VERIFY_OUT     (the folder that ./verify-labs.sh printed at the end)
Exit status: 0 if no marked block has drifted, 1 if one has, 2 on bad usage."""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent
MARKER = re.compile(r'^\s*<!--\s*lab-output:\s*([A-Za-z0-9_.-]+)\s*(?:,\s*JDK\s+([0-9 ,and]+?))?\s*-->\s*$')
FENCE = re.compile(r'^\s*```')
ANNOTATION = re.compile(r'\s{2,}(?:←|<-|//|#)')


def normalise(line):
    line = line.rstrip()
    line = re.sub(r'^[A-Z][a-z]{2} \d{1,2}, \d{4} \d{1,2}:\d{2}:\d{2} [AP]M ', '<time> ', line)  # java.util.logging
    line = re.sub(r'\[\d+\.\d+s\]', '[…]', line)                                                  # -Xlog uptime
    line = re.sub(r'\(file:[^)]*\)', '(file:…)', line)                                            # JAR locations
    line = re.sub(r'^(JDK \d+)\.[0-9.]+$', r'\1', line)                                           # JDK build lines
    return line


def lab_sources():
    """Maps each lab's position in verify-labs.sh to the source file it runs."""
    sources, n = {}, 0
    for line in (REPO / "verify-labs.sh").read_text().splitlines():
        m = re.match(r'\s*lab "[^"]+"\s+\d+\s+\d+\s+\d+\s+\S+\s+(.*)$', line)
        if m:
            n += 1
            src = re.search(r'([A-Za-z0-9_]+\.java)', m.group(1)) or re.search(r'\./([A-Za-z0-9_-]+\.sh)', m.group(1))
            sources[n] = src.group(1) if src else None
    return sources


def load_outputs(verify_out):
    """{source: {jdk feature: set of normalised lines}} from every JDK run in verify_out."""
    sources, outputs = lab_sources(), {}
    for name_file in sorted(verify_out.glob("*.name")):
        version = name_file.read_text().split()[-1]
        feature = version.split('.')[0]
        run_dir = verify_out / f"jdk-{version}"
        for f in run_dir.glob("[0-9][0-9]-*.txt"):
            src = sources.get(int(f.name[:2]))
            if src:
                lines = outputs.setdefault(src, {}).setdefault(feature, set())
                lines.update(normalise(l) for l in f.read_text(errors="replace").splitlines())
    return outputs


def found(line, candidates):
    if line in candidates:
        return True
    for m in ANNOTATION.finditer(line):            # "output line   ← a note the chapter added"
        if line[:m.start()].rstrip() in candidates:
            return True
    ascii_only = re.sub(r'[^\x00-\x7f]', '?', line)  # the lab ran with a non-UTF-8 locale, so Java printed ?
    return ascii_only != line and ascii_only in candidates


def marked_blocks():
    for md in sorted(REPO.glob("*.md")):
        lines = md.read_text().splitlines()
        for i, line in enumerate(lines):
            m = MARKER.match(line)
            if not m:
                continue
            if i + 1 >= len(lines) or not FENCE.match(lines[i + 1]):
                yield md.name, i + 1, m.group(1), [], None, "a marker must sit on the line just above a code block"
                continue
            jdks = re.findall(r'\d+', m.group(2) or "")
            body, j = [], i + 2
            while j < len(lines) and not FENCE.match(lines[j]):
                body.append((j + 1, lines[j]))
                j += 1
            yield md.name, i + 1, m.group(1), jdks, body, None


def check(body, candidates):
    missing, continuation = [], False
    for number, raw in body:
        text = raw.strip()
        if continuation:
            continuation = text.endswith("\\")
            continue
        if text.startswith("$ "):
            continuation = text.endswith("\\")
            continue
        if not text or text in ("…", "...", "[…]"):
            continue
        line = normalise(raw)
        if not found(line, candidates):
            missing.append((number, raw.rstrip()))
    return missing


def main():
    if len(sys.argv) != 2 or not pathlib.Path(sys.argv[1]).is_dir():
        print(__doc__.split("\n\n")[-1])
        return 2
    outputs = load_outputs(pathlib.Path(sys.argv[1]))
    runs = sorted({jdk for per in outputs.values() for jdk in per}, key=int)
    checked = drifted = skipped = 0
    for md, number, source, jdks, body, problem in marked_blocks():
        where = f"{md}:{number}"
        if problem:
            print(f"  BAD MARKER  {where}: {problem}")
            drifted += 1
            continue
        per_jdk = outputs.get(source, {})
        needed = jdks or sorted(per_jdk, key=int)
        absent = [j for j in needed if j not in per_jdk]
        if not per_jdk or absent:
            reason = f"no JDK {', '.join(absent)} output for {source}" if per_jdk else f"{source} didn't run"
            print(f"  not checked {where}: {reason}")
            skipped += 1
            continue
        candidates = set().union(*(per_jdk[j] for j in needed))
        missing = check(body, candidates)
        checked += 1
        if missing:
            drifted += 1
            print(f"  DRIFT       {where}: {len(missing)} quoted line(s) no longer printed by {source}")
            for line_number, text in missing[:5]:
                print(f"                line {line_number}: {text[:140]}")
    print(f"Quoted outputs: {checked} checked against JDK {', '.join(runs) or '-'}, {drifted} drifted, "
          f"{skipped} not checked (lab or JDK not run)")
    return 1 if drifted else 0


if __name__ == "__main__":
    sys.exit(main())
