# Contributing to Foldman

Thanks for wanting to help. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/deveshrai/Foldman/issues/new/choose) using the bug template. The most useful details are:
- your phone model and Android version;
- the Foldman version (Android Settings → Apps → Foldman);
- which source you were using (local folder, Koda/YouTube Music, Koda/RSS podcasts, Apple Music via MusicKit, or Apple Music via media session);
- the posture (folded, unfolded, half-open);
- a screenshot or screen recording.

If Foldman crashed, a log helps a lot:

```bash
adb logcat -d -b crash > crash.txt
```

## Building

See [Building from source](https://github.com/deveshrai/Foldman/wiki/Building-from-source) in the wiki. In short, `./gradlew :app:assembleDebug` builds without any Apple setup; MusicKit is optional. The build fetches Koda's dependencies from Maven Central and JitPack.

## Pull requests

- Keep each change focused, and describe what you tested and on which device or emulator. A foldable emulator, such as the Pixel 9 Pro Fold AVD, covers all three postures.
- Match the surrounding code: Kotlin and Jetpack Compose, comments that explain *why*, and no new dependencies without a reason.
- Never commit secrets or Apple's files. `apple-music.properties`, `signing.properties`, `*.p8`, `*.jks` and `app/libs/*.aar` are git-ignored; keep them that way.
- UI changes: include before and after screenshots.
- The launcher icon is generated. Edit `art/icon.svg` and run `python3 tools/svg_to_icon.py`.

By contributing, you agree your work is released under the project's [GPL-3.0](LICENSE). Existing Foldman files retain their Apache-2.0 notices.
