[🏠 Index](README.md)

# 🃏 Flashcards

All **588 self-test questions** from the 35 chapters, ready for spaced repetition.

| File | Use it for |
|---|---|
| [`flashcards-anki.txt`](flashcards-anki.txt) | Importing into **Anki**: formatted answers, tags, and stable IDs so updates don't create duplicates |
| [`flashcards.csv`](flashcards.csv) | Spreadsheets or other flashcard apps: plain text, one row per question (`chapter, chapter_title, question_no, level, question, answer`) |

**Cards per level:** 🟢 foundation 33 · 🟡 senior 159 · 🔴 staff 308 · 🟣 architect 88
**Cards per part:** I 120 · II 27 · III 30 · IV 35 · V 20 · VI 37 · VII 20 · VIII 30 · IX 186 (including the 159-question bank) · X 36 · XI 19 · XII 28

## Importing into Anki

1. In Anki on a computer: **File → Import**, then choose `flashcards-anki.txt`.
2. The file's header lines configure the import for you: tab-separated, HTML formatting, the *Basic* note type, a deck called **Java Interview Mastery**, plus the ID and tags columns. Check the preview, then click **Import**.
3. To study on your phone, sync through AnkiWeb to AnkiDroid or AnkiMobile.

**Getting an updated version later:** import the new file and choose **Update** for existing notes. Cards are matched by their ID (for example `jim-ch06-q012`), so your review history is kept.

## Studying by tag

Every card is tagged with its chapter, part and level, and question-bank cards also carry the chapter they point to:

| Search (Browse, or a filtered deck) | Gives you |
|---|---|
| `tag:level::staff` or `tag:level::architect` | The questions that separate senior from staff answers |
| `tag:ch12` | One chapter (here, advanced concurrency) |
| `tag:part::III` | One part (here, concurrency) |
| `tag:topic::ch06` | Question-bank cards about Chapter 06 |

For a focused session: **Tools → Create Filtered Deck**, with a search such as `deck:"Java Interview Mastery" tag:level::staff`.

## How to get the most out of them

- **Answer out loud before you flip.** Recognising an answer isn't the skill being tested; producing it is.
- **Grade yourself honestly.** If you'd have stumbled in an interview, press *Again*.
- **Keep it daily and small:** 20–30 new cards a day gets through the deck in three to four weeks, with reviews keeping the earlier ones fresh.
- When a card keeps failing, reread its chapter section. The card is the cue; the chapter is the understanding.

---

[🏠 Index](README.md)
