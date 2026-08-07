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

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Regression test for the MACLib 1008 ("input file too small") failure mode.
 *
 * <p>A cancelled load makes {@code ExtractorInput.read()} throw mid-frame. The IO bridge must
 * surface that as a read <em>error</em> to MACLib and let the original exception propagate so the
 * load is retried; it must not report a short read as success, which desynchronizes MACLib's bit
 * buffer and turns a transient cancellation into a permanent decode failure (1008/1009 or, on
 * CRC-less legacy files, silently corrupted PCM).
 *
 * <p>The test decodes corpus files while injecting one-shot {@link IOException}s at fixed byte
 * positions, retrying the way Media3's loader does, and asserts the final PCM is still bit-exact
 * against the corpus manifest.
 */
@RunWith(AndroidJUnit4.class)
public final class ApeLoadRecoveryTest {

  private static final File TEST_DIR = ApeTestUtil.corpusDir();

  /** Fault positions as fractions of the file length, all well inside the frame data region. */
  private static final double[] FAULT_FRACTIONS = {0.30, 0.60};

  /** A fault fires only within this window past its trigger, so tail-probe seeks skip it. */
  private static final long FAULT_WINDOW_BYTES = 64 * 1024;

  private static final int MAX_RETRIES = 10;

  @Test
  public void decodeStaysBitExactAcrossCancelledLoads() throws Exception {
    List<String[]> manifest = readManifest();
    assumeTrue("no corpus at " + TEST_DIR, !manifest.isEmpty());

    // Every manifest row that carries a reference PCM is tested. On the CI corpus these are the
    // multi-frame files of both container generations; a larger corpus of your own exercises the
    // same path over whatever else it contains.
    int tested = 0;
    for (String[] entry : manifest) {
      if (entry.length < 4 || entry[3].isEmpty()) {
        continue;
      }
      String name = entry[0];
      File file = new File(TEST_DIR, name);
      File referencePcm = new File(TEST_DIR, entry[3]);
      if (!file.exists() || !referencePcm.exists()) {
        continue;
      }
      tested++;

      // Baseline without faults first: proves the harness itself reproduces the manifest MD5.
      DecodeResult baseline = decodeWithRetries(file, new FaultInjector(0, new double[0]));
      assertWithMessage("baseline (no faults) PCM MD5 for %s", name)
          .that(baseline.pcmMd5.toUpperCase(Locale.ROOT))
          .isEqualTo(entry[1].toUpperCase(Locale.ROOT));

      FaultInjector injector = new FaultInjector(file.length(), FAULT_FRACTIONS);
      DecodeResult result = decodeWithRetries(file, injector);

      // On mismatch, locate the first corrupt byte against the reference PCM before failing.
      if (!result.pcmMd5.equalsIgnoreCase(entry[1])) {
        android.util.Log.e(
            "ApeRecovery", name + " divergence: " + firstDivergence(file, referencePcm));
      }

      assertWithMessage("all injected faults fired for %s", name)
          .that(injector.remainingFaults())
          .isEqualTo(0);
      assertWithMessage("retries were exercised for %s", name)
          .that(result.retries)
          .isAtLeast(FAULT_FRACTIONS.length);
      assertWithMessage("PCM MD5 for %s after cancelled loads", name)
          .that(result.pcmMd5.toUpperCase(Locale.ROOT))
          .isEqualTo(entry[1].toUpperCase(Locale.ROOT));
      assertWithMessage("block count for %s after cancelled loads", name)
          .that(result.totalBlocks)
          .isEqualTo(Long.parseLong(entry[2]));
    }
    assumeTrue("no manifest rows carry a reference PCM", tested > 0);
  }

  private static final class DecodeResult {
    final String pcmMd5;
    final long totalBlocks;
    final int retries;

    DecodeResult(String pcmMd5, long totalBlocks, int retries) {
      this.pcmMd5 = pcmMd5;
      this.totalBlocks = totalBlocks;
      this.retries = retries;
    }
  }

  /** One-shot IO faults at fixed byte positions of a sequentially consumed region. */
  private static final class FaultInjector {
    private final long[] triggers;
    private final boolean[] armed;

    FaultInjector(long fileLength, double[] fractions) {
      triggers = new long[fractions.length];
      armed = new boolean[fractions.length];
      for (int i = 0; i < fractions.length; i++) {
        triggers[i] = (long) (fileLength * fractions[i]);
        armed[i] = true;
      }
    }

    void maybeThrow(long position) throws IOException {
      for (int i = 0; i < triggers.length; i++) {
        if (armed[i] && position >= triggers[i] && position < triggers[i] + FAULT_WINDOW_BYTES) {
          armed[i] = false;
          throw new IOException("simulated cancelled load at byte " + position);
        }
      }
    }

    int remainingFaults() {
      int count = 0;
      for (boolean b : armed) {
        if (b) {
          count++;
        }
      }
      return count;
    }
  }

