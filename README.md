# Job Jar

An app for sorting tasks ("jobs") by how long they take, and picking one at
random when you know how much time you have — a digital version of a jar
full of chores you draw from, instead of always working off the top of a
list.

Two native builds live in this repo: **Android** (Kotlin + Jetpack Compose +
Room, described below, in `app/`) and **iOS** (SwiftUI + SwiftData, in
`ios/` — see [`ios/README.md`](ios/README.md)). Same feature set and the
same underlying logic on both, each built the idiomatic way for its
platform rather than transliterated line-by-line.

## Core idea

1. **Add a job** with a title, optional notes, a time estimate, a category,
   and a priority. Duration is picked from quick presets (5 min to 3 hr) or
   a **"4+ hrs"** option for jobs too big to size precisely up front.
2. On the **Jar** tab, a jar-shaped glyph shows how many jobs (and
   subtasks) are actually pending right now — a plain count, not a
   completed-vs-pending ratio (that decays toward "always looks full" as
   your lifetime completions pile up, which stops meaning anything after a
   while; the Stats tab is where completion history actually belongs). Set
   how much time you have with the slider, or jump straight to a preset via
   the **Time** dropdown; optionally narrow by **Category** the same way.
   A third dropdown, **How many**, lets you draw more than one job at once
   (1/2/3/4/All) — the draw greedily fills your time budget: pick a job,
   subtract what it needs, pick another that fits what's left, and so on
   until it hits your chosen count or nothing eligible fits anymore. A
   separate **"4+ hrs"** option (inside the Time dropdown) flips the match
   from a ceiling to a floor — instead of "what fits," it explicitly draws
   from jobs needing 4+ hours, always as a single job since there's no
   remaining budget left to keep filling after an open-ended pick.

   Drawn jobs get their own subtle tonal treatment (a warm-toned taupe
   grey, the app's one reserved accent) so results are distinct from the
   neutral controls above them without introducing actual color — tap a
   card to open its detail page, tap **Mark done** to
   complete it, swipe a card left to skip just that one (it's replaced if
   anything else still fits the time it freed up), or tap **Skip all**
   below the list to redraw the whole batch fresh.
3. The **Jobs** tab is the full list — filter by category or by **Repeating**,
   toggle Active/Completed, sort by time/priority/newest/category, tap into
   a job to view or edit it, or swipe into its overflow menu to delete it.
4. The **Stats** tab shows how many jobs are active vs. completed, total
   time invested, and a breakdown by category with a relative-size bar per
   category, so you can see where your time actually goes at a glance.

### Subtasks

Any top-level job can be broken into **subtasks** — one level deep, from
the job's detail screen *or* straight from its creation/edit screen, at any
time. Each subtask has its own title, notes, category, priority, and time
estimate, and is a real, independently drawable/completable job in its own
right. The "Subtasks" list, progress summary, and add-subtask button are
one shared composable (`ui/components/SubtasksSection.kt`), used identically
everywhere subtasks show up rather than reimplemented per screen.

Adding a subtask to a job you haven't saved yet (still on the "New job"
form) quietly persists the draft first, using whatever's filled in so far,
then attaches the subtask to it — `AddEditJobViewModel.ensurePersisted()`.
The form transparently becomes an edit of the now-real job from that point
on.

The interesting part is the parent's clock: a parent job's own
`estimatedMinutes` isn't fixed at creation — it's a **floor**, not a
ceiling. Its **remaining minutes** — what actually gets matched against
your time budget when drawing — is `estimatedMinutes − Σ(completed
subtasks' minutes)`, but `estimatedMinutes` itself auto-grows to match its
subtasks' total the moment they exceed it (never auto-shrinks). That's what
makes the job form's **"4+ hrs"** duration button work as intended: tapping
it seeds a 240-minute placeholder for a job you can't size up front, and as
you break it into subtasks whose real total turns out to be, say, 6 hours,
the parent's estimate grows to match instead of quietly understating how
much is left once a couple of subtasks are done. A fresh 4-hour project
only shows up for a 4-hour draw; knock out a 90-minute subtask and that
same parent now also matches a 2.5-hour draw, alongside the parent itself
and each remaining subtask, all as separate candidates in the jar.

