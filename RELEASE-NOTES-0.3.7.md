# Quartz Downloader 0.3.7

## Added

- Settings > Simultaneous downloads: choose one, two or three downloads. Two is the default.
- Separate progress notifications and Pause/Cancel controls for each running download, with Pause all in the summary notification.
- Two Spotify match searches at once. Each completed search is saved immediately.
- Successful Spotify search results are reused across playlists for seven days. Search again clears the relevant cached result; match ranking and review rules are unchanged.

## Performance

- The music player requests only its playback state instead of repeatedly loading all downloads and playlists.
- Background screens stop polling; returning to Quartz refreshes the current state.
- Download records and Spotify workspaces use in-memory snapshots for repeated reads. Durable queue/control changes and completed Spotify matches remain saved.
- Frequent download progress writes avoid blocking on a disk flush. Pause, resume and completion checkpoints retain synchronous persistence.
- Failed extractor-update checks are throttled for one hour so an unavailable update server does not delay every new request.

## Verification

Android emulator checks cover real concurrent HTTP media transfers, one/two/three download limits, individual pause, pause all, byte-range resume, background transfers, notifications, valid saved video/audio, real overlapping YouTube match searches, cache reuse and search pause/resume. Additional history and selection checks protect saved files and workspaces. This is targeted release verification; source websites and physical-phone behavior can vary.

Original source audio and 320 kbps MP3 options remain available. Multiple downloads share the available connection; they do not increase its total bandwidth.
