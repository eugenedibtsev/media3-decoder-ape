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
"""Builds the committed CI corpus (the androidTest assets).

Everything is synthetic (ffmpeg-generated sweeps), so the corpus is fully
redistributable. Layout of the result:

  <out_dir>/manifest.csv, broken.txt   consumed by the instrumentation tests
  <out_dir>/gen-1399-*.ape             modern matrix, 2-second program
  <out_dir>/gen-1399-normal-44k-16b-2ch.ape
  <out_dir>/gen-397-normal.ape         16-second program: >2 frames of the
                                       294912 branch, so frame-crossing decode
                                       and the seek tests are meaningful
  <out_dir>/gen-395a1-*, gen-396-*, gen-397-{fast,extrahigh}.ape (2-second)
  <out_dir>/ci_ref.pcm                 reference PCM for the two 16-second files
  <out_dir>/fuzz/                      deterministic header mutations

The legacy slice needs the archival 3.95a1-3.97 Windows encoders
(--encoders-dir); without it the slice is skipped and CI covers 3.98+ only.

Usage:
  python generate_ci_corpus.py <out_dir> [--mac PATH] [--encoders-dir DIR]
"""

import os
import shutil
import struct
import subprocess
import sys

import generate_corpus
from generate_corpus import Corpus, find_mac, run, total_blocks, wav_data_md5

TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))
REF_PCM = "ci_ref.pcm"


def synth(out_path, seconds):
    expr = ("sin(2*PI*(200+400*t)*t)*0.6+(random(0)-0.5)*0.12|"
            "cos(2*PI*(320+250*t)*t)*0.6+(random(1)-0.5)*0.12")
    run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i",
         "aevalsrc=%s:s=44100:d=%d" % (expr, seconds), "-c:a", "pcm_s16le", out_path])


def make_id3v2(title, artist):
    """Builds a minimal but valid ID3v2.3 tag with two text frames."""

    def text_frame(frame_id, value):
        # ISO-8859-1 encoding byte, then the text; ID3v2.3 frame sizes are
        # plain big-endian (synchsafe sizes only arrived in 2.4).
        data = b"\x00" + value.encode("latin-1")
        return frame_id.encode("ascii") + struct.pack(">IH", len(data), 0) + data

    frames = text_frame("TIT2", title) + text_frame("TPE1", artist)
    size = len(frames)
    # The header size field is synchsafe: seven bits per byte.
    synchsafe = bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F,
                       (size >> 7) & 0x7F, size & 0x7F])
    return b"ID3" + b"\x03\x00" + b"\x00" + synchsafe + frames


def extract_data_chunk(wav_path, pcm_path):
    with open(wav_path, "rb") as f:
        data = f.read()
    offset = 12
    while offset < len(data):
        chunk_id = data[offset:offset + 4]
        size = struct.unpack("<I", data[offset + 4:offset + 8])[0]
        if chunk_id == b"data":
            with open(pcm_path, "wb") as f:
                f.write(data[offset + 8:offset + 8 + size])
            return
        offset += 8 + size + (size & 1)
    raise RuntimeError("no data chunk in " + wav_path)


def main():
    args = sys.argv[1:]

    def take_option(name):
        if name not in args:
            return None
        i = args.index(name)
        args.pop(i)
        return args.pop(i)

    mac = find_mac(take_option("--mac"))
    encoders_dir = take_option("--encoders-dir")
    out_dir = args[0]
    os.makedirs(out_dir, exist_ok=True)
    tmp = os.path.join(out_dir, "_ci_tmp")
    os.makedirs(tmp, exist_ok=True)

    short_wav = os.path.join(tmp, "ref_short.wav")
    long_wav = os.path.join(tmp, "ref_long.wav")
    synth(short_wav, 2)
    synth(long_wav, 16)

    # Modern matrix over the 2-second program (writes manifest.csv, broken.txt).
    run([sys.executable, os.path.join(TOOLS_DIR, "generate_corpus.py"),
         "--mac", mac, short_wav, out_dir])

    # 16-second files: more than two 294912-block frames, one per generation.
    generate_corpus.MAC = mac
    corpus = Corpus(out_dir)
    corpus.compress_and_verify(long_wav, "gen-1399-normal-44k-16b-2ch.ape", 2000, 2, 16,
                               ref_name=REF_PCM)
    extract_data_chunk(long_wav, os.path.join(out_dir, REF_PCM))

    # Same audio behind a leading ID3v2 tag: exercises tag skipping and, since
    # every container offset shifts by the tag size, seeking with a non-zero
    # junk prefix. Decoded PCM is identical, so the manifest row is a copy.
    base_name, base_md5, base_blocks, base_ref = corpus.manifest[-1]
    prefixed_name = "gen-1399-normal-id3v2-44k-16b-2ch.ape"
    with open(os.path.join(out_dir, base_name), "rb") as f:
        base_bytes = f.read()
    with open(os.path.join(out_dir, prefixed_name), "wb") as f:
        f.write(make_id3v2(u"Reference Track", u"Test Artist") + base_bytes)
    corpus.manifest.append((prefixed_name, base_md5, base_blocks, base_ref))

    with open(os.path.join(out_dir, "manifest.csv"), "a") as f:
        for name, md5, blocks, ref in corpus.manifest:
            f.write("%s,%s,%d,%s\n" % (name, md5, blocks, ref))

    # Legacy slice via the archival encoders, when available. Every level the
    # era encoders offer, so the shipped corpus covers the whole version x
    # level matrix rather than just its corners. Insane (5000) arrived with
    # 3.99 and is produced by the modern encoder above, not here.
    if encoders_dir:
        run([sys.executable, os.path.join(TOOLS_DIR, "generate_legacy_corpus.py"),
             "--mac", mac, "--encoders-dir", encoders_dir,
             "--levels", "fast,normal,high,extrahigh", short_wav, out_dir])
        run([sys.executable, os.path.join(TOOLS_DIR, "generate_legacy_corpus.py"),
             "--mac", mac, "--encoders-dir", encoders_dir,
             "--encoders", "397", "--levels", "normal", "--ref-pcm", REF_PCM,
             long_wav, out_dir])
    else:
        print("NOTE: --encoders-dir not given, the legacy 3.95-3.97 slice is skipped")

    # Deterministic fuzz seeds over both container generations.
    donors = [os.path.join(out_dir, "gen-1399-normal-44k-16b-2ch.ape"),
              os.path.join(out_dir, "gen-1399-fast-44k-16b-2ch.ape")]
    if encoders_dir:
        donors.append(os.path.join(out_dir, "gen-397-normal.ape"))
    run([sys.executable, os.path.join(TOOLS_DIR, "generate_fuzz.py"),
         os.path.join(out_dir, "fuzz"), "15"] + donors)

    shutil.rmtree(tmp)
    total = sum(os.path.getsize(os.path.join(root, f))
                for root, _, files in os.walk(out_dir) for f in files)
    print("CI corpus ready: %.1f MB in %s" % (total / 1e6, out_dir))


if __name__ == "__main__":
    main()
