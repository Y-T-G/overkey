# AGENTS.md

A short brief for anyone, human or AI, making a change to Overkey. The full tour of how the
pieces fit together is in [`ARCHITECTURE.md`](ARCHITECTURE.md) beside this file; this is just how
to work in the repo.

## What Overkey is

An Android app that draws a bar of modifier and navigation keys over any keyboard and delivers
the keys to the app in front through a small helper process. No framework beyond the Android SDK
and Shizuku. One module, `app`, written in Kotlin.

## Build and test

- JDK 17 and the Android SDK. `local.properties` holds `sdk.dir` and is not tracked; on CI the
  `ANDROID_HOME` environment variable is enough.
- Build the release APK: `./gradlew assembleRelease`. Output in `app/build/outputs/apk/release/`.
- There is no unit-test suite. The app is tested on a phone. The one automated check is that it
  compiles, which is what CI runs.
- The release build is signed with the debug key so a clone builds with no keystore. Do not add a
  real key or its password to the repo.

## Layout

- `app/src/main/kotlin/dev/noblebits/overkey/` is all the code.
  - `MainActivity` / `HelpActivity` are the settings and help screens.
  - `OverlayService` runs the bar; `Overlays` draws it and holds every gesture.
  - `Injector` is the helper that runs as root or a shell uid and presses the keys; `InjectorClient`
    is the app side of the loopback protocol.
  - `ClipProvider` serves what Overkey puts on the clipboard; `ShareActivity` is the share target.
- `docs/store/` is the Play listing text and graphics; `tools/*.py` build them.
- `ARCHITECTURE.md` documents the protocol and the reflection into hidden APIs. Keep it in step
  with the code; a change to the protocol or a hidden-API call updates it in the same commit.

## Conventions

- Match the surrounding code: comments explain why, not what, and they are full sentences. The
  KDoc on a class says what problem it solves, not a list of its methods.
- Follow the writing style already in the file. Plain words, no em dashes, no marketing.
- Kotlin, four-space indent, no wildcard imports.
- The protocol has a `VERSION`; bump it when the wire format changes and handle the old client.
- Commit messages are a short imperative summary and a body that says why the change was needed.
  Do not add AI attribution or session trailers to commits.

## Constraints

- No new dependency without a reason. The APK is a few tens of KB and stays small.
- Nothing leaves the phone. The only socket is loopback to the helper. No analytics, no network
  calls to anywhere else.
- Do not commit secrets: no injector token, no keystore, no signing password, no local paths.
