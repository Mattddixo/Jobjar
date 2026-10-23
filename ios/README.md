# Job Jar for iOS

SwiftUI + SwiftData version of the Android app in `../app`. Same rules, same backup file
format, iOS 17+, no third-party dependencies.

## Building

The Xcode project is generated with [XcodeGen](https://github.com/yonaskolb/XcodeGen) from
`project.yml` (the `.xcodeproj` isn't checked in):

```bash
brew install xcodegen
cd ios/JobJar
xcodegen generate
open JobJar.xcodeproj
```

Then run the `JobJar` scheme on an iOS 17 simulator or device (Cmd-U runs the tests).
Regenerate after adding or moving files.

No Mac? The `iOS` GitHub Actions workflow builds it, runs the tests, and can publish an unsigned
`JobJar.ipa` for sideloading. See [docs/IPHONE.md](../docs/IPHONE.md).

No XcodeGen? Make a new SwiftUI + SwiftData app in Xcode (iOS 17, bundle id
`com.mattdixon.jobjar`) and drag in everything under `JobJar/`, replacing the generated
`JobJarApp.swift` and `ContentView.swift`.

## Layout

```
JobJar/
├── JobJarApp.swift / ContentView.swift   App, tabs, navigation
├── Models/Job.swift                      The SwiftData model
├── Data/JobRepository.swift              All the job rules, undo
├── Data/CalendarGateway.swift            Writes scheduled jobs to the calendar (EventKit)
├── Data/JobExport.swift                  Backup file (same as Android)
├── Util/                                 Duration, repeat and date formatting
├── Views/                                Jar, Jobs, Job, Add/edit, Stats, Settings, shared bits
└── Assets.xcassets                       Icon, accent colour, drawn-card colour
JobJarTests/                              Repository and backup tests (in-memory store, fake calendar)
```

Views read with `@Query`; anything that changes data goes through `JobRepository`.

## Compared with Android

Same features: the jar (time, how many, several categories), starting jobs, scheduling them into
the calendar, subtasks with dependencies, repeating jobs (every N days or set weekdays), search
and filters, undo, stats, light/dark/auto, export/import.

Calendar events are tagged with a `jobjar://job/<id>` URL and the app only ever edits or deletes
events carrying its own tag. Event ids aren't put in backups, since they only mean something on
the phone that made them.
