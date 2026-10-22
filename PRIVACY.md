# Privacy and permissions

Foldman has no analytics, ads or servers of its own. The embedded Koda app includes YouTube Music, video, downloads, and RSS podcasts; those features, along with Apple Music, make network requests to their respective providers.

## Permissions

| Permission | Why Foldman asks |
| --- | --- |
| **Notification access** (Settings → Notification access) | Android only lets apps with notification access see other apps' *media sessions*. Foldman's listener does nothing with notifications themselves; it never reads, stores or dismisses them. It is only needed when controlling Apple Music without MusicKit. Koda playback runs inside Foldman and does not need this permission. |
| **Folder access** (the system folder picker) | Read access to the one music folder you choose, and nothing else on the phone. |
| **Media and file access** | Koda can read local audio/video and manage downloaded media when you use those features. Android may ask for media or broader file access. |
| **Notifications** | Shows media playback controls and download progress when those features are active. |
| **Foreground services** | Keep local or streamed media playing in the background and support user-started downloads. |
| **Internet** | Connects to YouTube Music through Koda, fetches RSS podcast feeds and album art, and talks to Apple (below). |
| Wake lock, network state | Used by streaming, playback, and network-dependent features. |

## What's stored on the phone

Foldman keeps a few settings in its private app storage:
- the source in the deck;
- the music folder you picked;
- the CLICK and HAPTIC switch positions;
- Koda's local music/podcast library data and any downloads you choose to save;
- your Apple Music **user token**, after you sign in, and a developer token if you pasted one in.
- your YouTube Music session cookies and Koda account profile, stored by Koda in encrypted app-private preferences after you sign in.

Your sign-in data is sent to the relevant provider when you use its service. Foldman opts out of Android's cloud backup and of device-to-device transfer, so these local tokens and cookies aren't copied into a backup or onto a new phone; on a new phone you sign in again. Uninstalling Foldman deletes the app's private data.

> Versions 0.1.0 and 0.1.1 allowed Android's backup, so their settings, including the Apple Music user token, could be included in your phone's backup. 0.1.2 and later don't. Signing out in Foldman deletes the token from the phone, but not from a backup that was already made. Android replaces backups over time, and you can delete an existing one in your phone's Google backup settings.

## What goes over the network

- **Apple** (`api.music.apple.com` and Apple's streaming services), only when you use Apple Music through MusicKit:
  - Foldman fetches your library playlists and their songs with your user token.
  - Apple's MusicKit player streams the songs and, like any Apple Music player, reports what you played back to Apple.
  - Apple's handling of that data is covered by [Apple's privacy policy](https://www.apple.com/legal/privacy/).
- **Google/YouTube**: Koda signs in through an embedded WebView and sends session cookies with requests to YouTube/YouTube Music for search, account library, metadata, playback, and any account features you use. This is an unofficial integration, not a Google SDK. See [Google's privacy policy](https://policies.google.com/privacy).
- **Podcast publishers**: Koda requests the RSS feed URL you choose and streams episode media from the publisher or hosting service.
- **Other media hosts**: Koda contacts the selected media source for playback and downloads when you use those features.
- **Album art URLs** provided by Koda, Apple Music or Apple's API are downloaded to draw the cassette label.

Local music never leaves the phone.

## Signing out

Sign out of YouTube Music in Koda to remove the saved account session. In Foldman, go to Apple Music → **Sign out** to forget your Apple Music user token. Turning off Foldman's notification access in Android settings stops it seeing other apps' playback.
