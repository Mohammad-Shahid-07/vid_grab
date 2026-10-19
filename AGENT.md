# Agent Operating Guidelines & Autonomous Audit Protocol

This document establishes the mandatory protocol for all agentic development in this project (**VidGrab**).

---

## 1. Primary Mandate: The Continuous Audit Log

You must maintain an exhaustive, real-time audit record in **`AUDIT_LOG.md`**. Every single file created, command recommended, dependency introduced, and function written must be logged with high technical fidelity so that a **Supervisor / Auditor AI** can review, verify, and validate correctness against 2026 Android & YouTube standards.

### Mandatory Structure for Every Entry in `AUDIT_LOG.md`:
1. **Timestamp & Target File/Component**: Exact path and purpose.
2. **Technical Rationale**: Why this approach was taken over alternatives.
3. **Architecture & State Flow**: How this component interacts with the rest of the system (WebView, JVM, Native Libraries, WorkManager, MediaStore).
4. **Code / Schema Specification**: Full implementation details, function signatures, error boundaries, and parameters.
5. **Edge Cases & Anti-Bot Defenses Covered**: Explicitly document how this code avoids YouTube throttling, HTTP 403 Forbidden, Android 15/16 background timeouts, and memory leaks.
6. **Supervisor Review Checklist**: A verification checklist for the auditor AI to evaluate.

---

## 2. Core Architecture Rules (The 2026 Dual-Engine Blueprint)

1. **Dual-Engine Extraction**:
   - **Never** invoke Python (`yt-dlp`) on thumbnail tap. Metadata for the quality picker bottom sheet must be fetched in <300ms using either:
     - Direct in-memory interception (`window.ytInitialPlayerResponse.streamingData`) from the WebView, or
     - `NewPipeExtractor` running in the warm Android JVM.
   - Hand off to `yt-dlp` **only** after the user confirms their download choice.

2. **YouTube Anti-Bot & Throttling Defenses**:
   - **PO Token Bridge**: Capture `poToken` and `visitorData` by monkey-patching `/youtubei/v1/player` fetch calls inside the WebView and pass them across the `@JavascriptInterface` to `yt-dlp`.
   - **External JS Runtime**: Explicitly pass `--js-runtimes quickjs` (powered by `libqjs.so`).
   - **Download Protocol**: Default to `yt-dlp -N 8` (`--concurrent-fragments 8 --continue --throttled-rate 100K`) to respect YouTube's SABR streaming without triggering 403 blocks. Do not use raw multi-connection `aria2c` for DASH streams.
   - **Authentication**: Strip `; wv` and `Version/X.X` from the WebView `User-Agent` to allow native Google Sign-In. Sync fresh `CookieManager` cookies before each download.

3. **Android 15/16 Background Execution**:
   - Use **User-Initiated Data Transfer (UIDT)** jobs via `WorkManager` for background downloading.
   - Do not rely on continuous 6+ hour `dataSync` Foreground Services.

4. **Zero Unnecessary Boilerplate**:
   - Use modern, production-grade component frameworks, established libraries, and official CLI scaffolding. Do not hand-roll fragile custom parsers or low-level HTTP socket wrappers when audited solutions exist.

---

## 3. Tool & Package Management Rules

1. **NO MANUAL BOILERPLATE OR HARDCODED VERSIONS (STRICT)**:
   - **Never** manually hand-write boilerplate config files (`package.json`, `tailwind.config`, etc.) with statically pinned library versions from memory. Training knowledge goes stale, and newer, faster versions exist (e.g., React 19, Tailwind v4, Vite 6, modern component libraries).
   - **Always** use modern CLI commands and official generators directly (e.g. `bun add <pkg>@latest`, official UI component CLIs) so that the latest, cutting-edge dependencies and standard scaffolding are installed directly without manual boilerplate friction.

2. **Package Manager Preference**:
   - Always prefer and use **`bun`** for any JavaScript/TypeScript/Node tooling and script execution.

3. **Environment Security**:
   - Do **not** read or parse `.env` files.

4. **MANDATORY PRE-EXECUTION PLANNING (STRICT)**:
   - **Always** create and present a clear, structured implementation plan before writing or editing any code.
   - Plans must explicitly define:
     1. Target files and components.
     2. Step-by-step execution sequence.
     3. Architectural impact and anti-bot/OS edge-case handling.
     4. Verification and testing checkpoints.
   - Code writing begins only after the plan is established and reviewed.


