# Player

Player is a customized Android video player based on
[Next Player v0.18.0](https://github.com/anilbeesetti/nextplayer/releases/tag/v0.18.0).
It is written in Kotlin with Jetpack Compose and distributed under the GNU GPL v3.

This fork adds direct online playback, an integrated background download manager,
frame screenshots, custom branding, and a redesigned promotional About page.
The shipped APK is intentionally limited to `armeabi-v7a` (ARMv7).

## Custom features

- Paste HTTP, HTTPS, HLS, DASH, or RTSP links in the Network tab and play them directly.
- Download HTTP and HTTPS files in the background with up to 8 range connections and 3 active files, progress, notifications, pause/resume, retry, open, and remove actions. Cancel active, queued, or paused downloads from their notification to discard the transfer and its temporary files.
- Preserve APK and other real filename extensions, including encoded server filenames and redirected URLs. Generic server MIME types do not rename real extensions to `.bin`.
- Validate byte ranges and file validators before resuming, retry temporary network failures, and fall back to one connection when a host stops honoring ranges. Paused downloads remain paused when reopening the list.
- Receive browser links through the dedicated “Download with Player” activity.
- To choose Player in Firefox's download dialog, enable Settings → Download Settings →
  Manage downloads with another app. The download activity accepts typed HTTP(S)
  links without appearing as a handler for files already saved on the device.
  Untyped links opened from other browsers still use the separate link handoff.
- Capture the visible video frame from the player controls and save it under `Pictures/Player`.
- In Settings → Subtitle, choose a local TTF/OTF font, switch subtitle text between
  white and yellow, and enable a black text shadow. Custom appearance takes precedence
  over styles embedded in subtitle files; system caption style can still be selected.
- Use the supplied Player launcher icon without adaptive-icon zoom.
- Long-press a video for Rename, Share, Info, and Delete. Deletion requests the
  required Android permission and refreshes the library; failed requests show a message.
- Show Mobile Tina Instagram and developer Telegram destinations in a modern About screen.
- Minify, shrink, rename, and repackage release code with R8.
- Keep navigation to Home and Network. The More tab and its History and Trash screens have been removed from the source.
- Start with Videos, List layout, Date / Newest sorting, and all eight metadata fields enabled. Apply these defaults once when upgrading; later Quick settings changes remain saved.

The legacy SMB, FTP, SFTP, and WebDAV browsing and playback implementation and its
third-party protocol dependencies have been removed.

## Build

Use JDK 17 and the checked-in wrapper:

```bash
./gradlew assembleRelease-with-debug-signing
```

The ARMv7 APK is written to:

```text
app/build/outputs/apk/release-with-debug-signing/app-armeabi-v7a-release-with-debug-signing.apk
```

Pull requests run the debug build, minified ARMv7 build, unit tests, and ktlint.
The downloadable GitHub Actions artifact is named `Player-armv7`.

## Upstream

Player retains the original package structure and the upstream player, library,
subtitle, decoder, Android TV, and appearance functionality from
Next Player. See the original project for its full history and contributors:
[anilbeesetti/nextplayer](https://github.com/anilbeesetti/nextplayer).

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).
