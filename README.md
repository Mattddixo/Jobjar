# Job Jar

An Android app for sorting tasks ("jobs") by how long they take, and picking
one at random when you know how much time you have — a digital version of a
jar full of chores you draw from, instead of always working off the top of a
list.

## Core idea

1. **Add a job** with a title, optional notes, a time estimate, a category,
   and a priority.
2. Jobs are automatically grouped into **time buckets** (Quick ≤15 min,
   Short ≤30 min, Medium ≤1 hr, Long ≤2 hr, Extended 2 hr+) based on the
   estimate.
3. On the **Jar** tab, tell the app how much time you actually have (a
   slider or quick-pick chips) and, optionally, a category. Tap **Draw a
   job** and it randomly picks one eligible job from the jar. From there you
   can **Skip** (redraw, excluding jobs already shown this session), **Mark
   done**, or **View details**.
4. The **Jobs** tab is the full list — filter by category, toggle
   Active/Completed, sort by time/priority/newest/category, tap into a job
   to view or edit it, or swipe into its overflow menu to delete it.
5. The **Stats** tab shows how many jobs are active vs. completed, total
   time invested, and a breakdown by category.

### Subtasks

Any top-level job can be broken into **subtasks** — one level deep, from any
job's detail screen, at any time (not just at creation). Each subtask has
its own title, notes, category, priority, and time estimate, and is a real,
independently drawable/completable job in its own right.

The interesting part is the parent's clock: a parent job's own
`estimatedMinutes` never changes, but its **remaining minutes** — what
actually gets matched against your time budget when drawing — is computed
as `estimatedMinutes − Σ(completed subtasks' minutes)`. So a fresh 4-hour
project only shows up for a 4-hour draw; knock out a 90-minute subtask and
that same parent now also matches a 2.5-hour draw, alongside the parent
itself and each remaining subtask, all as separate candidates in the jar.

A parent auto-completes once every subtask is done; you can also mark it
done manually at any point (a confirmation dialog warns if subtasks are
still open, since they're left as-is, not force-completed). Deleting a
parent cascades to its subtasks — the reverse never happens, since a
subtask's own deletion or completion has no special effect beyond updating
its parent's remaining-minutes math.

## Architecture

Standard modern-Android stack, no unnecessary abstraction:

- **Kotlin + Jetpack Compose** for the entire UI (single-Activity, Material
  3, dynamic color on Android 12+).
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
├── util/               Duration formatting
└── ui/
    ├── theme/          Material 3 color/type/theme
    ├── components/     Shared badges (time bucket / category)
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
    val parentId: Long? = null                   // null = top-level job; set = subtask, one level deep
)
```

`TimeBucket` is derived from `estimatedMinutes`, not stored — it's purely a
display/filtering concept computed on the fly. Likewise, a parent's
remaining minutes is never persisted; `Job.remainingMinutes(subtasks)`
recomputes it from live subtask state every time it's needed, so there's
nothing to keep in sync when a subtask is added, completed, reopened, or
deleted.

## Building

Requires the Android SDK (compileSdk 34, minSdk 26) and JDK 17+. Open the
`android/JobJar` folder in Android Studio (Koala or newer), let it sync, and
run the `app` configuration — or from the command line:

```bash
cd android/JobJar
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
