# Third-party notices

This module is licensed under the [Apache License 2.0](LICENSE). When built
according to its instructions it incorporates the third-party components
below.

## Monkey's Audio (MACLib)

* **What:** the official Monkey's Audio SDK, version pinned in
  `media3-decoder-ape/src/main/jni/download_maclib.sh`; compiled into the
  native library `libapeJNI.so`.
* **Source:** https://monkeysaudio.com, downloaded at build time,
  SHA-256-verified, **not vendored** into this repository.
* **License:** Monkey's Audio License Agreement (3-clause BSD),
  copyright Matthew T. Ashland. Verbatim copy:
  [licenses/monkeys-audio-LICENSE.txt](licenses/monkeys-audio-LICENSE.txt).
* Clause 2 of the license requires binary distributions to reproduce the
  copyright notice and license text in accompanying materials: apps that
  ship this module should surface [NOTICE](NOTICE) (or equivalent) in their
  licenses screen.

## RSA MD5

* **What:** the MD5 implementation inside the Monkey's Audio SDK
  (`Source/MACLib/MD5.cpp`, `MD5.h`), identified as the "RSA Data Security,
  Inc. MD5 Message-Digest Algorithm".
* **Notice:** verbatim copy in
  [licenses/rsa-md5-NOTICE.txt](licenses/rsa-md5-NOTICE.txt).

## AndroidX Media3

* **What:** `androidx.media3:media3-extractor`, `media3-common` (declared
  `api` dependencies of the library; the sample additionally uses
  `media3-exoplayer` and `media3-ui`). Not redistributed by this repository;
  consumers pull them from Google's Maven repository.
* **License:** Apache License 2.0,
  https://github.com/androidx/media/blob/release/LICENSE.

## AndroidX Annotation

* **What:** `androidx.annotation:annotation` (`api` dependency;
  `@Nullable` appears in public signatures).
* **License:** Apache License 2.0.
