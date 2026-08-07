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
"""Merges corpus directories into one device-corpus manifest.

Each input directory must contain a manifest.csv (as written by
generate_corpus.py / generate_legacy_corpus.py) and may contain a broken.txt.
The merged manifest.csv and broken.txt are written to the output directory;
push_corpus.py consumes them.

Usage: python build_device_manifest.py <out_dir> <corpus_dir> [corpus_dir...]
"""

import os
import sys


def read_lines(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return [line.strip() for line in f if line.strip() and not line.startswith("#")]


def main():
    out_dir = sys.argv[1]
    corpus_dirs = sys.argv[2:]
    if not corpus_dirs:
        sys.exit("pass at least one corpus directory")
    os.makedirs(out_dir, exist_ok=True)

    manifest, broken, seen = [], [], set()
    for corpus_dir in corpus_dirs:
        for row in read_lines(os.path.join(corpus_dir, "manifest.csv")):
            name = row.split(",", 1)[0]
            if name in seen:
                sys.exit("duplicate corpus entry: " + name)
            seen.add(name)
            manifest.append(row)
        broken.extend(read_lines(os.path.join(corpus_dir, "broken.txt")))

    with open(os.path.join(out_dir, "manifest.csv"), "w") as f:
        f.write("# filename,expected_pcm_md5,expected_blocks,reference_pcm\n")
        f.write("\n".join(manifest) + "\n")
    with open(os.path.join(out_dir, "broken.txt"), "w") as f:
        f.write("\n".join(broken) + ("\n" if broken else ""))
    print("merged %d entries (%d broken) into %s" % (len(manifest), len(broken), out_dir))


if __name__ == "__main__":
    main()
