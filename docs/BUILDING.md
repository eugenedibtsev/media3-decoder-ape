# Building media3-decoder-ape

## Prerequisites

| Tool        | Version                | Notes                                              |
|-------------|------------------------|----------------------------------------------------|
| JDK         | 17+                    | what AGP 8.x requires                              |
| Android SDK | compileSdk 36          | install via SDK manager                            |
| Android NDK | **27.0.12077973**      | pinned in `build.gradle`; the SDK manager installs it side-by-side with other NDKs |
| CMake       | 3.21+                  | installable via the SDK manager                    |
| bash        | any                    | `download_maclib.sh` is a bash script; on Windows use **Git Bash** (ships `curl`, `sha256sum`, `unzip`) |

The NDK version is pinned deliberately: instrumentation measurements and the
bit-exactness matrix were produced with this exact toolchain, and third-party
builds should reproduce the same `.so`.

## 1. Fetch the MACLib sources

The Monkey's Audio SDK is BSD-3-licensed, downloaded on demand,
checksum-pinned and **never vendored** into this repository:

```shell
cd media3-decoder-ape/src/main/jni
./download_maclib.sh
```

The script downloads `MAC_1327_SDK.zip` from monkeysaudio.com, verifies its
SHA-256 against the value pinned in the script, and unpacks it into
`src/main/jni/maclib/`. Re-running is a no-op when the pinned version is
already present.

## 2. Build

```shell
./gradlew :media3-decoder-ape:assembleRelease   # the AAR
./gradlew :sample:assembleDebug                 # the sample app
./gradlew :media3-decoder-ape:apiCheck          # public API surface gate
```

The AAR lands in `media3-decoder-ape/build/outputs/aar/`. Native code is
built for `arm64-v8a`, `armeabi-v7a` and `x86_64`.

### 16 KB page size

The native libraries must be linked with 16 KB segment alignment: Android
15+ devices with 16 KB pages otherwise fall back to a warned compatibility
mode, and Google Play requires 16 KB support for apps targeting SDK 35 and
above. NDK r27 still defaults to 4 KB, so `CMakeLists.txt` passes
`-Wl,-z,max-page-size=16384` explicitly.

CI enforces this on the assembled AAR, and you can run the same check
locally:

```shell
unzip -q media3-decoder-ape/build/outputs/aar/media3-decoder-ape-release.aar -d /tmp/aar
./media3-decoder-ape/tools/check_so_alignment.sh /tmp/aar
```

It reports `p_align` per ABI and exits non-zero below 16 KB on the 64-bit
ABIs (32-bit ARM has no 16 KB variant, so it is reported but not enforced).

### Building against a different Media3

```shell
./gradlew :media3-decoder-ape:assembleRelease -Pmedia3Override=1.11.0
```

This forces every `androidx.media3` artifact to the given version without
touching the declared dependency, which is how the module is checked
against Media3 releases newer than the one it compiles against.

## 3. Instrumentation tests

```shell
./gradlew :media3-decoder-ape:assembleAndroidTest
adb install -r media3-decoder-ape/build/outputs/apk/androidTest/release/media3-decoder-ape-release-androidTest.apk
adb shell am instrument -w \
    io.github.eugenedibtsev.media3.ape.test/androidx.test.runner.AndroidJUnitRunner
```

Notes:

* Tests run against the **release** variant (`testBuildType 'release'`):
  debuggable variants get the native code compiled at `-O0`, which distorts
  every performance number roughly ninefold.
* The corpus ships inside the androidTest APK assets (fully synthetic,
  ~27 MB); no device setup is needed, and nothing on the device is read
  unless you pass `-e corpusDir <path>`. What it contains, how it is
  generated and how to run against material of your own: [CORPUS.md](CORPUS.md).
* The measurement harnesses (`ApeSeekLatencyTest`, `ApeWorkerPerfTest`) skip
  themselves unless `-e manual true` is passed, so the default run is
  correctness only.

## Typical errors

* **`MACLib sources not found` / CMake configure fails**: step 1 was
  skipped. Gradle deliberately skips native configuration while
  `src/main/jni/maclib/` is absent so that project sync works before the
  download; the AAR built in that state contains no native library.
* **`Checksum mismatch, aborting.`**: the downloaded archive does not match
  the pinned SHA-256. Do not build against it; re-run (a truncated download
  is the common cause) and open an issue if it persists, since upstream may
  have republished the file.
* **`NDK not configured` / `No version of NDK matched`**: install exactly
  27.0.12077973 via the SDK manager (`sdkmanager "ndk;27.0.12077973"`).
* **`sha256sum: command not found` on Windows**: the script was run from
  cmd/PowerShell. Run it from Git Bash.
* **Instrumentation reports 0 tests over Wi-Fi ADB on Windows**: a known
  Gradle/UTP issue: the profile file name derives from the serial and the
  colon in `IP:5555` is invalid in a Windows path. Use the manual
  `am instrument` flow above (or a USB connection) instead of
  `connectedAndroidTest`.
