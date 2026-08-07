/*
 * Copyright (C) 2026 The media3-decoder-ape Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.eugenedibtsev.media3.ape;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assume.assumeTrue;

import android.os.Process;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * MACLib worker scaling measurement over the current-format (3.99) corpus files at every
 * compression level.
 *
 * <p>Each combination decodes the whole file through the real extractor path and must stay
 * bit-exact against the reference PCM MD5. The thread priority is pinned to the audio priority
 * for the whole run so the scheduler does not skew the comparison; the single-thread numbers at
 * background priority live in the main performance table.
 *
 * <p>Results are logged as {@code ApeWorkerPerf} lines: {@code file threads=N decode_ms=… rtf=…}.
 */
@RunWith(AndroidJUnit4.class)
public final class ApeWorkerPerfTest {

  private static final File TEST_DIR = ApeTestUtil.corpusDir();
  private static final int MAX_THREADS = 4;

  private static final String[] FILES = {
    "gen-1399-fast-44k-16b-2ch.ape",
    "gen-1399-normal-44k-16b-2ch.ape",
    "gen-1399-high-44k-16b-2ch.ape",
    "gen-1399-extrahigh-44k-16b-2ch.ape",
    "gen-1399-insane-44k-16b-2ch.ape",
  };

  @Test
  public void workerScaling_staysBitExact() throws Exception {
    ApeTestUtil.assumeManualHarness();
    assumeTrue("corpus has no " + FILES[0], new File(TEST_DIR, FILES[0]).exists());
    // The expected PCM comes from the corpus manifest, not a constant, because the digest depends
    // on which corpus ApeTestUtil resolved. In the bundled synthetic corpus these files do not
    // share a digest (Normal is the 16-second program, the rest the 2-second one); in a corpus
    // built from one track at five levels they would. Regenerating either changes it again.
    Map<String, String> expectedMd5 = readManifestMd5s();
    assumeTrue("manifest carries no digests", !expectedMd5.isEmpty());
    int originalPriority = Process.getThreadPriority(Process.myTid());
    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
    try {
      for (String name : FILES) {
        File file = new File(TEST_DIR, name);
        String expected = expectedMd5.get(name);
        if (!file.exists() || expected == null) {
          continue;
        }
        for (int threads = 1; threads <= MAX_THREADS; threads++) {
          ApeExtractor.decoderThreadOverride = threads;
          ApeTestUtil.DecodeResult result = ApeTestUtil.decodeToMd5(file);
          Log.i(
              "ApeWorkerPerf",
              String.format(
                  Locale.ROOT,
                  "%s threads=%d decode_ms=%d rtf=%.4f",
                  name,
                  threads,
                  result.decodeTimeMs,
                  result.realtimeFactor()));
          assertWithMessage("PCM MD5 for %s with %s worker(s)", name, threads)
              .that(result.pcmMd5.toUpperCase(Locale.ROOT))
              .isEqualTo(expected.toUpperCase(Locale.ROOT));
        }
      }
    } finally {
      ApeExtractor.decoderThreadOverride = 0;
      Process.setThreadPriority(originalPriority);
    }
  }

  /** Maps corpus file name to its expected PCM MD5, from the manifest shipped with the corpus. */
  private static Map<String, String> readManifestMd5s() throws IOException {
    Map<String, String> digests = new HashMap<>();
    File manifestFile = new File(TEST_DIR, "manifest.csv");
    if (!manifestFile.exists()) {
      return digests;
    }
    try (BufferedReader reader = new BufferedReader(new FileReader(manifestFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        String[] parts = line.split(",");
        if (parts.length >= 2) {
          digests.put(parts[0], parts[1]);
        }
      }
    }
    return digests;
  }
}
