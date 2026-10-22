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

## GitHub Actions APKs

The **Build APKs** workflow uploads separate debug and release APK artifacts. The release APK uses the repository's Android signing key; configure these repository Actions secrets before running it:

- `ANDROID_SIGNING_KEYSTORE_BASE64`: base64-encoded `release-key.jks` (`base64 -w0 release-key.jks` on Linux);
- `ANDROID_SIGNING_STORE_PASSWORD`: `storePassword` from your local `signing.properties`;
- `ANDROID_SIGNING_KEY_ALIAS`: `keyAlias` from `signing.properties`;
- `ANDROID_SIGNING_KEY_PASSWORD`: `keyPassword` from `signing.properties`.

Add them under **Settings → Secrets and variables → Actions → New repository secret**. Keep the keystore and `signing.properties` backed up securely; both are git-ignored, and losing the keystore means future releases cannot update installations signed with it. The separate **Android tests** workflow runs `./gradlew test` on pushes and pull requests.

## Pull requests

- Keep each change focused, and describe what you tested and on which device or emulator. A foldable emulator, such as the Pixel 9 Pro Fold AVD, covers all three postures.
- Match the surrounding code: Kotlin and Jetpack Compose, comments that explain *why*, and no new dependencies without a reason.
- Never commit secrets or Apple's files. `apple-music.properties`, `signing.properties`, `*.p8`, `*.jks` and `app/libs/*.aar` are git-ignored; keep them that way.
- UI changes: include before and after screenshots.
- The launcher icon is generated. Edit `art/icon.svg` and run `python3 tools/svg_to_icon.py`.

By contributing, you agree your work is released under the project's [GPL-3.0](LICENSE). Existing Foldman files retain their Apache-2.0 notices.
