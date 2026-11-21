[🏠 Index](README.md)

# 🃏 Flashcards

All **640 self-test questions** from the 39 chapters, ready for spaced repetition.

| File | Use it for |
|---|---|
| [`flashcards-anki.txt`](flashcards-anki.txt) | Importing into **Anki**: formatted answers, tags, and stable IDs so updates don't create duplicates |
| [`flashcards.csv`](flashcards.csv) | Spreadsheets or other flashcard apps: plain text, one row per question (`chapter, chapter_title, question_no, purpose, question, answer`) |

**Cards per purpose:** 🟢 fundamentals 33 · 🟡 practice 159 · 🔴 mechanisms & diagnosis 325 · 🟣 system decisions 84 · 🔵 organisational execution 39
**Cards per part:** I 120 · II 27 · III 30 · IV 35 · V 20 · VI 37 · VII 20 · VIII 30 · IX 205 (including the 178-question bank) · X 36 · XI 19 · XII 28 · XIII 33

## Importing into Anki

1. In Anki on a computer: **File → Import**, then choose `flashcards-anki.txt`.
2. The file's header lines configure the import for you: tab-separated, HTML formatting, the *Basic* note type, a deck called **Java Interview Mastery**, plus the ID and tags columns. Check the preview, then click **Import**.
3. To study on your phone, sync through AnkiWeb to AnkiDroid or AnkiMobile.

**Getting an updated version later:** import the new file and choose **Update** for existing notes. Cards are matched by their ID (for example `jim-ch06-q012`), so your review history is kept.

## Studying by tag

Every card is tagged with its chapter, part and purpose (the marker it carries in the chapter), and question-bank cards also carry the chapter they point to:

| Search (Browse, or a filtered deck) | Gives you |
|---|---|
| `tag:purpose::mechanisms` | How things work inside and how they fail: the follow-up questions deep-dive rounds are made of |
| `tag:purpose::decisions` or `tag:purpose::execution` | Design trade-offs, and making decisions happen across teams: the core of staff and architect loops |
| `tag:ch12` | One chapter (here, advanced concurrency) |
| `tag:part::III` | One part (here, concurrency) |
| `tag:topic::ch06` | Question-bank cards about Chapter 06 |

For a focused session: **Tools → Create Filtered Deck**, with a search such as `deck:"Java Interview Mastery" tag:purpose::decisions`.

## How to get the most out of them

- **Answer out loud before you flip.** Recognising an answer isn't the skill being tested; producing it is.
- **Grade yourself honestly.** If you'd have stumbled in an interview, press *Again*.
- **Keep it daily and small:** 20–30 new cards a day gets through the deck in three to four weeks, with reviews keeping the earlier ones fresh.
- When a card keeps failing, reread its chapter section. The card is the cue; the chapter is the understanding.

## Where the cards come from

The chapters are the single source: every card is generated from a question block in one chapter by [`export_flashcards.py`](export_flashcards.py). To fix or add a question, edit the chapter, then run `python3 export_flashcards.py`. `python3 export_flashcards.py --check` exits with status 1 when the exports no longer match the chapters, so run it before you commit. If you imported an older deck whose tags said `level::`, re-import and choose **Update**: the IDs haven't changed, so your review history is kept. If the old `level::` tags are still there afterwards, delete them from the browser's sidebar.

---

[🏠 Index](README.md)
