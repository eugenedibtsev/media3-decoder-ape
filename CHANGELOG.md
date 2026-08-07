# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-08-08

Initial public release.

* `ApeSupport`: the one-call entry point. `ApeSupport.extractorsFactory()`
  returns an `ExtractorsFactory` with APE in front of the Media3 defaults
  (an overload wraps any base factory). Only Media3 types in its signatures,
  so language bindings such as .NET for Android need to bind a single class.
* `ApeExtractor`: a Media3 `Extractor` for Monkey's Audio files that decodes
  to PCM inside the extractor via the official MACLib 13.20; no custom
  renderer needed.
* Container support: legacy layout 2.50–3.97 and descriptor layout 3.98+;
  8/16/24-bit and 32-bit float; mono to multichannel; all compression
  levels including Insane.
* Sample-accurate seeking driven by the container seek table.
* APEv2 (including cover art) and ID3v1 tags exposed as Media3 `Metadata`;
  leading ID3v2 skipped and parsed.
* `ApeStreamMetadata`: parsed stream properties, including per-frame
  compressed sizes (`getFrameCount`/`getFrameCompressedBytes`) for cheap
  loudness envelopes.
* Robustness: transient IO failures during load are retried with a decoder
  rebuild (works around a MACLib worker-pipeline desync, reported upstream);
  truncated or corrupt files fail with clean errors.
* ABIs: arm64-v8a, armeabi-v7a, x86_64, linked with 16 KB segment alignment
  for Android 15+ devices with 16 KB pages; CI fails the build if that
  alignment regresses. NDK pinned to 27.0.12077973.
* Tested against Media3 1.10.1; also compiles against 1.11.0. ID3v2
  framing is parsed in-module rather
  than through Media3's `FlacMetadataReader`, whose signatures changed in
  1.11.0; a consumer upgrading Media3 would otherwise have hit a
  `NoSuchMethodError`.
* Fully synthetic, self-verifying instrumentation corpus covering container
  generations 3.41–3.99.
