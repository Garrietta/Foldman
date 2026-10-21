Put Apple's MusicKit for Android AARs here (download them from https://developer.apple.com/musickit/
with your Apple Developer account):

- `musickitauth-release-*.aar`: Apple Music sign-in, which unlocks browsing your playlists in Foldman.
- `mediaplayback-release-*.aar`: in-app streaming. Without it, picking a playlist opens it in the
  Apple Music app and Foldman's deck drives that app instead.

Then rebuild. Nothing else to change: Foldman finds the SDK at runtime.
