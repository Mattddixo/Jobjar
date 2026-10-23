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

Then run the `JobJar` scheme on an iOS 17 simulator or device. Regenerate after adding or
moving files.

No XcodeGen? Make a new SwiftUI + SwiftData app in Xcode (iOS 17, bundle id
`com.mattdixon.jobjar`) and drag in everything under `JobJar/`, replacing the generated
`JobJarApp.swift` and `ContentView.swift`.

## Layout

```
JobJar/
├── JobJarApp.swift / ContentView.swift   App, tabs, navigation
├── Models/Job.swift                      The SwiftData model
├── Data/JobRepository.swift              All the job rules, undo
├── Data/JobExport.swift                  Backup file (same as Android)
├── Util/                                 Duration and repeat formatting
├── Views/                                Jar, Jobs, Job, Add/edit, Stats, Settings, shared bits
└── Assets.xcassets                       Icon, accent colour, drawn-card colour
```

Views read with `@Query`; anything that changes data goes through `JobRepository`.

## Compared with Android

Has: the jar with time and category, subtasks with dependencies, repeating jobs (every N days or
set weekdays), undo, stats, light/dark/auto, export/import.

Not yet: in-progress jobs, calendar scheduling, and drawing more than one job at a time. The
model would need `isInProgress` and `scheduledDate` first; SwiftData picks up new optional
properties on its own.
