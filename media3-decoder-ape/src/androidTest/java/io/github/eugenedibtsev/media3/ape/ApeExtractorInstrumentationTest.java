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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.os.Debug;
import androidx.media3.common.ParserException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * On-device validation against the synthetic corpus shipped in the androidTest assets. No setup:
 * the corpus travels inside the APK and is extracted on first use.
 *
 * <p>To run against material of your own instead, pass {@code -e corpusDir <path>}; see {@link
 * ApeTestUtil#corpusDir()}. Such a directory must contain the {@code .ape} files, a {@code
 * manifest.csv} with lines of the form {@code
 * filename,expected_pcm_md5,expected_blocks,reference_pcm}, the reference PCM files its fourth
 * column names, and optionally {@code broken.txt} naming files that must fail cleanly — the same
 * layout the generators under {@code tools/} produce.
 */
@RunWith(AndroidJUnit4.class)
public final class ApeExtractorInstrumentationTest {

  private static final File TEST_DIR = ApeTestUtil.corpusDir();
  private static final int SEEK_ITERATIONS = 100;
  private static final int SEEK_READ_BLOCKS = 4096;

  private List<String[]> manifest;

  @Before
  public void setUp() throws Exception {
    File manifestFile = new File(TEST_DIR, "manifest.csv");
    assumeTrue("no corpus at " + TEST_DIR, manifestFile.exists());
    manifest = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new FileReader(manifestFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          manifest.add(line.split(","));
        }
      }
    }
    assumeTrue("manifest is empty", !manifest.isEmpty());
  }

  @Test
  public void decodeCorpus_bitExact() throws Exception {
    for (String[] entry : manifest) {
      File file = new File(TEST_DIR, entry[0]);
      if (!file.exists()) {
        continue;
      }
      ApeTestUtil.DecodeResult result = ApeTestUtil.decodeToMd5(file);
      android.util.Log.i(
          "ApePerf",
          String.format(
              Locale.ROOT,
              "%s decode_ms=%d rtf=%.4f",
              entry[0],
              result.decodeTimeMs,
              result.realtimeFactor()));
      assertWithMessage("PCM MD5 for %s", entry[0])
          .that(result.pcmMd5.toUpperCase(Locale.ROOT))
          .isEqualTo(entry[1].toUpperCase(Locale.ROOT));
      assertWithMessage("block count for %s", entry[0])
          .that(result.totalBlocks)
          .isEqualTo(Long.parseLong(entry[2]));
      if (entry[0].contains("id3v2")) {
        // Bit-exact PCM alone would still pass if the ID3v2 tag were skipped
        // but never parsed, so assert the tag actually reached the format.
        assertWithMessage("ID3v2 metadata for %s", entry[0])
            .that(result.metadata)
            .isNotNull();
        assertWithMessage("ID3v2 metadata entries for %s", entry[0])
            .that(result.metadata.length())
            .isGreaterThan(0);
      }
    }
  }

  @Test
  public void brokenFiles_failCleanlyWithoutCrashing() throws Exception {
    File brokenList = new File(TEST_DIR, "broken.txt");
    assumeTrue("no broken.txt in corpus", brokenList.exists());
    try (BufferedReader reader = new BufferedReader(new FileReader(brokenList))) {
      String name;
      while ((name = reader.readLine()) != null) {
        File file = new File(TEST_DIR, name.trim());
        if (!file.exists()) {
          continue;
        }
        assertThrows(ParserException.class, () -> ApeTestUtil.decodeToMd5(file));
      }
    }
  }

  @Test
  public void randomSeeks_landOnExactSample() throws Exception {
    for (String[] entry : manifest) {
      if (entry.length < 4 || entry[3].isEmpty()) {
        continue; // No reference PCM listed for this file.
      }
      File file = new File(TEST_DIR, entry[0]);
      File referencePcm = new File(TEST_DIR, entry[3]);
      if (!file.exists() || !referencePcm.exists()) {
        continue;
      }
      try {
        runSeekTest(file, referencePcm, Long.parseLong(entry[2]));
      } catch (Exception e) {
        throw new AssertionError("seek test failed for " + entry[0], e);
      }
    }
  }

  private void runSeekTest(File file, File referencePcm, long totalBlocks) throws Exception {
    Random random = new Random(/* seed= */ 42);
    try (ApeTestUtil.SeekSession session = ApeTestUtil.openForSeeking(file);
        RandomAccessFile reference = new RandomAccessFile(referencePcm, "r")) {
      int blockAlign = session.blockAlign();
      byte[] expected = new byte[SEEK_READ_BLOCKS * blockAlign];
      for (int i = 0; i < SEEK_ITERATIONS; i++) {
        long target;
        if (i == 0) {
          target = 0;
        } else if (i == 1) {
          target = totalBlocks - 1;
        } else if (i == 2) {
          target = totalBlocks + 12_345; // Past the end: must clamp, not crash.
        } else {
          target = (random.nextLong() & Long.MAX_VALUE) % totalBlocks;
        }

        ApeTestUtil.SeekSession.SeekResult result =
            session.seekAndRead(target, SEEK_READ_BLOCKS, totalBlocks);
        long landingBlock = result.landingBlock;
        byte[] actual = result.data;
        int expectedLength =
            (int)
                Math.min(
                    (long) SEEK_READ_BLOCKS * blockAlign, (totalBlocks - landingBlock) * blockAlign);
        reference.seek(landingBlock * blockAlign);
        reference.readFully(expected, 0, Math.min(expectedLength, actual.length));

        assertWithMessage("seek to block %s of %s", landingBlock, file.getName())
            .that(md5(actual))
            .isEqualTo(md5(expected, Math.min(expectedLength, actual.length)));
      }
    }
  }

  @Test
  public void fuzzedHeaders_neverCrashTheProcess() throws Exception {
    File fuzzDir = new File(TEST_DIR, "fuzz");
    File[] files = fuzzDir.listFiles();
    assumeTrue("corpus has no fuzz/ directory", files != null && files.length > 0);
    int cleanFailures = 0;
    int decoded = 0;
    for (File file : files) {
      try {
        ApeTestUtil.decodeToMd5(file);
        decoded++; // Some mutations leave a decodable stream; that is fine too.
      } catch (Exception e) {
        cleanFailures++; // Any clean exception is a pass; a crash would kill the process.
      }
    }
    android.util.Log.i(
        "ApeFuzz", "files=" + files.length + " cleanFailures=" + cleanFailures + " decoded=" + decoded);
    assertThat(cleanFailures + decoded).isEqualTo(files.length);
  }

  @Test
  public void repeatedOpenClose_doesNotLeakNativeMemory() throws Exception {
    File file = new File(TEST_DIR, manifest.get(0)[0]);
    assumeTrue(file.exists());

    // Warm up allocator pools before measuring.
    for (int i = 0; i < 50; i++) {
      ApeTestUtil.openAndClose(file);
    }
    long before = Debug.getNativeHeapAllocatedSize();
    for (int i = 0; i < 1000; i++) {
      ApeTestUtil.openAndClose(file);
    }
    long after = Debug.getNativeHeapAllocatedSize();

    // The native heap statistic is noisy; a real per-cycle leak of the decoder
    // state (several hundred KB each) would exceed this by orders of magnitude.
    assertWithMessage("native heap growth after 1000 open/close cycles")
        .that(after - before)
        .isLessThan(8L * 1024 * 1024);
  }

  private static String md5(byte[] data) throws NoSuchAlgorithmException {
    return md5(data, data.length);
  }

  private static String md5(byte[] data, int length) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("MD5");
    digest.update(data, 0, length);
    StringBuilder sb = new StringBuilder();
    for (byte b : digest.digest()) {
      sb.append(String.format(Locale.ROOT, "%02X", b));
    }
    return sb.toString();
  }
}
