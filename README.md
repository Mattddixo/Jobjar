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
   subtasks) are actually *available to draw* right now — a plain count,
   not a completed-vs-pending ratio (that decays toward "always looks
   full" as your lifetime completions pile up, which stops meaning
   anything after a while; the Stats tab is where completion history
   actually belongs). A job that's currently in progress has left the jar,
   so it's excluded from that count and shown as its own small "N in
   progress" line next to it instead. Set how much time you have with the
   slider, or jump straight to a preset via the **Time** dropdown;
   optionally narrow by **Category** the same checkbox multiselect as the
   Jobs list's own Category filter (tick to add, tick again to remove) -
   drawing from more than one category at once is just as reasonable a
   want here as filtering a list that way. A third dropdown, **How
   many**, lets you draw more than one job at once (1/2/3/4/All) — the
   draw greedily fills your time budget: pick a job, subtract what it
   needs, pick another that fits what's left, and so on until it hits your
   chosen count or nothing eligible fits anymore. A separate **"4+ hrs"**
   option (inside the Time dropdown) flips the match from a ceiling to a
   floor — instead of "what fits," it explicitly draws from jobs needing
   4+ hours, always as a single job since there's no remaining budget left
   to keep filling after an open-ended pick. Time budget, "4+ hrs", category,
   and how-many all persist across app restarts (`data/DrawPreferences.kt`,
   the same SharedPreferences file `ThemePreferences` already uses) so this
   panel comes back exactly as you left it instead of resetting to defaults
   on every launch - only the actual drawn batch itself doesn't carry over,
   since that's this session's output, not a setting to remember.

   Drawn jobs get their own subtle tonal treatment (a warm-toned taupe
   grey, the app's one reserved accent) so results are distinct from the
   neutral controls above them without introducing actual color — tap a
   card to open its detail page, or tap **Start** to mark it in progress.
   Drawing a job doesn't mean finishing it right away, so that's the only
   action a card offers; a started job drops out of the batch (and out of
   the jar's draw pool - see below) and is tracked from there via the Jobs
   list or its own detail page, where you actually mark it done. Below the
   list, two half-width buttons: **Redraw** for a fresh batch (excluding
   whatever's currently shown, so it can't just reshow the same jobs by
   chance) and **Close jar** to drop the current batch back to the empty
   prompt without redrawing - nothing was ever removed from the jar by
   drawing it in the first place, so there's nothing to actually "put
   back" beyond that.
3. **In progress**: any job can be started - from a drawn card, from its
   row's overflow menu on the Jobs list, or from its detail page - without
   drawing it first. An in-progress job is excluded from the jar's random
   draw (you can't pull something you're already working on) and from the
   jar's headline count, but it still shows under the Jobs list's Active
   tab (it's obviously not *done*) with its own "In progress" badge, and
   can be filtered to on its own via the **In Progress** chip. Starting a
   top-level job also excludes its own not-done subtasks from the random
   draw for as long as it stays in progress - the same "excluded from the
   draw, still fully completable by hand" treatment a dependency-blocked
   subtask already gets, and for a similar reason: once you've committed
   to the whole project, the jar shouldn't hand you a random piece of it
   as a separate draw too. That exclusion is purely computed from the
   parent's own in-progress flag (`Job.isParentAvailable`), not a
   cascading write to every subtask, so it needs no separate bookkeeping
   and reverses itself the instant the parent is moved back to the jar.
   Starting anything is reversible any time via **Move back to jar** on
   the same menu/button it started from; there's no cap on how many jobs
   can be in progress at once, matching the jar's own batch draws already
   surfacing several jobs for one sitting. It's tracked as its own flag
   (`Job.isInProgress`) rather than folded into `isDone` as a three-way
   status, specifically so it doesn't have to touch the already-correct
   `isDone` state machine (repeating-job cycling, parent auto-complete/
   reopen) - every path that marks a job done clears it, and that's the
   only invariant it has to maintain.
4. The **Jobs** tab is the full list — every job *and* every subtask, since
   a subtask is just as independently drawable/startable/completable as a
   top-level job and hiding it here (visible only by drilling into its
   parent) would make it impossible to find via a filter like **In
   Progress**. Subtasks are grouped under their own parent rather than
   scattered wherever the chosen sort would otherwise land them: the sort
   (time/priority/newest/category) orders the top-level jobs, and each
   parent's subtasks always sort immediately beneath it, in the order they
   were added, indented to mark them as nested. Each parent's subtask group
   starts **collapsed**, with a chevron to expand or collapse it on demand,
   so a long list of subtasks doesn't bury the surrounding top-level jobs;
   an active category/Repeating/In Progress filter or search term
   auto-reveals a matching parent's subtasks regardless of its collapsed
   state, so a search hit is never hidden with no way to reach it. A
   subtask whose own parent doesn't currently match the filter (e.g. only
   the subtask matches an **In Progress** filter or a search term) has no
   group to join, so it just shows on its own, same as any top-level entry
   - it still carries its "Part of:" caption either way. A subtask row also
   shows what it's still waiting on, if it's soft-blocked. Filter by category, by
   **Repeating**, or by **In Progress**, toggle Active/Completed, sort by
   time/priority/newest/category, tap into a job to view or edit it, or use
   its overflow menu to start it, move it back to the jar, or delete it.
   Category itself is free text typed while creating or editing a job, with
   no separate list to manage it from - so the Category filter's own
   dropdown doubles as that list, via a **Manage categories** entry at its
   bottom. It shows every category currently in use with a delete icon
   next to each; removing one (after a confirm step, since it touches
   every job with that category at once) clears it back to no category on
   those jobs rather than deleting them, and drops it from any active
   filter - on this tab or the Jar tab's own category picker - that had it
   selected, so a just-removed category can't stay checked somewhere with
   no way left to uncheck it.
5. The **Stats** tab shows how many jobs are active vs. completed, total
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

While a job's subtask list is still being built (on the add/edit screen,
not the detail screen), an **allocation summary** shows right next to
the duration picker itself - not above the subtask list further down,
where `SubtasksSection` shows no summary line at all on this screen, so
the two don't compete for attention (the detail screen's own copy of
`SubtasksSection` doesn't either - it already shows this same "remaining"
figure itself, right under the title, so repeating it again under
"Subtasks" would just be the same number twice on one screen).
`SubtasksSection` itself no longer has an opinion on any of this; the
summary is entirely the calling screen's job now. The allocation summary
answers a different question from that "remaining" line (which only
counts unfinished work, so it can't tell you anything useful while every
subtask you've just created is still undone): `estimatedMinutes −
Σ(every subtask's minutes, done or not)`
(`Job.kt#unallocatedMinutesOf`). "1h 30m left to allocate · 3h
total" while under, "Fully allocated · 3h total" at exactly zero, "Over
by 30m · 3h total" once subtasks add up to more than the typed total -
the cue to either trim a subtask or bump the total yourself, since this
doesn't re-read the auto-grown value back into the form. A job at or
above the 4-hour "4+ hrs" threshold skips this framing entirely (there's
no real ceiling to compare against for those) and just shows a running
sum, "Subtasks so far: 2h 15m," matching how its own total is meant to
emerge from the subtasks rather than the other way around.

The same summary, in the same spot relative to the duration picker,
shows on a subtask's own add/edit screen too - against its *parent's*
total this time, not its own. Both are live projections rather than a
plain sum of what's already saved: whichever job is currently open
contributes whatever's selected in its own duration picker right now
(not its last-saved estimate), so the number tracks the chips and
custom-minutes field as they're used, updating before anything's saved.

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

### Scheduling

Any non-repeating job — top-level or subtask — can be booked for a specific
date and time from three places: the "Schedule" button beside a drawn job's
"Start" button on the Jar tab, the overflow menu on a Jobs list row, or the
half-width "Schedule" button next to "Start" on a job's own detail page.
Repeating jobs don't get this option at all: a scheduled event describing a
one-off occurrence doesn't make sense for something that's already got its
own recurring cadence via `nextDueAt`.

Scheduling is deliberately **device Calendar Provider integration, not a
Google Calendar API/OAuth integration**. Tapping "Schedule" walks through a
date picker and a time picker (`ui/components/SchedulePickerDialog.kt`,
Material3's `DatePicker`/`TimePicker`), requesting `READ_CALENDAR`/
`WRITE_CALENDAR` runtime permission the first time it's used, then writes one
event directly via `android.provider.CalendarContract`
(`data/CalendarScheduler.kt`). From there it's the device's own account sync
adapter — not this app — that pushes the event to the user's real Google
Calendar (or whichever calendar app owns their primary account). That means
no sign-in flow, no API key, and no network call anywhere in this feature;
the tradeoff is that it's one-directional — Job Jar writes events but never
reads calendar-side edits back, so deleting or moving the event in Google
Calendar directly doesn't reflect back into the app (rescheduling from the
app afterward still works, it just falls back to inserting a fresh event
since the old one is gone).

A scheduled job is tracked with two new `Job` fields: `scheduledDate` (the
picked instant) and `calendarEventId` (the Calendar Provider row id, kept so
a reschedule updates the same event in place instead of leaving orphaned
duplicates behind). Scheduling a job immediately excludes it from the Jar's
random draw — `Job.isAvailableToDraw()` checks `scheduledDate == null`
alongside its existing pending/not-in-progress checks — the moment it's
booked, not just once the date arrives, since committing it to a day is
itself the reason to stop handing it out at random. There's no background
scheduler anywhere in this: nothing auto-transitions a scheduled job to "in
progress" when its date arrives, mirroring the same "live comparison at read
time, no `WorkManager`" philosophy already used for `nextDueAt`. Instead,
`scheduledDate` is kept deliberately separate from `isInProgress`/`isDone`,
and the two are held mutually exclusive by explicit cleanup at every
transition rather than a combined status field: starting a scheduled job
unschedules it first (`JobRepository.toggleInProgress`), and so does marking
one done (`JobRepository.toggleDone`) — a job that's actually being worked
or is already finished has nothing left to keep booked on the calendar for.
Unscheduling (from the same three entry points, or automatically via those
transitions) deletes the calendar event and clears both fields, returning
the job to the ordinary pending/draw-eligible state.

The Jobs tab surfaces this with a three-way **Active / Completed /
Scheduled** view (`JobListViewModel.JobsView`, replacing the previous
two-way Active/Completed toggle) rather than folding a "scheduled" badge
into the Active list alone — Scheduled is its own filter
(`scheduledDate != null`), independent of the pending/done split the other
two views use, so a scheduled job is easy to find as a group rather than
just visually flagged wherever it happens to sort. A scheduled job also
still shows a "Scheduled: <date>" badge inline in Active (list row, drawn
card is moot since scheduling removes it from the batch, and the detail
page), since being in the Scheduled view and being excluded from Active
are two different things — the Active view still shows it, since neither
`isInProgress` nor `isDone` changed to actually remove it from "active,"
it's just also excluded from the draw itself.

Each main tab's top bar also shows today's date in the top-left corner
(`ui/components/TodayDateButton.kt`) as a plain way *in* to the device's
Calendar app, complementing the "Schedule" buttons as the way out to it —
tapping it launches the same real Calendar app via an implicit
`ACTION_VIEW` intent on `CalendarContract.CONTENT_URI`, opened to today,
rather than this app building any calendar UI of its own.

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
  retuning the palette in one place reaches the whole app. Typography
  (`ui/theme/Type.kt`) gives every used Material3 role a deliberate value
  instead of leaning on stock defaults for whatever wasn't touched, and
  spacing/shape (`ui/theme/Spacing.kt`, `ui/theme/Shapes.kt`) are small
  scale objects every screen references instead of one-off dp literals -
  retuning the overall density or corner language means editing one file,
  not hunting through seven. All user-facing text lives in
  `res/values/strings.xml` rather than inline Kotlin string literals, the
  normal Android convention for a single source of truth and an actual
  localization path (enum-attached labels like `DrawBatchSize.label` are
  a known, deliberate exception - localizing those needs a composable
  accessor, not just moving the string, and was left out of this pass).
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
  too, rather than spread across ViewModels. It's also the only class that
  needs a platform `Context` (the Application context, passed once from
  `JobJarApplication`) — solely to own a `CalendarScheduler` internally, so
  every call site can schedule/unschedule a job through a plain
  `repository.scheduleJob(...)` call without needing its own Context-aware
  calendar plumbing (see Scheduling above).

```
app/src/main/kotlin/com/mattdixon/jobjar/
├── data/              Job entity, DAO, Room database, Converters, JobRepository, CalendarScheduler
├── util/               Duration + recurrence + scheduled-date formatting
└── ui/
    ├── theme/          Material 3 color/type/theme
    ├── components/     Shared badges (duration / category), SubtasksSection, SchedulePickerDialog
    ├── draw/            The Jar tab: time budget → random draw → act on it
    ├── joblist/         Full job list: filter, sort, complete, delete
    ├── addedit/          Add/edit form
    ├── jobdetail/        Single job view
    ├── jobpicker/        "Link to existing Job Tracker job" picker
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
    val isInProgress: Boolean = false,           // actively being worked on; excluded from the draw pool
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val timesDrawn: Int = 0,                     // how often it's come up in the jar
    val parentId: Long? = null,                  // null = top-level job; set = subtask, one level deep
    val recurrenceDays: Int? = null,             // null = one-off; set = repeats every N days
    val nextDueAt: Long? = null,                 // repeating only: null/past = due now
    val completionCount: Int = 0,                // repeating only: how many cycles completed
    val dependsOnSubtaskId: Long? = null,        // subtask only: sibling subtask that must be done first
    val scheduledDate: Long? = null,             // set = booked for this instant; excluded from the draw pool
    val calendarEventId: Long? = null,           // device Calendar Provider row id backing scheduledDate, if any
    val linkedTrackerJobId: Long? = null     // this job's Home Jobs Tracker counterpart, if linked
)
```

A parent's remaining minutes is never persisted; `Job.remainingMinutes(subtasks)`
recomputes it from live subtask state every time it's needed, so there's
nothing to keep in sync when a subtask is added, completed, reopened, or
deleted. `JobRepository` enforces one write-time invariant on top of that:
a parent's `estimatedMinutes` can never be less than what its subtasks add
up to (it grows to match, never auto-shrinks) — see the Subtasks section
above for why.

## Interop with Home Jobs Tracker

A job here can be **linked** - one-to-one, two-way - to a job in
[Home Jobs Tracker](https://github.com/Mattddixo/job-tracker) (a separate, unrelated app for
tracking a job's vendor/cost/payment details), via implicit `ACTION_VIEW` intents against a
custom URI scheme each app declares - the standard same-device mechanism for two local-only apps
(no server, no shared account) to exchange data. `DeepLink.kt` parses the incoming Uri;
`MainActivity` calls `navController.navigate(...)` explicitly for both a cold start and an
already-running instance (`onNewIntent`), rather than relying on Navigation-Compose's declarative
deep-link auto-matching.

A link is established one of two ways, both reachable from a job's detail screen:

- **Send to Job Tracker** - creates a brand-new Tracker job pre-filled from this one's title and
  category (the only two fields both apps' domain models actually share) via
  `hometracker://newjob?title=...&category=...&sourceId=<thisJobId>`. Nothing is auto-saved on
  the Tracker side - it lands on Tracker's own Add Job form, reviewed and saved like any other
  job there.
- **Link to existing Job Tracker job** - opens a picker (`hometracker://pickjob?returnJobId=<thisJobId>`)
  listing Tracker's own unlinked jobs, so this job can be tied to one that already exists instead
  of always spawning a new one.

Either path ends the same way: whichever side just created or picked the counterpart fires a
return callback - `jobjar://linked?jobId=<myId>&otherId=<theirId>` (Tracker calls back into this
scheme the same way, in reverse) - so **both** jobs end up remembering each other's id
(`Job.linkedTrackerJobId` here, `JobEntity.linkedJobJarId` on the Tracker side). From then on the
detail screen's Send/Link buttons are replaced by a single **Open in Job Tracker** button
(`hometracker://job/{id}`) - true two-way navigation, working the same regardless of which app the
link originated from.

Because the link lives on the `linkedTrackerJobId` field of the `Job` row itself, and that field
is gated purely on whether a link already exists (not on `parentId`), a **subtask** gets this same
Send/Link/Open UI on its own detail screen, independent of whatever its parent job is linked to -
useful when a subtask carries its own separate cost worth tracking on its own Tracker entry.

**Duplicate-proof by construction**: once `linkedTrackerJobId` is set, the Send/Link buttons are
gone from the UI entirely - there's no code path left that could create a second link. The picker
itself also excludes any Tracker job that's already linked to something, so a job can't be
double-linked from that side either. Any button shows a "Job Tracker isn't installed" toast
instead of crashing if the target app isn't present.

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
