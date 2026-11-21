#!/usr/bin/env python3
"""Builds the flashcard deck from the chapters, which are the single source of every question.

    python3 export_flashcards.py           rewrite flashcards-anki.txt and flashcards.csv
    python3 export_flashcards.py --check   exit 1 if those files don't match the chapters (use it before committing)

Each question lives in exactly one chapter, as a <details><summary><b>Qn. <marker> question</b></summary> block.
Fix a question there, then re-run this script: never edit the exports by hand.
Needs Python 3.8+ and nothing else.
"""
import csv, html, io, pathlib, re, sys
from collections import Counter

ROOT = pathlib.Path(__file__).resolve().parent
PARTS = {**{n: 'I' for n in range(1, 9)}, 9: 'II', 10: 'II', 11: 'III', 12: 'III', 13: 'IV', 14: 'IV', 15: 'IV', 16: 'V', 17: 'V',
         18: 'VI', 19: 'VI', 20: 'VI', 21: 'VII', 22: 'VII', 23: 'VIII', 24: 'VIII', 25: 'VIII', 26: 'IX', 27: 'IX', 28: 'IX',
         29: 'X', 30: 'X', 31: 'X', 32: 'XI', 33: 'XI', 34: 'XII', 35: 'XII', 36: 'XIII', 37: 'XIII', 38: 'XIII', 39: 'XIII'}
PURPOSE = {'🟢': 'fundamentals', '🟡': 'practice', '🔴': 'mechanisms', '🟣': 'decisions', '🔵': 'execution'}
QUESTION = re.compile(r'<details>\s*<summary><b>Q(\d+)\.\s*(\S+)\s+(.*?)</b></summary>\s*(.*?)\s*</details>', re.S)


def inline_md_to_html(t):
    codes = []
    def stash(m):
        codes.append('<code>' + html.escape(m.group(1), quote=False) + '</code>')
        return f'\x00{len(codes) - 1}\x00'
    s = re.sub(r'`([^`]+)`', stash, t)                                   # protect code spans first
    s = html.escape(s, quote=False)
    s = re.sub(r'\[([^\]]+)\]\((https?://[^)\s]+)\)', r'<a href="\2">\1</a>', s)
    s = re.sub(r'\[([^\]]+)\]\([^)\s]+\)', r'\1', s)                    # relative links: keep the text
    s = re.sub(r'\*\*(.+?)\*\*', r'<b>\1</b>', s)
    s = re.sub(r'(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])', r'<i>\1</i>', s)
    return re.sub(r'\x00(\d+)\x00', lambda m: codes[int(m.group(1))], s)


def md_to_html(md):
    lines, parts, i, para = md.strip('\n').split('\n'), [], 0, []
    def flush():
        if para:
            parts.append(inline_md_to_html(' '.join(x.strip() for x in para)))
            para.clear()
    while i < len(lines):
        line = lines[i]
        if line.startswith('```'):
            flush(); code = []; i += 1
            while i < len(lines) and not lines[i].startswith('```'):
                code.append(lines[i]); i += 1
            parts.append('<pre><code>' + html.escape('\n'.join(code), quote=False).replace('\n', '<br>') + '</code></pre>')
        elif re.match(r'^\s*([-*]|\d+\.)\s+', line):
            flush(); m = re.match(r'^\s*([-*]|\d+\.)\s+(.*)$', line)
            parts.append(('•' if m.group(1) in '-*' else m.group(1)) + ' ' + inline_md_to_html(m.group(2)))
        elif not line.strip():
            flush(); parts.append('')
        else:
            para.append(line)
        i += 1
    flush()
    joined, blank = [], False
    for p in parts:
        if p == '':
            blank = True
            continue
        if joined:
            joined.append('<br><br>' if blank else '<br>')
        joined.append(p); blank = False
    return ''.join(joined)


def html_to_text(h):
    t = re.sub(r'<br\s*/?>', '\n', h)
    return html.unescape(re.sub(r'<[^>]+>', '', t)).strip()


