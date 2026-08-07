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

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Measures the wall-clock cost of a user seek: from the seek request to the first decoded PCM
 * chunk (4096 blocks, the extractor's decode granularity), split by jump direction and file
 * flavor. Numbers go to logcat under the {@code ApeSeekLat} tag as CSV.
 *
 * <p>Measurement harness, not a functional test: it only sanity-asserts that every seek produced
 * data. Excluded from the default CI set.
 */
@RunWith(AndroidJUnit4.class)
public final class ApeSeekLatencyTest {

  private static final File TEST_DIR = ApeTestUtil.corpusDir();

  private static final String[] FILES = {
    // 3.98+ descriptor layout (format 3.99), lightest and heaviest levels.
    "gen-1399-fast-44k-16b-2ch.ape",
    "gen-1399-insane-44k-16b-2ch.ape",
    // Legacy 294912-blocks-per-frame branch (3.95a1 / 3.96 / 3.97).
    "gen-395a1-fast.ape",
    "gen-395a1-extrahigh.ape",
    "gen-396-fast.ape",
    "gen-396-extrahigh.ape",
    "gen-397-fast.ape",
    "gen-397-extrahigh.ape",
  };

  private static final int SEEK_ITERATIONS = 60;
  private static final int FIRST_CHUNK_BLOCKS = 4096;

  @Test
  public void measureSeekLatency() throws Exception {
    ApeTestUtil.assumeManualHarness();
    boolean anyFile = false;
    for (String name : FILES) {
      File file = new File(TEST_DIR, name);
      if (!file.exists()) {
        continue;
      }
      anyFile = true;

      List<Double> forwardMs = new ArrayList<>();
      List<Double> backwardMs = new ArrayList<>();
      try (ApeTestUtil.SeekSession session = ApeTestUtil.openForSeeking(file)) {
        long totalBlocks = session.totalBlocks();
        Random random = new Random(2026);
        long previousTarget = 0;
        for (int i = 0; i < SEEK_ITERATIONS; i++) {
          long target = (random.nextLong() & Long.MAX_VALUE) % totalBlocks;
          long startNs = System.nanoTime();
          ApeTestUtil.SeekSession.SeekResult result =
              session.seekAndRead(target, FIRST_CHUNK_BLOCKS, totalBlocks);
          double elapsedMs = (System.nanoTime() - startNs) / 1e6;
          assertWithMessage("seek to %s in %s produced no data", target, name)
              .that(result.data.length)
              .isGreaterThan(0);
          (target >= previousTarget ? forwardMs : backwardMs).add(elapsedMs);
          previousTarget = target;
        }
      }
      report(name, "forward", forwardMs);
      report(name, "backward", backwardMs);
    }
    assumeTrue("no corpus at " + TEST_DIR, anyFile);
  }

  private static void report(String file, String direction, List<Double> samples) {
    if (samples.isEmpty()) {
      return;
    }
    Collections.sort(samples);
    int n = samples.size();
    double p50 = samples.get(n / 2);
    double p95 = samples.get(Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1));
    double max = samples.get(n - 1);
    Log.i(
        "ApeSeekLat",
        String.format(
            Locale.ROOT, "%s,%s,n=%d,p50=%.1f,p95=%.1f,max=%.1f", file, direction, n, p50, p95,
            max));
  }
}
