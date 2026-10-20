# Quartz Downloader 0.3.6

20 October 2026. Download Facebook videos exposed by the browser session.

The previous release loaded Facebook with the browser session, then fetched the post again through the extractor. That could still fail for a shared post even when the browser could play its attached video.

- Read the attached video's delivery data from the loaded page, including Facebook's node_v2 shared-post structure and videoDeliveryResponse streams.
- Select the shared post's video and avoid unrelated recommendations.
- Keep the available DASH resolutions and separate source audio, then pass the captured media directly to the existing download service. A second Facebook post extraction is unnecessary when browser media is available.
- Retain media metadata privately for queued downloads and resume, refresh older metadata through the browser, and remove completed job metadata.
- Restrict captured media to HTTPS Facebook CDN addresses. Account cookies and page HTML are not saved in the media metadata.
- Retain browser-session-first sharing, Facebook sign-in handling, download options, notifications and GitHub updates.

## Verification

Using only the target video's media data exposed by the user's signed-in Facebook page, an Android WebView fixture exercised the production page parser, native preview and download service. The actual video downloaded at 1920 x 1080 with stereo AAC audio, about 24.31 seconds long. Full video/audio decoding completed without errors, with non-silent audio.

The browser session used for diagnosis was the desktop in-app browser. It was not copied to the Android emulator or embedded in the APK. An authenticated Share flow on the user's physical phone remains a separate device check.

Install over the existing Quartz app, or use Settings > App updates > Check for updates. Keep the original app data and website sessions.
