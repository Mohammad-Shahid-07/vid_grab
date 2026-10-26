# Third-party notices

YouDrop is built on open-source software. This file summarizes the major components
distributed in, or used by, the Android application. The full license text for each
component is available from the upstream project linked below.

YouDrop itself is licensed under the **GNU General Public License v3.0**; see
[`LICENSE`](LICENSE).

## Media extraction and processing

| Component | License | Source |
| :--- | :--- | :--- |
| **NewPipeExtractor** | GPL-3.0 | https://github.com/TeamNewPipe/NewPipeExtractor |
| **youtubedl-android** (`io.github.junkfood02.youtubedl-android`) | GPL-3.0 | https://github.com/junkfood02/youtubedl-android |
| **yt-dlp** | Unlicense | https://github.com/yt-dlp/yt-dlp |
| **FFmpeg** | LGPL-2.1-or-later (build-dependent) | https://ffmpeg.org/legal.html |
| **aria2** | GPL-2.0-or-later | https://github.com/aria2/aria2 |
| **QuickJS** | MIT | https://bellard.org/quickjs/ |
| **CPython** | PSF-2.0 | https://www.python.org/psf/license/ |
| **Mozilla Rhino** | MPL-2.0 | https://github.com/mozilla/rhino |

`youtubedl-android` bundles yt-dlp, Python, FFmpeg, aria2c and QuickJS so extraction
and merging can run entirely on the device. Those bundled binaries remain under
their respective upstream licenses. Source code for the GPL-licensed components is
available at the links above. The exact YouDrop source for this release is available
in this repository at tag `v0.01`.

## Android and Kotlin

| Component | License | Source |
| :--- | :--- | :--- |
| **AndroidX / Jetpack Compose / Room / WorkManager** | Apache-2.0 | https://github.com/androidx/androidx |
| **Kotlin** | Apache-2.0 | https://github.com/JetBrains/kotlin |
| **Kotlin Coroutines** | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| **OkHttp / Okio** | Apache-2.0 | https://github.com/square/okhttp |
| **Timber** | Apache-2.0 | https://github.com/JakeWharton/timber |

## Notes

- YouDrop does not modify the upstream libraries listed above.
- License texts are also embedded in the corresponding dependency artifacts where
  the upstream project provides them.
- If you redistribute a YouDrop APK, keep this notice, the GPL-3.0 license text, and
  a link to the corresponding source code with the distribution.
