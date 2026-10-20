# Third-party components and reused assets

- Quartz logo and colour palette: reused from the user's existing local Quartz Downloader project at `F:/work/Quartz-Downloader-Quality-Fix/Quartz-Downloader-0.1.12-Source`. Brand assets are preserved separately from the Android code's GPL licence.
- Manrope: reused from that project; see `MANROPE-LICENSE.txt`.
- youtubedl-android 0.18.1, `io.github.junkfood02.youtubedl-android:library` and `:ffmpeg`: https://github.com/yausername/youtubedl-android . GPL-3.0. Includes the Android media runtime, Python, QuickJS and FFmpeg components.
- yt-dlp: https://github.com/yt-dlp/yt-dlp . Version 2026.08.19 is bundled as `app/src/main/res/raw/ytdlp`, overriding the older extractor in the media library. The official zipimport executable's SHA-256 was verified against the release's SHA2-256SUMS: `1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6`. The library checks for subsequent official stable updates. The zipimport release contains ISC and MIT components, including the EJS solver scripts; see the upstream repository for component notices and licences.
- Gradle wrapper: Apache License 2.0; see the copyright and licence headers in `gradlew` and `gradlew.bat`.
- Apache Commons IO 2.16.1: Apache License 2.0; https://commons.apache.org/proper/commons-io/ . Replaces the media library's old 2.5 dependency, whose reflective Java7Support initialization crashes on the tested Android runtime.

The APK contains the required Android media binaries. The source archive contains this application's corresponding source and build files, with the Maven dependency versions pinned in `app/build.gradle`. Signing keys and local browser profiles are excluded.

- Spotify public playlist request structure: informed by the GPL-3.0 spotapi source used by the existing Windows importer. The Android implementation reads the current public Spotify web-player configuration and uses a guest session. It does not bundle account tokens or the SpotipyFree/spotapi Python runtime.
- JUnit 4.13.2: used for local matching safeguards tests; not packaged as an Android runtime dependency. https://github.com/junit-team/junit4 . Eclipse Public License 1.0.
- Google Material Design icons: official Rounded and Outlined SVG assets from https://github.com/google/material-design-icons . Apache License 2.0; see `MATERIAL-ICONS-LICENSE.txt` and `ASSET-SOURCES.json`.
- Simple Icons: YouTube, TikTok, X, Facebook, Instagram and Spotify brand SVGs from https://github.com/simple-icons/simple-icons . See `SIMPLE-ICONS-LICENSE.txt`. Brand trademarks belong to their owners; these identify the linked websites.