A parent auto-completes once every subtask is done; you can also mark it
done manually at any point (a confirmation dialog warns if subtasks are
still open, since they're left as-is, not force-completed). Deleting a
parent cascades to its subtasks.

A subtask can optionally **depend on** one sibling subtask (same parent,
set via the "Depends on" picker on its own form — never itself, and never a
subtask that would loop back to it). This is a **soft** block: a subtask
whose dependency isn't done yet is excluded from the jar's random draw
pool (`JobRepository.drawJob`), shown greyed out in the Subtasks list with
a lock icon and "Waiting on: <title>", but its checkbox is never disabled —
it can still be checked off by hand, out of order, at any time. Deleting a
subtask clears the dependency on anything that pointed at it, so nothing is
left waiting on a subtask that no longer exists.

### Repeating jobs

Any top-level job (not a subtask) can repeat, set from the same job form
via a **Repeat** switch and an interval — Daily/Weekly/Biweekly/Monthly
presets, or a custom "every N days." Completing one doesn't leave it done
for good: it silently reopens once the interval passes, so it comes back
into the jar with no reminder, notification, or confirmation needed (there's
no notification system in this app at all - "silent" is just what a repeating
job does by construction).

The schedule is **relative to when you actually complete it**, not a fixed
calendar date - completing a weekly job late just shifts the next one later
too, rather than tracking a strict "always Monday" pattern or trying to
catch you up on missed occurrences. Concretely: a repeating `Job` never
persists `isDone = true`. Completing it (`JobRepository.cycleRepeatingJob`)
instead bumps `completionCount`, stamps `completedAt`, and sets `nextDueAt`
to that moment plus the interval. `Job.isPending()` - not `isDone` - is
what decides whether a repeating job is "active" everywhere in the app: the
Jar's draw pool, and the Jobs tab's Active/Completed split. So completing a
repeating job makes it disappear from Active the same way a normal job
would - it shows in Completed instead, with a "Next: in N days" line - and
it reappears back in Active on its own the moment `nextDueAt` passes,
purely because `isPending()` is computed live off the current time, not
because anything runs in the background to move it. Tapping a resting
repeating job (in Completed, or its detail screen's "Make available now"
button) clears its schedule so it's due immediately, for whenever you want
to jump the queue.

Since a repeating job is never really "gone," just resting, the **Repeating**
filter chip is there for the case you want to see all of them regardless of
where they are in their cycle: turning it on bypasses the Active/Completed
split entirely (which is why that toggle is disabled while it's on) and
lists every repeating job together, due or resting alike.

If a repeating job has subtasks, its own cycle carries them: completing it
(directly, or automatically once every subtask is done) resets its subtasks
back to not-done for the fresh cycle too, rather than leaving them
permanently checked off. That in turn means a repeating parent's subtask
completions can't be individually tallied in Stats the way a one-off
parent's can - their `isDone` state doesn't survive the reset - so Stats
credits a repeating job's totals from `completionCount` instead, once per
cycle, whether or not it has subtasks.

## Architecture

Standard modern-Android stack, no unnecessary abstraction:

- **Kotlin + Jetpack Compose** for the entire UI (single-Activity, Material
  3). Theming is deliberately monochrome (`ui/theme/Color.kt` / `Theme.kt`)
  rather than Android 12+'s dynamic/Material You color, which pulls its
  palette from the phone's wallpaper and would silently override anything
  defined here - `dynamicColor` defaults to `false` for exactly that
  reason. Two earlier attempts at a colorful palette both read as too
  busy for what's meant to be a clean, production-feeling app, so color
  is mostly absent by design: ink (near-black/near-white, primary) for
  every main action and control, a cool-neutral slate (secondary) for
  badges/tags, and a warm-toned taupe (tertiary) reserved solely for a
  drawn job's card on the Jar tab - the one deliberate exception, a subtle
  grey-on-grey tonal shift rather than an actual color, so that one
  moment is distinct without competing for attention. Light and dark
  themes are both fully designed (not just a dark-mode auto-invert), and
  the top-right toggle on each tab (labelled with whichever mode tapping
  it would switch *to*) lets you override the system setting directly;
  that choice is persisted (`data/ThemePreferences.kt`, one SharedPreferences
  boolean) so it survives relaunching the app. Every screen pulls from
  `MaterialTheme.colorScheme` roles rather than hardcoded colors, so
  retuning the palette in one place reaches the whole app.
- **Room** for local persistence (one `jobs` table). All reads are exposed
  as `Flow`, so UI state recomposes automatically as data changes.
- **MVVM**: one `ViewModel` per screen (`DrawViewModel`, `JobListViewModel`,
  `AddEditJobViewModel`, `StatsViewModel`), each holding a `StateFlow` of UI
  state and exposing intent functions. ViewModels are built with plain
  `ViewModelProvider.Factory` implementations — no DI framework, since the
  app has a single dependency (`JobRepository`) to thread through.
- **Navigation Compose** for a bottom-nav shell (`Jar` / `Jobs` / `Stats`)
  plus pushed routes for add/edit and job detail.
- **`JobRepository`** is the single seam between UI and Room — it also
  contains the non-trivial logic: `drawJob()` loads the full job table into
  memory, computes each top-level job's remaining minutes against its
  subtasks, filters to what's eligible for the requested time/category/
  exclusion set, and picks one at random. That's a query Room's SQL can't
  express cleanly once "remaining time" depends on a live subquery over
  sibling rows, and the table size this app deals with (a personal task
  jar, not a shared backend) makes doing it in Kotlin both simpler and
  plenty fast. Completion logic (auto-completing a parent once all its
  subtasks are done, cascading deletes from parent to subtasks) lives here
  too, rather than spread across ViewModels.

```
app/src/main/kotlin/com/mattdixon/jobjar/
├── data/              Job entity, DAO, Room database, Converters, JobRepository
├── util/               Duration + recurrence formatting
└── ui/
    ├── theme/          Material 3 color/type/theme
    ├── components/     Shared badges (duration / category) and the SubtasksSection
    ├── draw/            The Jar tab: time budget → random draw → act on it
    ├── joblist/         Full job list: filter, sort, complete, delete
    ├── addedit/          Add/edit form
    ├── jobdetail/        Single job view
    └── stats/            Completion + time-invested stats
    JobJarApp.kt          Nav host + bottom navigation
```

## Data model

```kotlin
data class Job(
    val id: Long = 0,
    val title: String,
    val notes: String = "",
    val estimatedMinutes: Int,
    val category: String = "",
    val priority: Priority = Priority.NORMAL,   // LOW / NORMAL / HIGH
    val isDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val timesDrawn: Int = 0,                     // how often it's come up in the jar
    val parentId: Long? = null,                  // null = top-level job; set = subtask, one level deep
    val recurrenceDays: Int? = null,             // null = one-off; set = repeats every N days
    val nextDueAt: Long? = null,                 // repeating only: null/past = due now
    val completionCount: Int = 0,                // repeating only: how many cycles completed
    val dependsOnSubtaskId: Long? = null         // subtask only: sibling subtask that must be done first
)
```

A parent's remaining minutes is never persisted; `Job.remainingMinutes(subtasks)`
recomputes it from live subtask state every time it's needed, so there's
nothing to keep in sync when a subtask is added, completed, reopened, or
deleted. `JobRepository` enforces one write-time invariant on top of that:
a parent's `estimatedMinutes` can never be less than what its subtasks add
up to (it grows to match, never auto-shrinks) — see the Subtasks section
above for why.

## Building

Requires the Android SDK (compileSdk 34, minSdk 26) and JDK 17+. Open this
folder in Android Studio (Koala or newer), let it sync, and run the `app`
configuration — or from the command line:

```bash
./gradlew assembleDebug
```

The Gradle wrapper is checked in. This project was authored and structurally
validated (XML, version catalog, Kotlin file structure) in an environment
without network access to Google's Maven repository, so a full
`./gradlew build` has not been run here — do that as the first step after
cloning to confirm dependency resolution in your own environment.

## Possible next steps

- Daily/weekly reminder notifications ("you have 20 minutes — draw a job?")
  via WorkManager.
- A home-screen widget that surfaces one drawn job.
- Undo snackbar on delete/complete instead of an immediate action.
- Search within the job list.
- Export/import jobs (JSON) for backup or sharing a jar between devices.