  private static DecodeResult decodeWithRetries(File file, FaultInjector injector)
      throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      long length = raf.length();
      Md5Output output = new Md5Output();
      int retries = driveToEnd(raf, length, output, injector);
      Format format = output.format;
      int blockAlign = ((format.pcmEncoding == C.ENCODING_PCM_16BIT ? 2
          : format.pcmEncoding == C.ENCODING_PCM_24BIT ? 3
          : format.pcmEncoding == C.ENCODING_PCM_8BIT ? 1 : 4)) * format.channelCount;
      return new DecodeResult(output.md5Hex(), output.totalBytes / blockAlign, retries);
    }
  }

  /** Drives the extractor to the end with loader-style retries; returns the retry count. */
  private static int driveToEnd(
      RandomAccessFile raf, long length, Md5Output output, FaultInjector injector)
      throws Exception {
    ApeExtractor extractor = new ApeExtractor();
    int retries = 0;
    try {
      extractor.init(output);
      ExtractorInput input = inputAt(raf, 0, length, injector);
      PositionHolder positionHolder = new PositionHolder();
      while (true) {
        int result;
        try {
          result = extractor.read(input, positionHolder);
        } catch (IOException e) {
          // Loader-style retry: the extractor already parked the input at the position it
          // wants to resume from (setRetryPosition); reattach a fresh input there.
          if (++retries > MAX_RETRIES) {
            throw new AssertionError("decode did not recover after " + MAX_RETRIES + " retries", e);
          }
          input = inputAt(raf, input.getPosition(), length, injector);
          continue;
        }
        if (result == Extractor.RESULT_SEEK) {
          input = inputAt(raf, positionHolder.position, length, injector);
        } else if (result == Extractor.RESULT_END_OF_INPUT) {
          break;
        }
      }
    } finally {
      extractor.release();
    }
    return retries;
  }

  private static ExtractorInput inputAt(
      RandomAccessFile raf, long position, long length, FaultInjector injector)
      throws IOException {
    raf.seek(position);
    DataReader dataReader =
        (target, offset, maxLength) -> {
          injector.maybeThrow(raf.getFilePointer());
          int read = raf.read(target, offset, maxLength);
          return read == -1 ? C.RESULT_END_OF_INPUT : read;
        };
    return new DefaultExtractorInput(dataReader, position, length);
  }

  /** Re-runs the faulted decode, comparing every PCM byte against the reference file. */
  private static String firstDivergence(File file, File referencePcm) throws Exception {
    try (RandomAccessFile raf = new RandomAccessFile(file, "r");
        RandomAccessFile ref = new RandomAccessFile(referencePcm, "r")) {
      long length = raf.length();
      FaultInjector injector = new FaultInjector(length, FAULT_FRACTIONS);
      long[] firstMismatch = {-1};
      long[] mismatchedBytes = {0};
      long[] shiftPlusMatch = {0}; // bytes matching reference one frame AHEAD (294912 blocks * 4)
      long[] total = {0};
      long frameBytes = 294912L * 4;
      byte[] refBuffer = new byte[64 * 1024];
      byte[] refShifted = new byte[64 * 1024];
      Md5Output output =
          new Md5Output() {
            @Override
            void consume(byte[] data, int offset, int count) {
              try {
                ref.seek(total[0]);
                int refRead = ref.read(refBuffer, 0, count);
                int shiftedRead = 0;
                if (total[0] + frameBytes < ref.length()) {
                  ref.seek(total[0] + frameBytes);
                  shiftedRead = ref.read(refShifted, 0, count);
                }
                for (int i = 0; i < count; i++) {
                  if (i >= refRead || data[offset + i] != refBuffer[i]) {
                    if (firstMismatch[0] < 0) {
                      firstMismatch[0] = total[0] + i;
                    }
                    mismatchedBytes[0]++;
                    if (i < shiftedRead && data[offset + i] == refShifted[i]) {
                      shiftPlusMatch[0]++;
                    }
                  }
                }
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
              total[0] += count;
              super.consume(data, offset, count);
            }
          };
      driveToEnd(raf, length, output, injector);
      return "firstMismatchByte="
          + firstMismatch[0]
          + " mismatchedBytes="
          + mismatchedBytes[0]
          + " ofWhichMatchRefOneFrameAhead="
          + shiftPlusMatch[0]
          + " totalBytes="
          + total[0]
          + " faultTriggersAt="
          + (long) (length * FAULT_FRACTIONS[0])
          + "/"
          + (long) (length * FAULT_FRACTIONS[1]);
    }
  }

  private static List<String[]> readManifest() throws IOException {
    List<String[]> manifest = new ArrayList<>();
    File manifestFile = new File(TEST_DIR, "manifest.csv");
    if (!manifestFile.exists()) {
      return manifest;
    }
    try (BufferedReader reader = new BufferedReader(new FileReader(manifestFile))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          manifest.add(line.split(","));
        }
      }
    }
    return manifest;
  }

  /** Minimal output that hashes every emitted PCM byte. */
  private static class Md5Output implements ExtractorOutput, TrackOutput {
    Format format;
    long totalBytes;
    private final MessageDigest digest;
    private final byte[] scratch = new byte[64 * 1024];

    Md5Output() throws Exception {
      digest = MessageDigest.getInstance("MD5");
    }

    void consume(byte[] data, int offset, int count) {
      digest.update(data, offset, count);
      totalBytes += count;
    }

    String md5Hex() {
      StringBuilder sb = new StringBuilder();
      for (byte b : digest.digest()) {
        sb.append(String.format(Locale.ROOT, "%02X", b));
      }
      return sb.toString();
    }

    @Override
    public TrackOutput track(int id, int type) {
      return this;
    }

    @Override
    public void endTracks() {}

    @Override
    public void seekMap(SeekMap seekMap) {}

    @Override
    public void format(Format format) {
      this.format = format;
    }

    @Override
    public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
        throws IOException {
      int read = input.read(scratch, 0, Math.min(length, scratch.length));
      if (read == C.RESULT_END_OF_INPUT) {
        return C.RESULT_END_OF_INPUT;
      }
      consume(scratch, 0, read);
      return read;
    }

    @Override
    public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
      consume(data.getData(), data.getPosition(), length);
      data.skipBytes(length);
    }

    @Override
    public void sampleMetadata(
        long timeUs, int flags, int size, int offset, TrackOutput.CryptoData cryptoData) {}
  }
}
