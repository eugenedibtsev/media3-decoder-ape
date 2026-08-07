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
"""Pushes a corpus of your own to a device, for running the suite against it.

The instrumentation tests do not need this. They ship a synthetic corpus
inside the APK and use it by default; nothing on the device is read unless
you ask for it. This script is for the case where you have material of your
own -- your own recordings, an encoder this project cannot redistribute --
and want the same tests run over it.

Takes a directory holding the (possibly merged) manifest.csv and broken.txt,
plus any number of search directories where the files named in them live.
Also pushes every reference PCM the manifest mentions and, when present, a
fuzz/ subdirectory of the manifest directory. No absolute paths anywhere.

Usage:
  python push_corpus.py [--serial SERIAL] [--device-dir DIR] <manifest_dir> [search_dir...]

It prints the `am instrument` line to run afterwards. Nothing you push here
is part of the library or of any release built from it.
"""

import os
import subprocess
import sys

DEFAULT_DEVICE_DIR = "/data/local/tmp/ape_corpus"


def take_option(args, name, default=None):
    if name not in args:
        return default
    i = args.index(name)
    args.pop(i)
    return args.pop(i)


def main():
    args = sys.argv[1:]
    serial_value = take_option(args, "--serial")
    serial = ["-s", serial_value] if serial_value else []
    device_dir = take_option(args, "--device-dir", DEFAULT_DEVICE_DIR)
    if not args:
        sys.exit(__doc__.strip())
    manifest_dir = args[0]
    search_dirs = [manifest_dir] + args[1:]

    def locate(name):
        for directory in search_dirs:
            candidate = os.path.join(directory, name)
            if os.path.exists(candidate):
                return candidate
        sys.exit("file not found in search dirs: " + name)

    def adb(*adb_args):
        result = subprocess.run(["adb"] + serial + list(adb_args))
        if result.returncode != 0:
            sys.exit("adb failed: %s" % (adb_args,))

    names = set()
    with open(os.path.join(manifest_dir, "manifest.csv")) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(",")
            names.add(parts[0])
            if len(parts) > 3 and parts[3]:
                names.add(parts[3])
    broken_path = os.path.join(manifest_dir, "broken.txt")
    if os.path.exists(broken_path):
        with open(broken_path) as f:
            names.update(line.strip() for line in f if line.strip())

    adb("shell", "mkdir", "-p", device_dir)
    for name in sorted(names):
        adb("push", locate(name), device_dir + "/" + name)
    adb("push", os.path.join(manifest_dir, "manifest.csv"), device_dir + "/manifest.csv")
    if os.path.exists(broken_path):
        adb("push", broken_path, device_dir + "/broken.txt")
    fuzz_dir = os.path.join(manifest_dir, "fuzz")
    if os.path.isdir(fuzz_dir):
        adb("push", fuzz_dir, device_dir + "/fuzz")
    print("pushed %d corpus files to %s" % (len(names), device_dir))
    print()
    print("The suite ignores this unless you name it. To use it:")
    print("  adb shell am instrument -w -e corpusDir %s \\" % device_dir)
    print("      io.github.eugenedibtsev.media3.ape.test/"
          "androidx.test.runner.AndroidJUnitRunner")


if __name__ == "__main__":
    main()
