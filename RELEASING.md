# Releasing YouDrop

This checklist is for maintainers publishing a new build. The public version of record
is the GitHub Release, not the repository tag alone.

## 1. Prepare the version

In `app/build.gradle.kts`:

```kotlin
versionCode = 1        // increment for every published build
versionName = "0.01"   // user-visible version
```

Update `RELEASE_NOTES_<version>.md`, the download table in `README.md`, and changelog
references before building.

## 2. Release signing

Release builds read signing credentials from `keystore.properties` at the repository
root. This file is git-ignored and must never be committed.

```properties
storeFile=C:/path/to/youdrop-release.jks
storePassword=...
keyAlias=youdrop
keyPassword=...
```

The maintainer's key is stored outside the repository:

```text
%USERPROFILE%\.youdrop\youdrop-release.jks
%USERPROFILE%\.youdrop\keystore.properties
```

**Back up the keystore and its passwords in a secure location.** Losing the key means
existing users cannot install an update over the previous APK. Do not regenerate the
key for a patch release.

If `keystore.properties` is missing, Gradle falls back to the debug signing key so
local release builds still work. Those APKs must not be published.

## 3. Build and verify

```powershell
.\gradlew.bat :app:assembleRelease --console=plain
```

Verify the version, label, signature and ABI list:

```powershell
$bt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0"
& "$bt\aapt.exe" dump badging app\build\outputs\apk\release\app-arm64-v8a-release.apk |
  Select-String "package:|application-label:|sdkVersion|targetSdkVersion|native-code"
& "$bt\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-arm64-v8a-release.apk
```

Smoke-test the release build on a physical device: launch the browser, resolve a video,
download one short file, pause/resume it, and confirm the exported file appears in
MediaStore. The release build is minified and obfuscated, so a debug-only test is not
enough.

## 4. Assemble release assets

```powershell
New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item app\build\outputs\apk\release\app-arm64-v8a-release.apk    dist\YouDrop-v0.01-arm64-v8a.apk
Copy-Item app\build\outputs\apk\release\app-armeabi-v7a-release.apk dist\YouDrop-v0.01-armeabi-v7a.apk
Copy-Item app\build\outputs\apk\release\app-x86_64-release.apk      dist\YouDrop-v0.01-x86_64.apk
Copy-Item app\build\outputs\apk\release\app-universal-release.apk   dist\YouDrop-v0.01-universal.apk

Get-ChildItem dist\*.apk | ForEach-Object {
  "$((Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower())  $($_.Name)"
} | Set-Content dist\SHA256SUMS.txt -Encoding ascii
```

Copy the license and notices alongside the APKs:

```powershell
Copy-Item LICENSE, THIRD_PARTY_NOTICES.md dist\
```

## 5. Commit and tag

```powershell
git add README.md RELEASE_NOTES_v0.01.md LICENSE THIRD_PARTY_NOTICES.md `
  RELEASING.md keystore.properties.example assets/ app/ AUDIT_LOG.md .gitignore
git commit -m "Release YouDrop v0.01"
git push origin main

git tag -a v0.01 -m "YouDrop v0.01"
git push origin v0.01
```

Never add `keystore.properties`, `*.jks`, APKs, or `dist/` to the commit.

## 6. Publish the GitHub release

```powershell
gh release create v0.01 `
  --title "YouDrop v0.01 — First Public Release" `
  --notes-file RELEASE_NOTES_v0.01.md `
  dist/YouDrop-v0.01-arm64-v8a.apk `
  dist/YouDrop-v0.01-armeabi-v7a.apk `
  dist/YouDrop-v0.01-x86_64.apk `
  dist/YouDrop-v0.01-universal.apk `
  dist/SHA256SUMS.txt `
  dist/LICENSE `
  dist/THIRD_PARTY_NOTICES.md
```

After publishing, check that every asset is downloadable, the checksums match, and the
README download links resolve.
