# Contributing

Issues and pull requests are welcome.

## Before you start

* Build instructions live in [docs/BUILDING.md](docs/BUILDING.md); in
  particular, run `download_maclib.sh` once before the first build.
* The MACLib SDK is **never vendored or patched**: this repository contains
  only the JNI bridge, the extractor and tests. If you believe the defect is
  inside MACLib, please open an issue describing it. Upstream problems are
  reported to the Monkey's Audio author, not patched locally.

## Pull requests

* `./gradlew :media3-decoder-ape:assembleRelease :media3-decoder-ape:apiCheck`
  must pass. If you intentionally change the public API, run `apiDump`,
  commit the snapshot diff, and say so in the PR description.
* Instrumentation tests (`docs/BUILDING.md`, section 3) must pass on at
  least one device or emulator; state which one in the PR.
* Test corpus additions must be **synthetic and self-verifying** (generated
  by the scripts under `media3-decoder-ape/tools/`, round-trip verified
  against the modern decoder). Real-world recordings cannot be committed,
  regardless of how freely they seem to be licensed.
* Match the style of the surrounding code; Java sources follow the Media3
  conventions (2-space indent, `@UnstableApi` on public classes).

## Reporting decode problems

The most useful report for a file that misplays: MAC version that produced
it, compression level, and the output of the official `mac.exe <file> -v`
verify command. If the official tool rejects the file too, it is not a bug
in this module, but it may still be worth documenting.
