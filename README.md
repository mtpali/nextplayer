# Player

Player is a customized Android video player based on
[Next Player v0.18.0](https://github.com/anilbeesetti/nextplayer/releases/tag/v0.18.0).
It is written in Kotlin with Jetpack Compose and distributed under the GNU GPL v3.

This fork adds direct online playback, an integrated background download manager,
frame screenshots, custom branding, and a redesigned promotional About page.
The shipped APK is intentionally limited to `armeabi-v7a` (ARMv7).

## Custom features

- Paste HTTP, HTTPS, HLS, DASH, or RTSP links in the Network tab and play them directly.
- Download HTTP and HTTPS files in the background with progress, notifications, retry, open, and remove actions.
- Receive browser links through the dedicated “Download with Player” activity.
- Capture the visible video frame from the player controls and save it under `Pictures/Player`.
- Use the supplied Player launcher icon without adaptive-icon zoom.
- Show Mobile Tina Instagram and developer Telegram destinations in a modern About screen.
- Minify, shrink, rename, and repackage release code with R8.

The legacy SMB, FTP, SFTP, and WebDAV browsing and playback implementation and its
third-party protocol dependencies have been removed.

## Build

Use JDK 17 and the checked-in wrapper:

```bash
./gradlew assembleReleaseWithDebugSigning
```

The ARMv7 APK is written to:

```text
app/build/outputs/apk/release-with-debug-signing/app-armeabi-v7a-release-with-debug-signing.apk
```

Pull requests run the debug build, minified ARMv7 build, unit tests, and ktlint.
The downloadable GitHub Actions artifact is named `Player-armv7`.

## Upstream

Player retains the original package structure and the upstream player, library,
subtitle, decoder, Android TV, playlist, vault, and appearance functionality from
Next Player. See the original project for its full history and contributors:
[anilbeesetti/nextplayer](https://github.com/anilbeesetti/nextplayer).

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
