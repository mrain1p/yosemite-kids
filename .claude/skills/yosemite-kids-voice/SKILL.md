---
name: yosemite-kids-voice
description: The product's voice and its rules of drawing - what a child reads, what a parent reads, when a colour is allowed, what an empty state must do, and the one-sided-discipline check to run before adding anything to one face. Use when writing or changing any words a child or a parent sees, any empty or error state, or anything drawn on one face that the other face also draws.
---

# The house style

`CLAUDE.md` says how to work. `DesignTokens` holds the colours and the numbers.
`KidSurface` and `SettingsSurface` hold what exists. None of them says what the
product *sounds* like, and that is the thing that has lived only in whoever
last touched a composable. This is it.

Everything here is a judgement, which is why it is a skill and not a guard.
Where a guard can hold a line, it already does, and the guard number is named.

## Two readers, never one

Every failure has two audiences and they need different sentences.

**A child** gets: what happened, in words they read at five, and what to do if
there is anything. Never a code, never a number, never a device name, never the
word "error", never a reason that blames them. `KidWords` in `:core` is where
these live, so the television, the phone and the tablet refuse in the same
words — a child who moves between them in an afternoon must not meet three
vocabularies for one rule.

**A parent** gets: what happened, which child, which thing, and what they can
do. Reasons may be specific and may name a mechanism. `HubPolicy.Decision`
carries both halves on purpose: `reason` is the stable code, `detail` is the
parent's sentence, and the child's is looked up from the code.

The failure to avoid is the one this product has made twice: showing one
audience the other's sentence. A raw extractor message on a child's home
screen, and a parent's log line put in front of a five-year-old, are the same
mistake in opposite directions.

> A child: "That will not play right now."
> A parent: "Leo has a bedtime and a daily budget, and nobody has set the
> family's home timezone, so this hub cannot tell what day it is here."

## Sentences a child reads

- One clause. If it needs a comma, it needs to be two sentences or fewer words.
- Present tense, active, about the thing — not about the app. "Nothing here
  yet" beats "This list is currently empty."
- Never an instruction a child cannot carry out alone. "Ask a grown-up" is the
  honest ending when the fix is a parent's; anything else is a dead end.
- No apology, no exclamation beyond one, and never two emoji in a row.
- Say the gesture, not the noun: "Hold a video and pick Add to Up next" beats
  "No queued items".

## Empty states

**An empty state must say the gesture that fills it.** That is the rule, and it
is the one most often missed, because an empty shelf is written while the
author is looking at a full one.

- A shelf with nothing in it says what would put something there, in the words
  of the gesture that does it.
- A shelf that is *loading* is not empty: draw the slot and its keys, never
  nothing (guard 71 holds this for the channel rails, and the reason is that a
  rail that is sometimes there reads as breakage rather than as waiting).
- A shelf that is empty *because of a rule* says so in the child's words —
  "Nothing new since you last looked" is different from "Nothing here yet", and
  a child can tell.

## Colour

Every hue is a token in `DesignTokens`; guard 48 fails the build if `:app`
states a value of its own and guard 61 if the kid page does. What the tokens
cannot say is when a colour is *allowed*:

- **Amber** means a limit the child is approaching and can still act inside —
  the last five minutes of a session, a budget nearly spent. Never a refusal;
  a refusal is over and amber suggests it is not.
- **Red** is watched progress and nothing else (`WatchedProgressRed`, the
  YouTube convention). It is deliberately not the brand teal, and it is never
  a failure state on a kid's face: a child who sees red on their own screen
  reads it as having done something wrong.
- **The brand teal** is the product's own chrome. It never carries meaning.
- A refusal on a child's screen is drawn in the ordinary text colour, with the
  card doing the work. The words are the signal.

## The one-sided-discipline check

Run this before adding anything to any face. It is the check the 2026-09-17
review wished had existed: nearly every finding was a discipline applied on one
side of a pair and not the other.

Ask, in order:

1. **Which faces draw this?** Phone, television, browser. If more than one,
   what will they read it from? A rule two faces decide is a rule with two
   implementations, and the one in a child's browser is the one nobody can
   inspect: it does not throw, it does not log, and the symptom is a tablet
   showing something the television does not.
2. **Does the other face already do this, and how?** If the hub reads a
   manifest for it, the phone must too. If eleven stores write a file one way,
   the twelfth does not invent a way. If one page reports its own errors, so
   does the other.
3. **What did the last guard of this kind learn?** When a check is widened
   because it went blind, every check of the same shape is widened with it.
   Four route guards stayed blind to a digit for a year because the lesson
   reached one of them.
4. **If this difference is deliberate, where is the reason written?**
   `KidSurface.why` / `tvWhy`, `SettingsSurface.honourWhy`, or a KDoc. A
   difference with no reason recorded is indistinguishable from an omission,
   and the next person will "fix" it or copy it.

## A control says what it does, and undoes it

A control that draws a state can change that state back. This is about
honesty rather than convenience: a chip with `aria-pressed`, a switch, a
filled heart, a highlighted chip in a row — each of them is a sentence about
what is true, and a reader who can see a state and cannot change it has been
shown a lie about who is in charge.

Two have shipped in this product, both for months, both invisible in review
because the code reads perfectly:

- The console's per-kid rulings were drawn with `aria-pressed` and only ever
  **added** the kid to the list. Pressing a pressed chip removed them and put
  them straight back, so a parent who blocked the wrong child could not take
  it back from that page at all — they had to edit the document.
- The console's number fields carried the manifest's `min` and `max` as HTML
  attributes, which stop a spinner and nothing else. The range was drawn and
  not enforced, so the page described a rule the box did not have.

So, before shipping a control:

1. **Can the reader get back to where they were?** Press it twice. If the
   second press is a no-op, either it is not a toggle and must not be drawn
   as one, or it is a toggle and is missing half its code.
2. **Is the state it draws the state that is in force?** Two lists that
   overlap (`allowedFor` and `blockedFor`) can both say yes while the rule
   that wins says no. Turning one on clears the other.
3. **Is the limit it draws enforced where the value lands?** A range in the
   markup is a hint to one browser. The hub takes patches from older builds,
   other tabs and anything that is not this page; if the range matters, it
   is enforced on the box, from the same manifest the page drew it from.

## Words this product uses

| Say | Not |
| --- | --- |
| a grown-up | a parent, an adult (to a child) |
| Up next | queue, playlist (for the lineup) |
| Watch later | save for later, bookmarks |
| Hold | long-press (to a child) |
| put away / hidden | blocked (to a child; "blocked" is the parent's word) |
| The hub | the server, the container (to a parent) |
| did not answer | timed out, unreachable (to a child) |

## Where the words live

- A child's: `KidWords` in `:core`. If a face builds a child-facing sentence
  inline, that is the bug — it will drift from the other faces by the next
  release.
- A shelf's title, its icon and its empty line: `KidSurface`.
- A parent's settings words: `SettingsSurface`.
- A refusal's two halves: `HubPolicy.Decision` (`reason`, `detail`) plus
  `KidWords.refusal(reason)`.
