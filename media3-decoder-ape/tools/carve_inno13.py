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
"""Carves zlib file streams out of an Inno Setup 1.3.x installer.

This is how the MAC 3.80 encoder entered the corpus: its installer is ISX
1.3.16, whose modified setup headers innoextract rejects, but the Inno 1.3
payload behind them is individual zlib streams that need no header metadata
at all. The script walks the setup-1 region, inflates every stream it can,
and writes the results out for identification — filenames are lost, so
identify PEs by their export table. Nothing from the installer is executed.

The recovered encoder was accepted only after it reproduced the corpus's
existing 3.80 containers byte for byte; see docs/CORPUS.md.

Usage:
  python carve_inno13.py <installer.exe> <out_dir>
"""

import os
import struct
import sys
import zlib

def main():
    src, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    data = open(src, "rb").read()
    size = len(data)

    # TSetupLdrOffsetTable, last 44 bytes: ID[12], TotalSize, OffsetEXE,
    # CompressedSizeEXE, UncompressedSizeEXE, AdlerEXE, OffsetMsg, Offset0, Offset1.
    table = data[size - 44:]
    table_id = table[:12].rstrip(b"\x00")
    (total_size, offset_exe, csize_exe, usize_exe, adler_exe,
     offset_msg, offset0, offset1) = struct.unpack("<8i", table[12:])
    print("table id %r  total=%d  offset0=%d  offset1=%d  (file %d)"
          % (table_id, total_size, offset0, offset1, size))
    if not (0 < offset1 < size):
        sys.exit("offset1 out of range; not an Inno 1.3 layout")

    # setup-1 region: individual zlib streams back to back, from offset1 to the
    # offset table. Walk it: try to inflate at the cursor; on success jump past
    # the consumed bytes, otherwise advance one byte.
    region_end = size - 44
    pos = offset1
    found = 0
    while pos < region_end - 2:
        # zlib CMF/FLG: 0x78 with a valid check byte ((CMF<<8|FLG) % 31 == 0).
        if data[pos] == 0x78 and ((data[pos] << 8) | data[pos + 1]) % 31 == 0:
            d = zlib.decompressobj()
            try:
                blob = d.decompress(data[pos:region_end])
                if d.eof and len(blob) > 64:
                    consumed = (region_end - pos) - len(d.unused_data)
                    kind = "PE" if blob[:2] == b"MZ" else blob[:4].hex()
                    name = "stream_%08d_%s.bin" % (pos, "pe" if blob[:2] == b"MZ" else "dat")
                    with open(os.path.join(out_dir, name), "wb") as f:
                        f.write(blob)
                    print("ok  at %9d  compressed=%8d  inflated=%9d  %s"
                          % (pos, consumed, len(blob), kind))
                    found += 1
                    pos += consumed
                    continue
            except zlib.error:
                pass
        pos += 1
    print("%d streams recovered" % found)

if __name__ == "__main__":
    main()
