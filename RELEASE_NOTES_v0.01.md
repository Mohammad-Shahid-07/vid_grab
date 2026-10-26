# YouDrop v0.01

**The first public release of YouDrop: a private, ad-free YouTube browser and downloader for Android.**

<img src="https://raw.githubusercontent.com/Mohammad-Shahid-07/vid_grab/main/assets/branding/banner.png" alt="YouDrop" width="100%" />

## Browse

- Built-in YouTube browser with download buttons on videos, Shorts and playlists.
- Ad and tracker blocking, with a switch in Settings.
- Background audio playback when the screen is off.
- Search, paste a URL, or share a link from another app.
- Dark interface with multiple themes and accent tones.

## Download

- Video or audio-only downloads.
- Quality presets from 144p to 4K when the source provides it.
- Video codec, container and audio-track selection.
- Playlist batches with item selection, ordering and `01`, `02` numbering.
- Subtitle and chapter embedding when available.
- Folder presets for Downloads, Music and Movies.
- 1-5 simultaneous downloads.
- Pause, resume and retry without discarding completed bytes; the queue survives app restarts.
- Background progress notifications and a battery-optimization exemption for long jobs.
- MediaStore export to `Download/YouDrop` and `Music/YouDrop`.

## Engines

- **Fast**: parallel segmented downloads for healthy connections.
- **Compatibility**: NewPipe/DirectJVM path for unusual streams.
- Automatic fallback to the other native engine, then bundled yt-dlp with FFmpeg.

## Privacy

- No sign-up, analytics, crash reporting or ad SDK.
- Cookies and downloads stay on your device.
- Android 10 (API 29) or newer.
- Open source under GPL-3.0.

## Install

1. [**Download the universal APK**](https://github.com/Mohammad-Shahid-07/vid_grab/releases/download/v0.01/YouDrop-v0.01-universal.apk) (215 MB). One file that works on every supported device.
2. Open the file and allow **Install unknown apps** when prompted.
3. Launch YouDrop and allow notifications for download progress.
4. Optional: exempt YouDrop from battery optimization for long background downloads.

<details>
<summary><strong>Smaller builds for specific devices</strong></summary>

<br />

| Build | Device | Size |
| :--- | :--- | ---: |
| `YouDrop-v0.01-arm64-v8a.apk` | Most phones from 2017 onward | 62 MB |
| `YouDrop-v0.01-armeabi-v7a.apk` | Older 32-bit ARM phones | 55 MB |
| `YouDrop-v0.01-x86_64.apk` | Emulators and x86 Chromebooks | 64 MB |

All builds contain the same app and are signed with the same key. When in doubt, use the universal build.

</details>

Verify downloads with `SHA256SUMS.txt`:

```bash
sha256sum -c SHA256SUMS.txt
```

## Notes

- The APK is large because Python, yt-dlp, FFmpeg, aria2c and QuickJS are bundled for on-device extraction and merging.
- YouTube changes can break extraction until the bundled engines catch up.
- Private, protected, DRM and age-restricted content is not supported.
- 4K availability depends on the source video.
- This is an early release (`0.01`); report issues with a logcat if possible.

## License and legal

YouDrop is released under the **GNU General Public License v3.0**. See the repository's `LICENSE` and `THIRD_PARTY_NOTICES.md`.

YouDrop is not affiliated with, endorsed by, or sponsored by YouTube, Google, or any content owner. Download only content you own or have permission to save. You are responsible for complying with copyright law and the terms of any service you use.

---

**Full documentation:** [README](https://github.com/Mohammad-Shahid-07/vid_grab#readme)
