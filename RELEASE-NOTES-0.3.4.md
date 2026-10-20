# Quartz Downloader Android 0.3.4

20 October 2026. Adds GitHub-based in-app updates to Quartz Downloader.

## User experience

- Quartz checks the latest stable release at https://github.com/happyachraf/quartz-downloader when opened, at most once every six hours automatically. Manual checks are available in Settings.
- An update prompt shows the version, release notes and installer size, with Download update / Later. Later suppresses the automatic prompt for that version for 24 hours; manual checks still open details.
- Android's DownloadManager downloads the APK and retains it if Quartz is closed. On return, Quartz verifies it and offers Install update.
- Android may ask to allow installs from Quartz, then displays its ordinary installation confirmation. No silent installation is requested.
- The same package and original signing key preserve the update identity. Users on 0.3.3 or earlier manually install 0.3.4 once; future releases can be offered inside the app.

## Trust and privacy

The app contains no GitHub token. It accepts one stable versioned APK at the expected repository release URL, checks its GitHub SHA-256 digest and size, and verifies its package, version and signer against the installed app. A verified copy is kept in private app storage. A non-exported, read-only provider exposes only that APK through a temporary Android URI grant. Other arbitrary paths and writes are rejected.

The public repository contains source, build configuration, tests and licences. Private signing material, caches, browser profiles/cookies, emulator data, test output and downloaded media are excluded.

## Requested scope

> yes just github and our app
> Quartz downloader

Only GitHub Releases and the existing Android app are used. Quartz Downloader is a separate product from Quartz Notes.

## Verification

Verification results are recorded in outputs/0.3.4/CHECKS.md. A local test-only bootstrap build can identify itself as 0.3.3 / code 10 to exercise a real GitHub upgrade to the signed 0.3.4 / code 11 release. Test-only bootstrap APKs are never published.
