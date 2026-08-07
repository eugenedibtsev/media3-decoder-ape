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
"""Generates the header/descriptor fuzz corpus (TZ section 5.4).

Takes the first 64 KB of donor files from both container generations and
applies deterministic pseudo-random mutations concentrated on the header
region: byte flips, field overwrites with boundary values, truncations.
The instrumentation fuzz test feeds every file to the extractor and asserts
the process survives (any clean exception is a pass).

Donors should cover both container generations (a legacy header and a 3.98+
descriptor file); any corpus files work, e.g. one gen-397-* and one gen-1399-*.

Usage: python generate_fuzz.py <out_dir> <count_per_donor> <donor.ape> [donor.ape...]
"""

import os
import random
import struct
import sys

HEAD_BYTES = 65536
BOUNDARY_U32 = [0, 1, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0xFFFFFFF0, 294912, 73728]


def mutate(data, rng):
    data = bytearray(data)
    mode = rng.randrange(4)
    if mode == 0:
        # random byte flips in the header region
        for _ in range(rng.randrange(1, 16)):
            data[rng.randrange(min(len(data), 4096))] ^= rng.randrange(1, 256)
    elif mode == 1:
        # overwrite an aligned 32-bit field with a boundary value
        for _ in range(rng.randrange(1, 5)):
            offset = rng.randrange(0, 96, 4)
            struct.pack_into("<I", data, offset, rng.choice(BOUNDARY_U32))
    elif mode == 2:
        # truncate somewhere inside the header/tables region
        cut = rng.randrange(1, min(len(data), 8192))
        del data[cut:]
    else:
        # random garbage block over the seek table area
        start = rng.randrange(32, 2048)
        for i in range(start, min(len(data), start + rng.randrange(16, 512))):
            data[i] = rng.randrange(256)
    return bytes(data)


def main():
    out_dir, count = sys.argv[1], int(sys.argv[2])
    donors = sys.argv[3:]
    if not donors:
        sys.exit("pass at least one donor .ape file")
    os.makedirs(out_dir, exist_ok=True)
    rng = random.Random(20260731)
    n = 0
    for donor in donors:
        with open(donor, "rb") as f:
            head = f.read(HEAD_BYTES)
        for _ in range(count):
            with open(os.path.join(out_dir, "fuzz-%04d.ape" % n), "wb") as f:
                f.write(mutate(head, rng))
            n += 1
    print("fuzz corpus: %d files in %s" % (n, out_dir))


if __name__ == "__main__":
    main()
