# Koda integration

Foldman vendors the Android source from [Garrietta/Koda](https://github.com/Garrietta/Koda), revision `0d9be5146499fcfd4ecb8db3a500aa3f7ef89c84` (GPL-3.0). The source and resources are under `src/`; `README.upstream.md`, `LICENSE`, and `THIRD_PARTY_NOTICES.md` preserve the upstream project information and notices.

Koda is built as an Android library in this repository. Foldman opens Koda's `MainActivity` for YouTube Music sign-in, music browsing, and RSS podcast browsing, and connects to Koda's `MusicService` through Media3 for playback state and transport controls. Foldman's manifest removes Koda's launcher aliases so the combined APK has one launcher entry.

The vendored upstream source is otherwise retained. Keep Koda's GPL-3.0 terms and third-party notices with any redistribution; see the repository-root `LICENSE`, `LICENSE-APACHE-2.0`, and `NOTICE`.
