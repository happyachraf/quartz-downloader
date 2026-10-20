# Quartz Downloader 0.3.9

## One-time YouTube downloads

- Remove the five-minute preview cache between separate YouTube lookups. Every separate lookup fetches fresh video formats.
- Keep the current preview's formats for its selected download, so pressing Download can start without extracting that video again. This temporary transfer is private, expires, and is removed on completion or cancellation.
- Retain lookup during shared-link loading, buffered format discovery, HD recovery and immediately visible highest-quality options.

This change follows the user's one-time download workflow. Removing repeat-preview reuse does not make first-time network extraction faster by itself. Fresh lookup timings are reported separately; no fixed 2–5 second promise is made.

Spotify playlist workspaces and matching history retain their existing behavior.
