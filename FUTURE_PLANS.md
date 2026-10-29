# YouDrop Future Plans

Living backlog for features that are agreed on but intentionally not part of the current release.

---

## v0.02 - Default Download Settings

**Status:** Planned
**Requested:** 2026-10-29
**Area:** Settings, quality picker, batch downloads

### Goal

Let the user save one preferred download configuration and have the quality picker open with that configuration already selected, instead of defaulting to the highest quality every time.

### User flow

1. The user opens **Settings -> Download defaults**.
2. They choose:
   - Preferred video quality (e.g. `1080p`, `720p`, `Best available`, `Lowest`).
   - Preferred codec (`H.264 / MP4` or `VP9 / WEBM`), with `Auto` as an option.
   - Preferred audio track (`Auto (best)`, a specific bitrate, or `No audio` if supported).
   - Download folder (Downloads / Music / Movies / custom).
3. The choice is saved on device.
4. The next time the download sheet opens, those values are pre-selected.
5. The user can still change any option for that individual download.
6. The chosen defaults also apply to batch and playlist downloads unless overridden.

### Behaviour rules

- If the saved quality is not available for a video, fall back to the closest lower available quality (never silently upgrade to a higher one). If no lower option exists, use the lowest available.
- If the saved codec is not available, fall back to the other available codec and show the change in the sheet.
- `Best available` keeps the current behaviour: highest resolution the resolver returns.
- Defaults must never block a download. A missing or invalid saved value is ignored and the normal automatic selection is used.
- Changing defaults must not alter downloads that are already queued or running.

### Implementation notes

- Store the defaults in the existing settings store (DataStore/SharedPreferences), not in the queue database.
- Read the saved defaults when `QualityPickerViewModel` initialises and map them onto the resolved `VideoQualityOption` / `AudioQualityOption` lists.
- Keep the selection logic in one place so the single-video and batch paths cannot drift apart.
- Add a **Reset to automatic** action in Settings.

### Acceptance criteria

- A saved `720p` default opens the picker with `720p` selected for a 1080p video.
- A saved `1440p` default falls back to `1080p` on a video that has no 1440p stream.
- Per-download overrides do not change the saved default.
- Settings survive an app restart and an app update.
- Batch downloads use the same defaults.

### Open questions

- Should the default be per-quality only, or a full preset (quality + codec + audio + folder)?
- Should there be a "remember my last choice" option as an alternative to explicit defaults?

---

## Parking lot

Ideas noted for later, not scheduled:

- Website/domain and download page (deferred from v0.01).
- Import/export of app settings.
- Per-channel download rules.
