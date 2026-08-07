#!/usr/bin/env python3
# Copyright (C) 2026 The media3-decoder-ape Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Generates the legacy 3.95-3.97 corpus slice with the archival encoders.

The 294912-blocks-per-frame branch can only be produced by the era encoders
(MAC 3.95a1 through 3.97); the modern console tool always writes 3.99. The
encoders are Windows binaries from the Wayback Machine snapshots of
monkeysaudio.com/files/ (2002) and are not part of this repository; point
--encoders-dir at a directory containing MAC_395a1_F/app/MAC.exe,
MAC_396F/app/MAC.exe and MAC_397F/app/MAC.exe trees.

Every output is round-trip verified with the MODERN console decoder, so the
manifest MD5s are trustworthy without trusting the archival binaries.

Usage:
  python generate_legacy_corpus.py <reference.wav> <out_dir> --encoders-dir DIR
                                   [--mac PATH] [--ref-pcm NAME]
                                   [--encoders 395a1,396,397] [--levels fast,normal,...]
  python generate_legacy_corpus.py <reference.wav> <out_dir> --from-dir DIR
                                   [--mac PATH]

Appends rows to <out_dir>/manifest.csv. With --ref-pcm NAME, rows whose PCM
matches the reference are marked with that reference file (which must already
exist in out_dir, produced by generate_corpus.py --ref-pcm).

--from-dir ingests gen-<label>-<level>.ape files that were already encoded
elsewhere (e.g. inside Windows Sandbox, where untrusted archival binaries are
run) from DIR instead of running the encoders here. Each file still goes
through the same round-trip verification against <reference.wav> with the
modern decoder before it is admitted, so the trust story is unchanged.
"""

import glob
import os
import shutil
import sys

from generate_corpus import find_mac, run, total_blocks, wav_data_md5

ENCODERS = [
    ("395a1", os.path.join("MAC_395a1_F", "app", "MAC.exe")),
    ("396", os.path.join("MAC_396F", "app", "MAC.exe")),
    ("397", os.path.join("MAC_397F", "app", "MAC.exe")),
]

LEVELS = [(1000, "fast"), (2000, "normal"), (3000, "high"), (4000, "extrahigh")]


def take_option(args, name):
    if name not in args:
        return None
    i = args.index(name)
    args.pop(i)
    return args.pop(i)


def verify_and_manifest(modern_mac, ape_path, source_md5, source_bytes, blocks,
                        ref_pcm_name, rows):
    name = os.path.basename(ape_path)
    check_wav = ape_path + ".check.wav"
    run([modern_mac, ape_path, check_wav, "-d"])
    check_md5, check_bytes = wav_data_md5(check_wav)
    os.remove(check_wav)
    if check_md5 != source_md5 or check_bytes != source_bytes:
        raise RuntimeError("round-trip mismatch for " + name)
    rows.append("%s,%s,%d,%s" % (name, source_md5, blocks, ref_pcm_name or ""))
    print("OK  %-28s %s blocks=%d" % (name, source_md5, blocks))


def main():
    args = sys.argv[1:]
    encoders_dir = take_option(args, "--encoders-dir")
    from_dir = take_option(args, "--from-dir")
    modern_mac = find_mac(take_option(args, "--mac"))
    ref_pcm_name = take_option(args, "--ref-pcm")
    only_encoders = take_option(args, "--encoders")
    only_levels = take_option(args, "--levels")
    if not encoders_dir and not from_dir:
        sys.exit("--encoders-dir or --from-dir is required (see the module docstring)")
    ref_wav, out_dir = args[0], args[1]

    if from_dir:
        source_md5, source_bytes = wav_data_md5(ref_wav)
        blocks = total_blocks(source_bytes, channels=2, bits=16)
        rows = []
        pattern = os.path.join(from_dir, "gen-*.ape")
        for src in sorted(glob.glob(pattern)):
            dst = os.path.join(out_dir, os.path.basename(src))
            shutil.copyfile(src, dst)
            verify_and_manifest(modern_mac, dst, source_md5, source_bytes,
                                blocks, ref_pcm_name, rows)
        if not rows:
            sys.exit("no gen-*.ape files in " + from_dir)
        manifest = os.path.join(out_dir, "manifest.csv")
        with open(manifest, "a") as f:
            for row in rows:
                f.write(row + "\n")
        print("appended %d rows to %s" % (len(rows), manifest))
        return

    encoders = [e for e in ENCODERS
                if only_encoders is None or e[0] in only_encoders.split(",")]
    levels = [l for l in LEVELS
              if only_levels is None or l[1] in only_levels.split(",")]

    source_md5, source_bytes = wav_data_md5(ref_wav)
    blocks = total_blocks(source_bytes, channels=2, bits=16)

    rows = []
    for label, rel_path in encoders:
        encoder = os.path.join(encoders_dir, rel_path)
        if not os.path.exists(encoder):
            sys.exit("encoder not found: " + encoder)
        for level, level_name in levels:
            name = "gen-%s-%s.ape" % (label, level_name)
            ape_path = os.path.join(out_dir, name)
            run([encoder, ref_wav, ape_path, "-c%d" % level])

            check_wav = ape_path + ".check.wav"
            run([modern_mac, ape_path, check_wav, "-d"])
            check_md5, check_bytes = wav_data_md5(check_wav)
            os.remove(check_wav)
            if check_md5 != source_md5 or check_bytes != source_bytes:
                raise RuntimeError("round-trip mismatch for " + name)

            ref = ref_pcm_name if ref_pcm_name else ""
            rows.append("%s,%s,%d,%s" % (name, source_md5, blocks, ref))
            print("OK  %-28s %s blocks=%d" % (name, source_md5, blocks))

    manifest = os.path.join(out_dir, "manifest.csv")
    with open(manifest, "a") as f:
        for row in rows:
            f.write(row + "\n")
    print("appended %d rows to %s" % (len(rows), manifest))


if __name__ == "__main__":
    main()