def md_to_text(md):
    t = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', md).replace('**', '').replace('`', '')
    return re.sub(r'\n{3,}', '\n\n', t).strip()


def collect():
    rows, problems = [], []
    for f in sorted(ROOT.glob('[0-9][0-9]-*.md')):
        num, text = int(f.name[:2]), f.read_text(encoding='utf-8')
        if num not in PARTS:
            problems.append(f'{f.name}: chapter {num} has no part in PARTS')
            continue
        title = re.search(r'^# \d\d · (.+)$', text, re.M).group(1).strip()
        numbers = []
        for m in QUESTION.finditer(text):
            qn, marker, question, answer = int(m.group(1)), m.group(2), m.group(3).strip(), m.group(4).strip()
            numbers.append(qn)
            if marker not in PURPOSE:
                problems.append(f'{f.name} Q{qn}: unknown marker {marker!r}')
                continue
            topic = re.search(r'→ \[Ch (\d\d)\]', answer)
            tags = [f'ch{num:02d}', f'part::{PARTS[num]}', f'purpose::{PURPOSE[marker]}'] + ([f'topic::ch{topic.group(1)}'] if topic else [])
            rows.append(dict(num=num, title=title, qn=qn, marker=marker, purpose=PURPOSE[marker], question=question, answer=answer, tags=tags))
        if numbers and sorted(numbers) != list(range(numbers[0], numbers[0] + len(numbers))):
            problems.append(f'{f.name}: question numbers are not consecutive: {numbers}')
        if len(set(numbers)) != len(numbers):
            problems.append(f'{f.name}: duplicate question numbers')
    return rows, problems


def render(rows):
    anki = io.StringIO()
    anki.write('#separator:tab\n#html:true\n#notetype:Basic\n#deck:Java Interview Mastery\n#guid column:1\n#tags column:4\n')
    for r in rows:
        front = (f'<div style="font-size:0.8em;color:#57606a">Ch {r["num"]:02d} · {html.escape(r["title"], quote=False)} · Q{r["qn"]}</div>'
                 f'{r["marker"]} {r["question"]}')
        back = md_to_html(r['answer'])
        assert '\t' not in front + back and '\n' not in front + back
        anki.write('\t'.join([f'jim-ch{r["num"]:02d}-q{r["qn"]:03d}', front, back, ' '.join(r['tags'])]) + '\n')
    plain = io.StringIO(newline='')
    w = csv.writer(plain)
    w.writerow(['chapter', 'chapter_title', 'question_no', 'purpose', 'question', 'answer'])
    for r in rows:
        w.writerow([r['num'], r['title'], r['qn'], r['purpose'], html_to_text(r['question']), md_to_text(r['answer'])])
    return {'flashcards-anki.txt': anki.getvalue(), 'flashcards.csv': plain.getvalue()}


def main():
    rows, problems = collect()
    for p in problems:
        print('PROBLEM', p)
    outputs = render(rows)
    if '--check' in sys.argv[1:]:
        stale = [name for name, content in outputs.items()
                 if not (ROOT / name).exists() or (ROOT / name).read_bytes().decode('utf-8') != content]
        for name in stale:
            print(f'STALE {name}: run python3 export_flashcards.py')
        ok = not stale and not problems
        print(f'{len(rows)} questions; exports {"match the chapters" if not stale else "are out of date"}')
        sys.exit(0 if ok else 1)
    for name, content in outputs.items():
        with open(ROOT / name, 'w', encoding='utf-8', newline='') as fh:
            fh.write(content)
    order = ['fundamentals', 'practice', 'mechanisms', 'decisions', 'execution']
    by_purpose = Counter(r['purpose'] for r in rows)
    by_part = Counter(PARTS[r['num']] for r in rows)
    print(f'{len(rows)} cards from {len({r["num"] for r in rows})} chapters')
    print('by purpose:', ' · '.join(f'{k} {by_purpose[k]}' for k in order))
    print('by part:   ', ' · '.join(f'{k} {by_part[k]}' for k in ['I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X', 'XI', 'XII', 'XIII']))
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()
