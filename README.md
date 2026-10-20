# Quartz Downloader

An Android video and music downloader with a purple interface, website browser, Android Share support, Spotify playlist matching and a local music library.

## Install

Get the signed APK from [the latest release](https://github.com/happyachraf/quartz-downloader/releases/latest).

- Android 10 or newer; ARM64 phones and x86_64 emulators.
- Install over an existing Quartz Downloader installation to retain its app data.
- Starting with 0.3.4, Quartz checks GitHub Releases for app updates when opened. Settings also has Check for updates. Android asks you to confirm installation.
- Users on older versions install 0.3.4 or later manually once to get the updater.

## Features

- Share a supported video link to Quartz and choose an available video or audio format.
- Built-in website browser and local website sign-in sessions for X, Facebook and Instagram.
- Facebook shares load through that saved browser session first, resolve the final post link, then offer download formats without a manual browser visit. Facebook can still require sign-in or decline access to a post.
- Download queue, foreground progress notifications, Pause/Resume and retained compatible partial downloads.
- Public Spotify playlist metadata, reviewed YouTube matches, independent saved playlist workspaces and audio downloads from selected matches.
- Original source audio or 320 kbps MP3, with artwork and music tags when available.
- Music library, player, playlist groups, long-press selection, Select all and bulk removal.
- GitHub-only app-update checks, checksum and original-signer validation, and Android installation confirmation.

Spotify itself is used for playlist metadata and artwork; Quartz does not decrypt Spotify audio. Available formats, website access and matching accuracy depend on the source. Review uncertain recordings. Normal background work is supported, while Android Force stop and vendor/system restrictions can stop an app. Saved work can be resumed. No private-account access or every-provider guarantee is made.

## Build

Use JDK 21 and Android SDK platform 36. The application targets Android 15 and supports Android 10+.

```shell
./gradlew :app:assembleRelease :app:testReleaseUnitTest
```

Without the private signing configuration this creates an unsigned release. For local signed builds, place the original keystore at `signing/quartz-release.p12` and local properties at `signing/release-signing.properties` containing `storePassword` and `keyPassword`; the signing alias is `quartz`. These files are excluded from Git and must never be committed.

The version name and code are in `app/build.gradle`. Test-only overrides `-PquartzVersionName=... -PquartzVersionCode=...` support update verification; test bootstrap APKs are not released.

## Releases

Release assets include the signed APK, corresponding source ZIP and SHA256SUMS. Version tags are `vMAJOR.MINOR.PATCH`. The updater accepts the APK named `Quartz-Downloader-Android-MAJOR.MINOR.PATCH.apk`, from this repository's matching stable release only.

To publish from an authenticated development machine, use `scripts/github-release.py`. Keep signing keys local. No GitHub credential is included in the Android app. Preserve the original application ID and signing certificate for compatible upgrades.

## Verification

`app/src/test` contains JVM checks. `app/src/androidTest` contains a separate Android instrumentation runner for targeted flows. Tests use an owned emulator and temporary fixtures; they do not substitute for testing a particular physical phone. The 0.3.4 release verifies update metadata, actual APK checksum/package/version/signing validation, provider access boundaries and update UI. Prior release notes describe earlier targeted checks and their limits.

## Licence

Application source is GPL-3.0; see [LICENSE](LICENSE). Reused branding and third-party components have their own notices. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and the included font/icon licences. Release source archives exclude signing credentials, caches, browser sessions, device data and downloaded media.
