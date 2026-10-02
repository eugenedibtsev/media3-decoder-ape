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
# Downloads the pinned Monkey's Audio SDK into src/main/jni/maclib and
# verifies its checksum. The SDK sources are BSD-3-licensed and are not
# vendored into this repository.

set -eu

MACLIB_VERSION="13.27"
MACLIB_URL="https://monkeysaudio.com/files/MAC_1327_SDK.zip"
MACLIB_SHA256="c47c6b36f6a7bd50d990f2eb36a70915c0074a7b9634be396c94464905e76686"

cd "$(dirname "$0")"

if [ -f maclib/.downloaded_sha256 ] \
    && [ "$(cat maclib/.downloaded_sha256)" = "${MACLIB_SHA256}" ]; then
  echo "MACLib ${MACLIB_VERSION} already present."
  exit 0
fi

echo "Downloading Monkey's Audio SDK ${MACLIB_VERSION}..."
curl -L --fail -o mac_sdk.zip "${MACLIB_URL}"

echo "${MACLIB_SHA256}  mac_sdk.zip" | sha256sum -c - || {
  echo "Checksum mismatch, aborting." >&2
  rm -f mac_sdk.zip
  exit 1
}

rm -rf maclib
mkdir maclib
unzip -q mac_sdk.zip -d maclib
rm -f mac_sdk.zip
echo "${MACLIB_SHA256}" > maclib/.downloaded_sha256
echo "MACLib ${MACLIB_VERSION} ready in $(pwd)/maclib"
