# media3-decoder-ape

**Monkey's Audio (APE) playback for AndroidX Media3, powered by the official MACLib decoder.**

[![CI](https://github.com/eugenedibtsev/media3-decoder-ape/actions/workflows/ci.yml/badge.svg)](https://github.com/eugenedibtsev/media3-decoder-ape/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.eugenedibtsev/media3-decoder-ape)](https://central.sonatype.com/artifact/io.github.eugenedibtsev/media3-decoder-ape)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

[Monkey's Audio][] is a lossless audio format that [AndroidX Media3][] cannot
play out of the box: the platform has no APE codec, and Media3 ships no
extractor for the container. This module adds both: an `Extractor` that
parses the container and decodes to raw PCM inside the extractor, the same
architecture as Media3's own native `FlacExtractor`, so the standard audio
pipeline plays the output and no custom `Renderer` is needed.

The decoder is the **official MACLib** from monkeysaudio.com, not an
independent re-implementation, so format coverage and correctness track the
reference implementation.

[Monkey's Audio]: https://monkeysaudio.com
[AndroidX Media3]: https://github.com/androidx/media

## Quick start

```gradle
dependencies {
    implementation 'io.github.eugenedibtsev:media3-decoder-ape:1.0.0'
}
```

**Requires AndroidX Media3 1.10.1 or newer**, the version this module is
built and tested against. The dependency is declared `api`, so Gradle
raises an older Media3 in your build rather than letting it fail at
runtime.

Inside an `Activity` (or with any `Context` in place of `this`):

```java
ExoPlayer player = new ExoPlayer.Builder(this)
    .setMediaSourceFactory(
        new DefaultMediaSourceFactory(this, ApeSupport.extractorsFactory()))
    .build();
```

`ApeSupport.extractorsFactory()` is a `DefaultExtractorsFactory` with the
APE extractor in front; `ApeSupport.extractorsFactory(base)` wraps a factory
you build yourself the same way. The renderers are the standard Media3 ones:
decoding happens inside the extractor, which emits raw PCM.

The [sample/](sample/) module is this quick start as a runnable app and a
minimal APE viewer: open an `.ape` file from a file manager (or pick one
with the in-app button) and it shows the container version, compression
level, format and average bitrate, then plays.

### .NET for Android / Xamarin

Bind **only `ApeSupport`**: its signatures use nothing but Media3 types,
which come from the `Xamarin.AndroidX.Media3.*` packages. There is no
renderer to bind: decoding happens inside the extractor, so the one bound
call (`ApeSupport.ExtractorsFactory()`) is the entire integration. The AAR
ships consumer R8 rules that keep the public API by name, which the .NET
toolchain's R8 pass relies on.

## Why not the FFmpeg route?

Media3's only other path to APE is `lib-decoder-ffmpeg`:

|                | FFmpeg route                          | this module                |
|----------------|---------------------------------------|----------------------------|
| Implementation | independent GPL decoder (from Rockbox) | official MACLib            |
| License        | LGPL/GPL                              | BSD-3 + Apache-2.0         |
| Channels       | mono/stereo only                      | everything MACLib supports |
| Bit depth      | up to 24-bit                          | including 32-bit float     |

## Supported formats

* **Compression levels:** Fast, Normal, High, Extra High, Insane.
* **File versions:** legacy layout from 2.50 up to 3.97, descriptor layout
  3.98+ (both verified bit-exact against reference PCM on a multi-version
  corpus).
* **Bit depths:** 8, 16, 24, 32 (including 32-bit float).
* **Channels:** mono, stereo, multichannel (up to MACLib's limit of 32).
* **Sample rates:** anything the container declares (44.1–192 kHz tested).
* **Tags:** APEv2 (including embedded cover art) and ID3v1, exposed as
  Media3 `Metadata` (`VorbisComment` / `PictureFrame` entries); ID3v2 data
  preceding the container is skipped and parsed.
* **Seeking:** sample-accurate, driven by the container seek table; MACLib
  positions within a frame by decode-and-discard.

## Requirements and compatibility

* **minSdk 23**, compiled against **Media3 1.10.1** (declared as an `api`
  dependency, so your app's Media3 version wins as long as it is
  binary-compatible). **Tested against 1.10.1** with the full
  instrumentation suite on a physical arm64 device and an x86_64 emulator;
  the module also compiles against 1.11.0. It deliberately avoids Media3
  helper APIs whose `@UnstableApi` signatures churn between releases, so a
  Media3 upgrade in your app should not require an upgrade here.
* **ABIs:** `arm64-v8a`, `armeabi-v7a`, `x86_64`. NEON and SSE/AVX kernels
  are compiled in and selected at runtime by MACLib.
* **16 KB page sizes supported**: the native libraries are linked with 16 KB
  segment alignment, so apps shipping this module satisfy the Google Play
  requirement for targetSdk 35+ and load cleanly on Android 15+ devices with
  16 KB pages.
* **NDK pinned** to 27.0.12077973 so third-party builds reproduce the `.so`
  the test matrix ran against.
* The public API is five classes (`ApeSupport`, `ApeExtractor`,
  `ApeStreamMetadata`, `ApeDecoderException`, `ApeLibrary`) and is
  annotated `@UnstableApi`,
  matching the Media3 extension convention: if your app opts into Media3's
  unstable API lint check, opt in for these too.
* **What we promise:** the public API surface is snapshotted in
  [api/](media3-decoder-ape/api/) and enforced by the `apiCheck` Gradle task;
  breaking changes bump the major version. `@UnstableApi` refers to the
  Media3 convention, not to an intent to break things.

## Known limitations

1. **Seek and start latency scales with frame size.** APE frames must be
   decoded from the frame start to the target block. Measured on a Galaxy
   A33 (release build, the synthetic reference track from the Performance
   section, p50 over 60 random seeks; direction makes no difference):
   3.99 Fast **26 ms**; 3.95–3.97 Fast **~95 ms**, their Extra High
   **~434 ms**; 3.99 **Insane ~2.8 s**, because the whole frame has to come
   out before the first block is deliverable. This is a property of the
   format (Insane targets archival density with frames sixteen times the
   base size), identical for every MACLib-based player.
2. **Memory scales with frame size too.** MACLib buffers whole frames on the
   native heap: `blocksPerFrame × channels × bytesPerSample` per buffer,
   where `blocksPerFrame` is 73,728 (Fast/Normal/High), ×4 (Extra High,
   and all 3.95–3.97 files), or ×16 (Insane). A 16-bit stereo Insane file
   holds ~4.5 MB per frame buffer; 32-bit float stereo doubles that. Plain
   16-bit stereo at Normal is ~0.3 MB and unremarkable.
3. **No unknown-length sources.** APE stores its tags at the end of the file
   and MACLib needs the total size to locate the last frame, so progressive
   or chunked HTTP streams without a `Content-Length` are rejected with an
   unsupported-feature error. This is not a streaming format.
4. **Devices without a supported ABI** still *sniff* APE files successfully
   (container parsing is pure Java), but playback fails with a
   `ParserException` naming the device's ABIs. Call
   `ApeLibrary.isAvailable()` up front if you need to know in advance.
5. **Files without a seek table cannot be decoded**, by this or any other
   implementation: APE frames carry no sync pattern, so nothing can locate
   frames without the table. Truncated or non-monotonic tables fail with a
   clean error.
6. **`.apl` (APE link) files are out of scope** for v1.

## Performance

Realtime factor (decode time / audio duration; lower is better) measured on
a Samsung Galaxy A33 5G (arm64, Android 16), single decoding thread, every
run verified bit-exact against the reference PCM MD5:

| Level      | 3.41   | 3.94b1 | 3.97   | 3.99   |
|------------|--------|--------|--------|--------|
| Fast       | 0.0037 | 0.0050 | 0.0073 | 0.0081 |
| Normal     | 0.0043 | 0.0085 | 0.0097 | 0.0102 |
| High       | 0.0055 | 0.0123 | 0.0133 | 0.0111 |
| Extra High | 0.0093 | 0.0313 | 0.0331 | 0.0186 |
| Insane     | —      | —      | —      | 0.0537 |

Other measured points (3.99, Normal): 24-bit 0.0106, 32-bit float 0.0122,
mono 0.0051, 5.1 0.0266, 96 kHz 0.0201, 192 kHz 0.0385.

The reference track is synthetic: four minutes of 44.1 kHz/16-bit stereo
from a single ffmpeg expression, compressing to 59–63%, the same class as a
commercial recording. To reproduce the measurement, generate it with:

```shell
ffmpeg -y -f lavfi -i "aevalsrc=sin(2*PI*(200+400*(t-4*floor(t/4)))*(t-4*floor(t/4)))*0.6+(random(0)-0.5)*0.12|cos(2*PI*(320+250*(t-4*floor(t/4)))*(t-4*floor(t/4)))*0.6+(random(1)-0.5)*0.12:s=44100:d=240" -c:a pcm_s16le ref.wav
```

(The sweep restarts every four seconds to stay band-limited; a monotonic
sweep would alias into noise within seconds.)

From 3.98 onwards MACLib runs its neural filters through NEON, and the
benefit grows with filter order: against 3.97 the current format decodes
1.2x faster at High and 1.8x faster at Extra High. At Fast and Normal there
is no filter worth vectorising and 3.99 is marginally slower instead. Older
containers are cheaper across the board: 3.41 is the fastest at every
level, 2.0–2.4x quicker than 3.99.

> These numbers require an optimized native build. Android Gradle configures
> CMake as `Debug` (no `-O` flag at all) for debuggable variants, which costs
> roughly 9x overall and up to 26x on the NEON paths, because unoptimized
> intrinsics spill every vector to the stack. The module therefore sets
> `testBuildType 'release'` so instrumentation measurements reflect what
> actually ships.

### MACLib worker scaling (3.98+ format)

MACLib can decode independent frames on worker threads. Realtime factors for
the current-format files on the same device (audio thread priority pinned,
every run verified bit-exact against the reference PCM MD5):

| Level      | 1 worker | 2 workers | 3 workers | 4 workers | speedup at 4 |
|------------|----------|-----------|-----------|-----------|--------------|
| Fast       | 0.0075   | 0.0041    | 0.0043    | 0.0038    | 2.0x         |
| Normal     | 0.0097   | 0.0051    | 0.0050    | 0.0049    | 2.0x         |
| High       | 0.0107   | 0.0056    | 0.0057    | 0.0053    | 2.0x         |
| Extra High | 0.0182   | 0.0093    | 0.0093    | 0.0087    | 2.1x         |
| Insane     | 0.0533   | 0.0294    | 0.0270    | 0.0283    | 1.9x         |

On this device the second worker roughly halves decode time (two big cores)
and the third and fourth add nothing measurable, so scaling saturates at
about 2x; a CPU with more uniform cores would saturate later. **The
extractor uses a single worker at every level**: even the worst case,
Insane, decodes 19x faster than realtime on one thread, so parallelism buys
nothing for playback and only costs battery.

Insane still claims audio thread priority as a safety margin: its frames are
by far the longest, so a scheduling stall there is expensive.

Desktop reference for the same track (i5-13420H, MAC 13.20 console, best of
three): Fast 0.59 s, High 0.85 s, Extra High 1.20 s, Insane 2.22 s total
decode time.

## Building

```shell
cd media3-decoder-ape/src/main/jni && ./download_maclib.sh
./gradlew :media3-decoder-ape:assembleRelease
```

The MACLib sources are downloaded, checksum-verified and never vendored.
Full instructions, prerequisites (NDK, CMake) and troubleshooting:
[docs/BUILDING.md](docs/BUILDING.md).

## Licenses

* This module: [Apache License 2.0](LICENSE), like Media3 itself.
* Monkey's Audio (MACLib): 3-clause BSD since version 10.18. The full text
  and the required attributions are in [NOTICE](NOTICE) and
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Acknowledgements

Monkey's Audio was created by Matthew T. Ashland. This project bundles the
official Monkey's Audio SDK without modifications; all decoding is his work,
and this library only provides the bridge between the SDK and AndroidX Media3.

Thanks to Matthew for reviewing this integration and giving his approval to
publish it.

Thanks to the AndroidX Media3 team, whose native FLAC extension provided
the architectural model for the decode-in-extractor approach used here.
