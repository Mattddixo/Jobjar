# Job Jar

A to-do app for people who'd rather not stare at a list. You put jobs in the jar with a rough
time estimate, then when you've got a spare half hour you draw something that fits.

There's an Android app (Kotlin, Jetpack Compose, Room) in `app/` and an iOS version (SwiftUI,
SwiftData) in `ios/`. They follow the same rules and use the same backup format, but the iOS one
is behind: no in-progress jobs, calendar scheduling or multi-job draws yet. See
[ios/README.md](ios/README.md).

## What it does

**Jar tab.** Pick how much time you have (or "4+ hrs" for a big one), optionally a category
or two and how many jobs you want, then draw. You get random jobs that fit. From a drawn card
you can start the job or schedule it. Your settings are remembered between launches.

**Jobs tab.** Everything, split into To do / Done / Scheduled, with filters for repeating,
in-progress and category, plus search and sorting. Completing or deleting something gives you
a few seconds to undo.

**Subtasks.** Big jobs can be split into subtasks (one level deep). A parent's estimate grows
to cover its subtasks, and the jar treats a half-finished parent as a shorter job. A subtask can
be set to wait for a sibling, which just keeps it out of the draw until then. Ticking off the
last subtask finishes the parent; finishing the parent ticks off whatever's left, and reopening
the parent puts back only the ones it ticked off.

**Repeating jobs.** Either every N days (counted from when you actually finish it) or on set
weekdays. Finishing one sends it to Done until it's due again. Repeating jobs can't have
subtasks, because the two schedules never line up.

**Scheduling.** Any non-repeating job can be booked for a date and time. It's added to the
phone's calendar (and from there to Google Calendar if that's synced), and stays out of the
jar until you start or finish it.

**Stats.** Jobs done and time spent, overall and by category.

**Settings.** Light / dark / auto, export and import, version.

## Your data

Everything stays on the phone. There's no account and the app doesn't have internet
permission. Settings > Export writes a JSON file you can keep anywhere, and Import reads one
back (it asks before replacing anything). The same file works in the iOS app.

## Building

You need Android Studio Ladybug or newer and JDK 17.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest          # repository and recurrence rules, export format
./gradlew connectedDebugAndroidTest  # database migrations, needs a device or emulator
```

GitHub Actions runs the unit tests, lint and both builds on every push, plus the migration
tests on an emulator.

Room writes each schema version to `app/schemas/`. Commit those; the migration tests check
against them. Releasing is covered in [docs/RELEASE.md](docs/RELEASE.md) and the privacy policy
is [docs/PRIVACY.md](docs/PRIVACY.md).

## How it's put together

```
app/src/main/kotlin/com/mattdixon/jobjar/
├── data/        Job, Room DAO + migrations, JobRepository, calendar, backup format, prefs
├── util/        Duration and date formatting
└── ui/
    ├── draw/        Jar tab
    ├── joblist/     Jobs tab
    ├── jobdetail/   Job page
    ├── addedit/     Add / edit form
    ├── stats/       Stats tab
    ├── settings/    Settings
    ├── components/  Shared bits (badges, subtasks list, schedule dialog...)
    └── theme/       Colours, type, spacing
```

Plain MVVM: one ViewModel per screen, no DI framework. All the job rules live in
`JobRepository`, which only depends on interfaces for the calendar and transactions, so it's
tested on the JVM with fakes (`app/src/test`).

A few things worth knowing before changing it:

- Repeating jobs never stay done. Completing one sets `nextDueAt` and bumps
  `completionCount`; `isPending()` is what decides whether it shows as to do.
- Every repository write re-reads the rows it changes and runs as one transaction. Calendar
  calls happen after the transaction commits.
- Calendar event ids are only unique per device, so after restoring a backup on a new phone a
  stored id might point at someone else's event. Events are tagged with the app's package and
  a job URI, and nothing is edited or deleted without checking that tag.
- When a parent's completion ticks off its subtasks, they get the parent's exact `completedAt`.
  That's how reopening the parent knows which ones to put back.

### Data model

```kotlin
data class Job(
    val id: Long = 0,
    val title: String,
    val notes: String = "",
    val estimatedMinutes: Int,
    val category: String = "",
    val priority: Priority = Priority.NORMAL,
    val isDone: Boolean = false,
    val isInProgress: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val timesDrawn: Int = 0,
    val parentId: Long? = null,           // set = subtask
    val recurrenceDays: Int? = null,      // set = repeats every N days
    val repeatWeekdays: Int? = null,      // set = repeats on these weekdays instead (Mon = bit 0)
    val nextDueAt: Long? = null,          // repeating only, null = due now
    val completionCount: Int = 0,         // repeating only
    val dependsOnSubtaskId: Long? = null, // subtask only, a sibling to wait for
    val scheduledDate: Long? = null,
    val calendarEventId: Long? = null
)
```

## Ideas

- Home screen widget with a drawn job
- History for repeating jobs
- Tablet / landscape layouts
