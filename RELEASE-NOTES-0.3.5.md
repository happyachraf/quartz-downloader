# Quartz Downloader 0.3.5

20 October 2026. Facebook sharing uses the saved browser session first.

- Sharing or previewing a Facebook link loads its page through Quartz's WebView cookie store before media extraction. No initial direct extraction attempt and no manual browser visit for normal shared videos.
- Waits for Facebook share redirects and uses the canonical post/video URL, with the browser user agent, cookies and page referrer passed to extraction.
- Downloads and retries also refresh the Facebook page/session first. Existing queue pause and resume controls remain in use.
- Recognizes Facebook login/checkpoint pages, including the mobile home login form. The share sheet offers Sign in to Facebook and retries the preview on returning with a session.
- Website content receives no native bridge. Only HTTPS Facebook pages are used for resolution, and background WebViews are destroyed on completion or cancellation.
- GitHub in-app updates from 0.3.4 remain available.

## Verification scope

Release build, required lint gate and JVM checks passed. Focused Android checks exercise URL identity, redirects, login detection, a real WebView page fixture, an HttpOnly session cookie exported to extraction, cancellation and the actual share UI.

The user's supplied Facebook link redirects to Facebook's login page on the signed-out emulator, as confirmed by both background resolution and the visible Quartz browser. Its signed-in media download cannot be confirmed without the user's account session on their phone. No successful download of that particular video is claimed.

Install over the existing app with the original signing identity. Do not uninstall to update. The source archive excludes signing keys, browser sessions and device/test outputs.
