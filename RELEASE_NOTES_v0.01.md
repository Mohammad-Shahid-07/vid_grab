# YouDrop v0.01

**The first public build of YouDrop — a private, ad-free YouTube browser and multi-engine downloader for Android.**

<img src="https://raw.githubusercontent.com/Mohammad-Shahid-07/vid_grab/main/assets/branding/banner.png" alt="YouDrop" width="100%" />

## Highlights

- **Built-in YouTube browser** with download actions injected on videos, Shorts and playlists.
- **Ad and tracker blocking**, background audio, and a clean dark interface.
- **Video or audio downloads** with quality, codec, container and audio-track selection.
- **Up to 4K** when the source provides it, from 144p data-saver presets to 2160p.
- **Playlist batches** with item selection, ordering and optional `01`, `02` numbering.
- **1-5 parallel downloads** and a global Fast / Compatibility engine setting.
- **Automatic engine fallback** from the fast segmented downloader to the compatibility path, then bundled yt-dlp with FFmpeg.
- **Resumable queue** that reuses partial files after a pause, network drop or app restart.
- **MediaStore export** to `Download/YouDrop` and `Music/YouDrop`.

## The pause-during-merge fix

This release includes a rewrite of the pause/resume lifecycle around the final merge step.

Previously, pausing a task while it displayed `MERGING` and then resuming could restart the transfer from zero. YouDrop now writes a `.vidgrab-active-engine` marker (`fast`, `jvm`, `ytdlp1` or `ytdlp2`) inside the task working directory before each engine or yt-dlp stage starts. On resume it continues with the same engine, session and partial files. Tasks paused by older builds are inferred from their sandbox files. An explicit pause can also no longer be converted back to `PENDING` by the engine's cleanup block.

## Install

1. Download the APK for your device below.
2. Open the file and allow **Install unknown apps** when prompted.
3. Launch YouDrop and allow notifications for download progress.
4. Optional: exempt YouDrop from battery optimization for long background downloads.

| Build | Device | Size |
| :--- | :--- | ---: |
| `YouDrop-v0.01-arm64-v8a.apk` | Most modern Android phones | 62 MB |
| `YouDrop-v0.01-armeabi-v7a.apk` | Older 32-bit ARM phones | 55 MB |
| `YouDrop-v0.01-x86_64.apk` | Emulators and x86 Chromebooks | 64 MB |
| `YouDrop-v0.01-universal.apk` | Works on every supported ABI | 215 MB |

**Requirements:** Android 10 (API 29) or newer.

Verify downloads with `SHA256SUMS.txt`:

```bash
sha256sum -c SHA256SUMS.txt
```

## Notes

- The APK is large because Python, yt-dlp, FFmpeg, aria2c and QuickJS are bundled for on-device extraction and merging.
- YouTube changes can break extraction until the bundled engines catch up.
- Private, protected, DRM and age-restricted content is not supported.
- 4K availability depends on the source video.
- This is an early release (`0.01`); expect rough edges and report them with a logcat if possible.

## License and legal

YouDrop is released under the **GNU General Public License v3.0**. See the repository's `LICENSE` and `THIRD_PARTY_NOTICES.md`.

YouDrop is not affiliated with, endorsed by, or sponsored by YouTube, Google, or any content owner. Download only content you own or have permission to save. You are responsible for complying with copyright law and the terms of any service you use.

---

**Full documentation:** [README](https://github.com/Mohammad-Shahid-07/vid_grab#readme)
