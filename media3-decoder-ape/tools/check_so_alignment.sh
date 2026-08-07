#!/usr/bin/env bash
#
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
#
# Fails when any built libapeJNI.so has LOAD segments aligned below 16 KB.
# Android 15+ devices with 16 KB pages reject (or warn about) 4 KB-aligned
# libraries, and Google Play requires 16 KB support for targetSdk 35+. NDK
# r27 still defaults to 4 KB, so the alignment depends on a linker flag in
# CMakeLists.txt that a toolchain or flag change could silently drop.
#
# Usage: check_so_alignment.sh <dir-to-scan> [readelf-binary]

set -eu

scan_dir="${1:-.}"
readelf_bin="${2:-}"

# Locate a readelf. The NDK ships llvm-readelf; a system llvm-readelf or a
# plain GNU readelf will do as well. Falling through to a bare command name
# was how this used to fail: the tool was simply missing, every file then
# produced no program headers, and the script reported "is this an ELF file?"
# about perfectly good libraries. Be explicit about where we looked instead.
searched=""
try_ndk_root() {
  [ -n "${1:-}" ] && [ -d "${1}/toolchains/llvm/prebuilt" ] || return 1
  searched="${searched}
  ${1}/toolchains/llvm/prebuilt"
  found="$(find "${1}/toolchains/llvm/prebuilt" -name 'llvm-readelf*' -type f 2>/dev/null | head -1)"
  [ -n "${found}" ] || return 1
  readelf_bin="${found}"
}

if [ -z "${readelf_bin}" ]; then
  for root in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
              "${ANDROID_HOME:-}"/ndk/* "${ANDROID_SDK_ROOT:-}"/ndk/*; do
    try_ndk_root "${root}" && break
  done
fi
if [ -z "${readelf_bin}" ]; then
  for candidate in llvm-readelf readelf; do
    searched="${searched}
  ${candidate} on PATH"
    if command -v "${candidate}" > /dev/null 2>&1; then
      readelf_bin="${candidate}"
      break
    fi
  done
fi
if [ -z "${readelf_bin}" ]; then
  echo "No readelf found. Looked in:${searched}" >&2
  echo "Install the NDK, set ANDROID_NDK_HOME, or pass one as the second" >&2
  echo "argument: check_so_alignment.sh <dir> /path/to/llvm-readelf" >&2
  exit 1
fi

required=16384
failed=0
found=0

# 32-bit ARM has no 16 KB page variant; the flag is harmless there but the
# linker may still emit a smaller alignment, so it is reported, not enforced.
while IFS= read -r so; do
  [ -n "${so}" ] || continue
  found=$((found + 1))
  abi="$(basename "$(dirname "${so}")")"
  # One line per program header; the Align column is last. The gate is the
  # smallest alignment across the LOAD segments. Hex is converted in the
  # shell rather than with awk's strtonum, which is a gawk extension and is
  # missing from the mawk that some Linux images ship as /usr/bin/awk.
  align_dec=""
  # -W keeps each program header on one line. Without it GNU readelf wraps
  # 64-bit LOAD entries and $NF becomes an address, not the alignment.
  for hex in $("${readelf_bin}" -lW "${so}" | awk '/^ *LOAD / { print $NF }'); do
    value=$((16#${hex#0x}))
    if [ -z "${align_dec}" ] || [ "${value}" -lt "${align_dec}" ]; then
      align_dec="${value}"
    fi
  done
  if [ -z "${align_dec}" ]; then
    echo "${so}: no LOAD segments found; is this an ELF file?" >&2
    failed=1
    continue
  fi
  align="$(printf '0x%x' "${align_dec}")"
  if [ "${abi}" = "armeabi-v7a" ]; then
    printf '%-14s p_align=%s (%s bytes) [not enforced: 32-bit]\n' \
        "${abi}" "${align}" "${align_dec}"
  elif [ "${align_dec}" -lt "${required}" ]; then
    printf '%-14s p_align=%s (%s bytes) FAIL: below 16 KB\n' \
        "${abi}" "${align}" "${align_dec}"
    failed=1
  else
    printf '%-14s p_align=%s (%s bytes) ok\n' "${abi}" "${align}" "${align_dec}"
  fi
done <<EOF
$(find "${scan_dir}" -name 'libapeJNI.so' -type f | sort)
EOF

if [ "${found}" -eq 0 ]; then
  echo "no libapeJNI.so found under ${scan_dir}; nothing was verified" >&2
  exit 1
fi

if [ "${failed}" -ne 0 ]; then
  echo "" >&2
  echo "Native libraries are not 16 KB aligned. Check that CMakeLists.txt" >&2
  echo "still passes -Wl,-z,max-page-size=16384 to the linker." >&2
  exit 1
fi

echo "all ${found} libraries pass the 16 KB alignment gate"
