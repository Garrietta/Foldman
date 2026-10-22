# Privacy and permissions

Foldman has no analytics, ads or servers of its own. YouTube Music, podcast RSS feeds, and the embedded Koda playback engine make network requests to their respective providers.

## Permissions

| Permission | Why Foldman asks |
| --- | --- |
| **Internet** | Connects to YouTube Music, fetches RSS podcast feeds and album art. |
| **Folder access** (the system folder picker) | Read access to the one music folder you choose, and nothing else on the phone. |
| **Media and file access** | Koda can read local audio/video and manage downloaded media when you use those features. Android may ask for media or broader file access. |
| **Notifications** | Shows media playback controls and download progress when those features are active. |
| **Foreground services** | Keep local or streamed media playing in the background and support user-started downloads. |
| Wake lock, network state | Used by streaming, playback, and network-dependent features. |

## What's stored on the phone

Foldman keeps a few settings in its private app storage:
- the source in the deck;
- the music folder you picked;
- the CLICK and HAPTIC switch positions;
- Koda's local music/podcast library data and any downloads you choose to save;
- your YouTube Music session cookies and Koda account profile, stored by Koda in encrypted app-private preferences after you sign in.

Your sign-in data is sent to the relevant provider when you use its service. Foldman opts out of Android's cloud backup and of device-to-device transfer, so these local tokens and cookies aren't copied into a backup or onto a new phone; on a new phone you sign in again. Uninstalling Foldman deletes the app's private data.

> Versions 0.1.0 and 0.1.1 allowed Android's backup, so their settings could be included in your phone's backup. 0.1.2 and later don't. Android replaces backups over time, and you can delete an existing one in your phone's Google backup settings.

## What goes over the network

- **Google/YouTube**: Koda signs in through an embedded WebView and sends session cookies with requests to YouTube/YouTube Music for search, account library, metadata, playback, and any account features you use. This is an unofficial integration, not a Google SDK. See [Google's privacy policy](https://policies.google.com/privacy).
- **Podcast publishers**: Koda requests the RSS feed URL you choose and streams episode media from the publisher or hosting service.
- **Other media hosts**: Koda contacts the selected media source for playback and downloads when you use those features.
- **Album art URLs** provided by Koda or the podcast feeds are downloaded to draw the cassette label.

Local music never leaves the phone.

## Signing out

Sign out of YouTube Music in Koda's sign-in screen to remove the saved account session.
