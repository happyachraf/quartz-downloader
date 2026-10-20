# Quartz Downloader 0.3.8

## YouTube speed and quality

- Use the visionOS client first for public YouTube previews. This route returned the supplied video's full 1440p streams without the slower embedded-player JavaScript challenge.
- A successful 360p-only response no longer ends quality discovery. Quartz checks alternate clients and keeps the best available result.
- Show the highest discovered quality directly in the share options. More formats still exposes the complete resolution list.
- Reuse successful HD previews for five minutes, including equivalent youtu.be and youtube.com watch links. Keep at most eight previews in memory.
- Start YouTube downloads from fresh private preview metadata instead of extracting the same video again. Expired or declined cached streams trigger fresh extraction through the next route.
- Cancel superseded previews and discard stale results, so an old lookup does not block the latest shared video.
- Start shared YouTube lookups while the options screen loads and coalesce duplicate requests for the same link.
- Enable a private extractor cache for reusable player/challenge work.
- Read large metadata through a buffered temporary file, reuse the metadata mapper, and select preview formats explicitly so discovery does not probe the media merger. Streaming manifests are retained so their HD formats remain discoverable.

## Keep maintenance off the lookup path

Extractor update checks and downloads run separately with connection/read timeouts. Updates must come from the official yt-dlp GitHub stable release, match its SHA256 digest and size, and contain a valid engine archive. A verified replacement is installed only while no extraction/download process uses the existing archive. Network checks never hold the execution lock.

## Retained

Simultaneous downloads, individual pause/resume, Spotify matching, audio quality, artwork, notifications, website sessions and Quartz's purple design.

## Verification scope

Targeted release checks use an owned Android 15 emulator, including the exact reported link https://youtu.be/ADy6IeLCoeo and the earlier https://youtu.be/2z6pCa_xehY. Real network timings are samples, not a fixed delay guarantee for every phone or website response. No separate YouTube sign-in window is required for these public videos.
