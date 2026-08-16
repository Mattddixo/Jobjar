# Job Jar (iOS)

A native SwiftUI port of the Android app in `../app` - same idea, same feature set, same
underlying logic, rebuilt on Apple's stack instead of transliterated line-by-line. Where a
platform idiom differs (SwiftData's live-object persistence vs. Room's immutable-row-plus-Flow
model, `@Query` vs. DAO Flows, `NavigationStack` vs. Navigation Compose), this follows the
idiom rather than fighting it - but every rule (recurrence cycling, subtask dependencies, the
parent auto-grow invariant, draw eligibility, stats crediting) is the same rule, ported
deliberately rather than approximated.

## Stack

- **SwiftUI** for the entire UI (iOS 17+, so `NavigationStack`/`navigationDestination` and the
  `@Observable` macro are both available without any back-compat shims).
- **SwiftData** for persistence (one `Job` model, no separate DTO layer - the Room-workalike
  here is `JobRepository`, which owns mutation logic while `@Query` in each view supplies live,
  reactive reads, mirroring Room's Flow-returning DAO methods at the view layer instead of a
  shared ViewModel layer).
- No third-party dependencies.

## Project structure

```
ios/JobJar/
├── project.yml              XcodeGen project definition (see "Building" below)
└── JobJar/
    ├── JobJarApp.swift       @main entry point, SwiftData container
    ├── ContentView.swift     TabView root + per-tab NavigationStack + route wiring
    ├── Models/
    │   └── Job.swift          @Model entity + isPending/isUnblocked/dependency-cycle logic
    ├── Data/
    │   └── JobRepository.swift   Mutation logic: add/update/delete, toggleDone, drawJob
    ├── Util/
    │   ├── DurationFormat.swift
    │   └── RecurrenceFormat.swift
    ├── Views/
    │   ├── Components/       Badges, FilterChip, SubtasksSection (shared everywhere)
    │   ├── Draw/              The Jar tab: jar meter, time/category picker, draw, drawn card
    │   ├── JobList/           Full job list: filter, sort, complete, delete
    │   ├── AddEdit/            Add/edit form (duration, category, priority, repeat, depends-on, subtasks)
    │   ├── JobDetail/          Single job view
    │   └── Stats/              Completion + time-invested stats
    └── Assets.xcassets/       AppIcon + AccentColor placeholders (no artwork supplied)
```

## Feature parity with the Android app

- **Jar / draw**: time slider + presets, "4+ hrs" floor mode, category filter, jar-shaped fill
  meter (drawn with SwiftUI's `Canvas`, same `available/(available+completed)` proportional
  fill as the Android version), drawn-job card with Skip/Mark done/View details, force-complete
  confirmation when subtasks are still open.
- **Jobs list**: Active/Completed split (disabled while the Repeating filter bypasses it, same
  as Android), category filter, sort menu, swipe-to-delete, per-row checkbox that reflects
  `isPending()` (so a resting repeating job shows checked, same as Android).
- **Subtasks**: one level deep, parent auto-grow-to-fit invariant, auto-complete-parent on last
  subtask done (and the fix from the Android session: reopening a subtask reopens its
  non-repeating parent too), optional single sibling dependency with a soft block (lock icon +
  "Waiting on:", draw-pool-only, checkbox always live).
- **Repeating jobs**: `nextDueAt`/`completionCount` cycling identical to Android -
  `isPending()` drives Active/Completed everywhere, a completed repeating job disappears into
  Completed and reappears on its own once due (no background scheduling - just a live date
  comparison, same as Android), "Make available now" wakes a resting one early.
- **Stats**: active/completed/time-invested cards, per-category breakdown with a relative bar,
  same crediting rules (one-off jobs and non-repeating-parent subtasks counted individually,
  repeating jobs credited via `completionCount` once per cycle) including the active-count
  fix from the same Android session (counts every pending job and subtask, not just top-level).

## Building

This project was authored without access to Xcode or a macOS toolchain, so - like the Android
app's own README disclaimer about an unverified first Gradle build - **it has not been
compiled here**. Do that as the first step after cloning.

The project file itself is generated rather than hand-typed (hand-authoring a `.pbxproj`
without Xcode to validate it is a bad way to lose an afternoon), via
[XcodeGen](https://github.com/yonaskolb/XcodeGen):

```bash
brew install xcodegen
cd ios/JobJar
xcodegen generate
open JobJar.xcodeproj
```

Then build/run the `JobJar` scheme on an iOS 17+ simulator or device. `JobJar.xcodeproj` is
generated output (from `project.yml`) and isn't checked in - regenerate it any time
`project.yml` or the file layout under `JobJar/` changes.

If you'd rather not install XcodeGen: create a new Xcode "App" project (SwiftUI interface,
SwiftData storage, iOS 17+ deployment target, bundle id `com.mattdixon.jobjar`), then drag the
contents of `JobJar/` (everything under `Models/`, `Data/`, `Util/`, `Views/`, plus
`JobJarApp.swift` and `ContentView.swift`, replacing Xcode's generated versions of those two)
into the new project.

## Known gaps vs. the Android app

- No app icon artwork - `AppIcon.appiconset` is a valid but empty placeholder.
- No equivalent of Android's Material 3 dynamic color; this uses the system accent color
  instead, which is the idiomatic iOS analog rather than a literal port.
