# TOMS v2 and the research questions

What the build can support in the paper, per research question. Written against the code as of branch `v2/android-phase1`. "Built" means it exists and is covered by a passing test or was seen on the phone, as stated in `v2_progress.md`. Nothing here is a result: no field trial has been run.

## Two sets of questions in the paper

The paper words the questions in two places, and they do not match. This table follows the four in the systematic literature review (section 2.2.2). Section 1.3.1 lists three different ones:

| Section 1.3.1 | SLR section 2.2.2 |
| --- | --- |
| 1. Operational challenges of manual fare collection and counting | RQ1. Challenges and limitations of manual counting and fare monitoring |
| 2. How to design an RFID POS that automates fare calculation and stores data in dead zones | RQ2. How to design an RFID POS with cards and handhelds to record destinations and fares |
| 3. How effective and accurate the prototype is against manual methods | RQ3. Mechanisms by which RFID scanning and seat pagers automate counting and fare recording; RQ4. How effective the system is against manual methods |

The mechanism question (RQ3) has no counterpart in section 1.3.1. Decide whether the paper keeps three questions or four, and use the same wording everywhere.

## Summary

| RQ | Answered by | State |
| --- | --- | --- |
| RQ1 | Literature review and conductor survey | Evidence exists. Not the app's job. |
| RQ2 | The design and the build | Mostly answered. The Return Terminal is not built. |
| RQ3 | The build, in part | Partly answered. Counting is manual in this build. |
| RQ4 | A field trial | Not answered. None of what it needs exists yet. |

## RQ1. Challenges of manual counting and fare monitoring

**Answered by:** the literature review and the conductor survey (`Survey_Synthesis_Report(1).md`, 20 conductors on two routes). The app does not answer it.

**What the survey gives the paper:**
- 16 of 20 conductors (80%) count passengers by memory or by eye.
- 18 of 20 (90%) agree it is hard to track exact fare payment in a crowded bus; 17 of 20 (85%) struggle to find exact change.
- 16 of 20 (80%) report that cash collected does not match the passenger count at the end of the day.
- 16 of 20 (80%) report areas with no or slow internet (Tag-ibo, Sta. Fe, Barinaut and Timoga are named).
- All 20 would use a handheld that works out the fare; 18 of 20 (90%) would use reusable cards.

**Limits to state:** 20 respondents on two routes is a small sample, and these are the conductors' own reports of their problems, not measurements. The plan also says the manual baseline for RQ4 must not be taken from this survey.

**Not checked:** I have not read the literature review's citations, so I cannot say the design documents cite them correctly.

## RQ2. Designing an RFID point-of-sale to record destinations and fares

**Built and tested:**
- Card reading on the phone's own NFC (four different cards read on the Tecno Pova 4).
- Destination capture: the conductor picks the destination, and the boarding stop can come from the GPS nearest stop.
- Fare computation: distance-based, per person, rounded per person and summed for a group, with discounts. Money is whole centavos. The fare version is stored on each trip.
- Records: every trip is saved locally first, with an outbox, safe retries and no duplicates on the server.
- Offline operation: tested with the network cut, and the events upload later.
- Card registry with Web NFC, static QR receipts and a verified fare document on the receipt.
- A shift summary that closes the day's figures.

**Not built:**
- The Return Terminal. The app's tap-again and manual return stand in for it.
- A seat card or seat pager. The paper's wording is "RFID seat pagers" and "seat pager"; the build has trip cards only.

**What the paper may say:** the prototype records destination and computes fare automatically from the card tap, and works offline.

**What it must not say yet:**
- That fares and stops are correct. They are placeholders in `TestConfig.kt` until verified fares and real stops are loaded. The 15.00 base, 4 km and 2.50 per km come from the old app and are not asserted to be right.
- That the terminal is part of the prototype, until it exists. Chapter IV still contains `[TODO]` markers for hardware measurements.

## RQ3. Mechanisms that automate counting and fare recording

**What the build shows:**
- Fare recording is automated: a card tap produces a complete record with no typing of the fare.
- A card moves through a state machine (available, assigned unpaid, assigned paid, returned, returned unpaid, lost). A card that comes back unpaid raises an alarm state. Shift end can write off unpaid fares without recording a terminal return that did not happen.

**What the build does not do:** automate the passenger count. The conductor enters the group by pressing passenger-type buttons (Regular, Student, PWD, Senior), then taps one card for the whole group. The count is as accurate as those presses.

I searched the app for seat cards, seat numbers and occupancy and found none.

**What the paper may say:** RFID tapping automates fare calculation and record creation, and the card state machine tracks whether each trip has been paid and returned.

**What it must not say:**
- That passenger counting is automated. The plan's feature table lists "automated passenger count and active passengers list" as in the MVP, and the app does not do that. Either build it, with seat cards or a different count method, or reword the scope, the hypothesis and the framework (which mentions "automated passenger counting").
- That the shift summary is an independent count. Its passenger figure is the sum of what the conductor entered.

**Mechanism part still missing:** the unpaid-return alarm exists only as a card state. The state machine can reach it (`RETURNED_UNPAID`), and the Collect list shows such cards first, but today only the conductor's manual Return button can cause it. Nothing from a real terminal does, since there is no Bluetooth code in the app. So the "terminal alarm" in the design is simulated by a button and has not been shown with hardware.

## RQ4. Whether the system beats manual methods on accuracy

**State: not answered. None of what it needs exists.**

| Needed | State |
| --- | --- |
| A field trial on real or simulated trips | Not run. |
| A manual baseline measured on the same trips | Not built. The plan says the manual side needs an observer sheet, not the survey. |
| Ground truth: who observes and how a correct fare and a correct count are decided | Not defined. The code has no expected-fare or observer field. |
| The four criteria fixed (passenger identification, fare computation, record generation, transaction recording) | Named in the plan, with no measurement definition. |
| Six metrics against four criteria | Chapter IV lists six metrics (identification, fare, record completeness, recording effectiveness, availability, sync reliability) while the hypotheses name four criteria. They must be mapped, or the hypotheses revised. |
| A corrected sync reliability formula | The plan says it should be records synced divided by records created offline, split by channel. I did not find it in the app or the backend. |
| A stated test for H0 | None. The plan suggests McNemar's test for paired correct and incorrect results, or a paired t-test or Wilcoxon signed-rank for per-trip percentages, with ground truth and sample size fixed before the trial. |

**The hypothesis wording.** Section 1.3.2 says the system will improve "the accuracy of passenger counting and revenue recording ... as evidenced by reduced discrepancies between physical passenger headcounts and digital transaction records." That ties the result to passenger counting, which this build does not automate. Revenue recording can be tested with this build. Counting cannot, unless the count becomes automatic or the hypothesis is narrowed.

**What the build can contribute to a trial, once it exists:**
- The event log, with times and fare versions, and the CSV export on the dashboard.
- Fares charged against fares collected, per shift (the shift summary and the written-off figure).

**What it cannot contribute:** the manual side of any comparison.

## What to decide next

1. **Counting.** Build an automatic count (seat cards or another method), or narrow the scope, framework and hypothesis to fare and revenue recording.
2. **Questions.** Three or four, and one wording.
3. **Trial design.** Observer sheet, ground-truth rule, sample size, the four criteria with definitions, the mapping of the six metrics, and the H0 test, written down before any trial.
4. **Sync metric.** Implement the corrected formula so the trial can report it.
5. **Placeholders.** Replace fares and stops with verified ones before any trial run.
