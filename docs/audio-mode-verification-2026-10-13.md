# Audio Mode Verification

## Changes

- Tablet audio panes reuse the full phone audio interface, with layout selected by actual pane constraints.
- Playback, loop, previous/next, favorites, speed and queue controls operate on the shared playback session.
- Narrow layouts wrap speed presets and scroll the expanded picker into view.
- The waveform translates under a fixed center reference. Dragging previews time; release commits one seek; cancellation commits none.
- Audio-only playback disables video tracks. Returning to video restores decoding without restarting the audio session.
- Surface detach is identity-checked; attaching a new surface while paused refreshes the current frame without resuming playback.
- Audio/video navigation, pure-audio entry and fullscreen-to-pane motion are included.

## Verification

- `gradlew.bat test lint :app:assembleDebug :app:assembleRelease`: successful.
- Debug/JVM results: 37 suites, 199 tests, no failures or errors.
- Lint: no errors; three existing app warnings. Player UI lint reports no issues.
- Pixel phone emulator: pure audio, Back, mini-player entry, audio/video switching, green-video pixel assertions.
- Pixel tablet emulator: the same checks, full audio controls, fullscreen return, paused surface handoffs and video pixel assertions.
- Tablet 2000x1300 override: favorites, all speed presets, engine speed, queue and modal Back verified; original size restored.
- Waveform tests on both emulators: fixed reference pixels, visible waveform translation, unchanged engine position during drag, release seeking, reverse dragging and cancellation.
- Fullscreen return recording contains eight distinct video rectangles moving from fullscreen toward the original pane, without overshooting its horizontal position.

## Artifacts

- Release APK: `None Player/app/build/outputs/apk/release/app-release.apk`.
- Regression scripts: `audio-mode-regression.ps1`, `waveform-regression.ps1`, `audio-pane-controls-regression.ps1`.
- Build log: `audio-mode-layout-final-2026-10-13.log`.
- Screenshots and motion recording: `screenshots/audio-mode-2026-10-13/`.

Only emulators were used. The connected physical device and existing application data were not cleared or modified.
