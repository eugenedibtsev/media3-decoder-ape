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

import androidx.annotation.Nullable;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/** Drives {@link ApeExtractor} over a local file the way a player load task would. */
/* package */ final class ApeTestUtil {

  /** Logcat tag under which the resolved corpus is announced. */
  private static final String TAG = "ApeCorpus";

  /**
   * Instrumentation argument pointing the suite at a corpus of your own:
   *
   * <pre>{@code
   * adb shell am instrument -w -e corpusDir /data/local/tmp/my_corpus ...
   * }</pre>
   *
   * <p>The directory must hold a {@code manifest.csv} in the format the generators under
   * {@code tools/} emit. Nothing about that corpus ships with this library; it is yours, it stays
   * on your device, and it is read only because you named it on the command line.
   */
  private static final String ARG_CORPUS_DIR = "corpusDir";

  @Nullable private static File resolvedCorpusDir;

  /**
   * Resolves the corpus root.
   *
   * <p>The default, and the only thing CI ever sees, is the synthetic corpus bundled in the
   * androidTest assets, extracted on first use. The suite does not go looking for material
   * anywhere on the device: an external corpus is used only when {@link #ARG_CORPUS_DIR} names
   * one. Whichever is chosen is logged under {@code ApeCorpus}, so a set of results can always be
   * attributed to the corpus that produced it.
   */
  public static synchronized File corpusDir() {
    if (resolvedCorpusDir != null) {
      return resolvedCorpusDir;
    }
    String requested =
        androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString(ARG_CORPUS_DIR);
    if (requested != null) {
      File external = new File(requested);
      if (!new File(external, "manifest.csv").isFile()) {
        throw new IllegalArgumentException(
            "-e " + ARG_CORPUS_DIR + " " + requested + " has no manifest.csv");
      }
      android.util.Log.i(TAG, "corpus: external, " + external + " (-e " + ARG_CORPUS_DIR + ")");
      resolvedCorpusDir = external;
      return resolvedCorpusDir;
    }
    android.content.Context testContext =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getContext();
    File extracted = new File(testContext.getFilesDir(), "ci_corpus");
    try {
      // The marker carries a fingerprint of the asset manifest, not just a
      // "done" flag: app data survives `install -r`, so a plain flag would
      // pin the first corpus ever extracted and silently test stale data
      // after the corpus changes.
      String fingerprint = assetFingerprint(testContext.getAssets());
      File marker = new File(extracted, ".complete");
      if (!fingerprint.equals(readFileOrEmpty(marker))) {
        deleteTree(extracted);
        copyAssetTree(testContext.getAssets(), "ci_corpus", extracted);
        if (new File(extracted, "manifest.csv").exists()) {
          try (java.io.FileOutputStream out = new java.io.FileOutputStream(marker)) {
            out.write(fingerprint.getBytes("UTF-8"));
          }
        }
      }
    } catch (IOException e) {
      // Fall through: tests will assume-skip on the missing manifest.
    }
    android.util.Log.i(TAG, "corpus: bundled synthetic assets, " + extracted);
    resolvedCorpusDir = extracted;
    return resolvedCorpusDir;
  }

  /** MD5 of the asset manifest, identifying which corpus revision is bundled in the APK. */
  private static String assetFingerprint(android.content.res.AssetManager assets)
      throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (java.io.InputStream in = assets.open("ci_corpus/manifest.csv")) {
      byte[] chunk = new byte[8192];
      int read;
      while ((read = in.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
    }
    try {
      byte[] digest = MessageDigest.getInstance("MD5").digest(buffer.toByteArray());
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) {
        hex.append(String.format(Locale.ROOT, "%02X", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String readFileOrEmpty(File file) {
    if (!file.isFile()) {
      return "";
    }
    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[256];
      int read;
      while ((read = in.read(chunk)) != -1) {
        buffer.write(chunk, 0, read);
      }
      return buffer.toString("UTF-8");
    } catch (IOException e) {
      return "";
    }
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteTree(child);
      }
    }
    file.delete();
  }

  private static void copyAssetTree(
      android.content.res.AssetManager assets, String path, File target) throws IOException {
    String[] children = assets.list(path);
    if (children == null || children.length == 0) {
      // A file (or an absent directory, in which case open() throws and the caller skips).
      File parent = target.getParentFile();
      if (parent != null) {
        parent.mkdirs();
      }
      try (java.io.InputStream in = assets.open(path);
          java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
          out.write(buffer, 0, read);
        }
      }
      return;
    }
    for (String child : children) {
      copyAssetTree(assets, path + "/" + child, new File(target, child));
    }
  }

  /** Skips the calling test unless the instrumentation was started with {@code -e manual true}. */
  public static void assumeManualHarness() {
    org.junit.Assume.assumeTrue(
        "measurement harness: pass -e manual true to run",
        "true".equals(
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("manual")));
  }

  /** Result of a full decode. */
  public static final class DecodeResult {
    public final String pcmMd5;
    public final long totalBlocks;
    public final int sampleRate;
    public final long decodeTimeMs;
    /** Metadata attached to the emitted format: tags found in or around the container. */
    @Nullable public final androidx.media3.common.Metadata metadata;

    /* package */ DecodeResult(
        String pcmMd5,
        long totalBlocks,
        int sampleRate,
        long decodeTimeMs,
        @Nullable androidx.media3.common.Metadata metadata) {
      this.pcmMd5 = pcmMd5;
      this.totalBlocks = totalBlocks;
      this.sampleRate = sampleRate;
      this.decodeTimeMs = decodeTimeMs;
      this.metadata = metadata;
    }

    /** Returns the decode time as a fraction of the audio duration (lower is faster). */
    public double realtimeFactor() {
      double durationSeconds = (double) totalBlocks / sampleRate;
      return durationSeconds > 0 ? (decodeTimeMs / 1000.0) / durationSeconds : 0;
    }
  }

  private ApeTestUtil() {}

  /** Fully decodes {@code file} and returns the MD5 of the emitted PCM. */
  public static DecodeResult decodeToMd5(File file) throws IOException {
    try (FileReader reader = new FileReader(file)) {
      ApeExtractor extractor = new ApeExtractor();
      CollectingOutput output = new CollectingOutput(/* collectData= */ false);
      try {
        extractor.init(output);
        long startMs = android.os.SystemClock.elapsedRealtime();
        runToEnd(extractor, reader, output, /* stopAfterBytes= */ Long.MAX_VALUE);
        long decodeTimeMs = android.os.SystemClock.elapsedRealtime() - startMs;
        Format format = Objects.requireNonNull(output.format);
        return new DecodeResult(
            output.md5Hex(), output.totalBytes / output.blockAlign(), format.sampleRate,
            decodeTimeMs, format.metadata);
      } finally {
        extractor.release();
      }
    }
  }

  /** Opens a file, initializes the decoder far enough to emit the format, and releases it. */
  public static void openAndClose(File file) throws IOException {
    try (FileReader reader = new FileReader(file)) {
      ApeExtractor extractor = new ApeExtractor();
      CollectingOutput output = new CollectingOutput(/* collectData= */ false);
      try {
        extractor.init(output);
        ExtractorInput input = reader.inputAt(0, /* limitBytes= */ Long.MAX_VALUE);
        PositionHolder positionHolder = new PositionHolder();
        // Run until the first PCM chunk is produced, following seek requests.
        while (output.totalBytes == 0) {
          int result = extractor.read(input, positionHolder);
          if (result == Extractor.RESULT_SEEK) {
            input = reader.inputAt(positionHolder.position, Long.MAX_VALUE);
          } else if (result == Extractor.RESULT_END_OF_INPUT) {
            break;
          }
        }
      } finally {
        extractor.release();
      }
    }
  }

  /** Opens a file for repeated seek-and-read probes. */
  public static SeekSession openForSeeking(File file) throws IOException {
    return new SeekSession(file);
  }

  /** A persistent extractor session supporting random seeks. */
  public static final class SeekSession implements AutoCloseable {
    private final FileReader reader;
    private final ApeExtractor extractor;
    private final CollectingOutput output;

    /* package */ SeekSession(File file) throws IOException {
      reader = new FileReader(file);
      extractor = new ApeExtractor();
      output = new CollectingOutput(/* collectData= */ true);
      extractor.init(output);
      // Run until format and seek map are known.
      ExtractorInput input = reader.inputAt(0, Long.MAX_VALUE);
      PositionHolder positionHolder = new PositionHolder();
      while (output.seekMap == null || output.format == null || output.totalBytes == 0) {
        int result = extractor.read(input, positionHolder);
        if (result == Extractor.RESULT_SEEK) {
          input = reader.inputAt(positionHolder.position, Long.MAX_VALUE);
        } else if (result == Extractor.RESULT_END_OF_INPUT) {
          break;
        }
      }
    }

    public int blockAlign() {
      return output.blockAlign();
    }

    /** Returns the stream length in blocks, derived from the reported duration and sample rate. */
    public long totalBlocks() {
      Format format = Objects.requireNonNull(output.format);
      SeekMap seekMap = Objects.requireNonNull(output.seekMap);
      return seekMap.getDurationUs() * format.sampleRate / C.MICROS_PER_SECOND;
    }

    /** Result of a seek-and-read probe. */
    public static final class SeekResult {
      /** The decoded PCM starting at {@link #landingBlock}. */
      public final byte[] data;

      /**
       * The block the extractor lands on for the requested time. Seeking is time-based, so the
       * target block first becomes a microsecond timestamp and then a block index again; the two
       * floor divisions may land one block below the requested one.
       */
      public final long landingBlock;

      /* package */ SeekResult(byte[] data, long landingBlock) {
        this.data = data;
        this.landingBlock = landingBlock;
      }
    }

    /** Seeks to the time of {@code targetBlock} and returns up to {@code maxBlocks} blocks. */
    public SeekResult seekAndRead(long targetBlock, int maxBlocks, long totalBlocks)
        throws IOException {
      Format format = Objects.requireNonNull(output.format);
      SeekMap seekMap = Objects.requireNonNull(output.seekMap);
      long timeUs = targetBlock * C.MICROS_PER_SECOND / format.sampleRate;
      long landingBlock =
          androidx.media3.common.util.Util.constrainValue(
              timeUs * format.sampleRate / C.MICROS_PER_SECOND, 0, totalBlocks - 1);
      SeekMap.SeekPoints seekPoints = seekMap.getSeekPoints(timeUs);

      extractor.seek(seekPoints.first.position, timeUs);
      output.resetCollection();

      ExtractorInput input = reader.inputAt(seekPoints.first.position, Long.MAX_VALUE);
      PositionHolder positionHolder = new PositionHolder();
      long wantedBytes = (long) maxBlocks * blockAlign();
      while (output.collectedBytes() < wantedBytes) {
        int result = extractor.read(input, positionHolder);
        if (result == Extractor.RESULT_SEEK) {
          input = reader.inputAt(positionHolder.position, Long.MAX_VALUE);
        } else if (result == Extractor.RESULT_END_OF_INPUT) {
          break;
        }
      }
      byte[] collected = output.collectedData();
      int limit = (int) Math.min(collected.length, wantedBytes);
      byte[] resultData = new byte[limit];
      System.arraycopy(collected, 0, resultData, 0, limit);
      return new SeekResult(resultData, landingBlock);
    }

    @Override
    public void close() throws IOException {
      extractor.release();
      reader.close();
    }
  }

  private static void runToEnd(
      Extractor extractor, FileReader reader, CollectingOutput output, long stopAfterBytes)
      throws IOException {
    ExtractorInput input = reader.inputAt(0, Long.MAX_VALUE);
    PositionHolder positionHolder = new PositionHolder();
    while (output.totalBytes < stopAfterBytes) {
      int result = extractor.read(input, positionHolder);
      if (result == Extractor.RESULT_SEEK) {
        input = reader.inputAt(positionHolder.position, Long.MAX_VALUE);
      } else if (result == Extractor.RESULT_END_OF_INPUT) {
        return;
      }
    }
  }

  /** {@link DataReader} over a {@link RandomAccessFile}, building fresh inputs per position. */
  private static final class FileReader implements AutoCloseable {
    private final RandomAccessFile file;
    private final long length;

    FileReader(File f) throws IOException {
      file = new RandomAccessFile(f, "r");
      length = file.length();
    }

    ExtractorInput inputAt(long position, long limitBytes) throws IOException {
      file.seek(position);
      DataReader dataReader =
          (target, offset, maxLength) -> {
            int read = file.read(target, offset, maxLength);
            return read == -1 ? C.RESULT_END_OF_INPUT : read;
          };
      return new DefaultExtractorInput(dataReader, position, length);
    }

    @Override
    public void close() throws IOException {
      file.close();
    }
  }

  /** Collects format, seek map and PCM output; hashes all sample data. */
  private static final class CollectingOutput implements ExtractorOutput, TrackOutput {
    @Nullable Format format;
    @Nullable SeekMap seekMap;
    long totalBytes;

    private final boolean collectData;
    private final MessageDigest digest;
    private final ByteArrayOutputStream collected;
    private final byte[] scratch;

    CollectingOutput(boolean collectData) {
      this.collectData = collectData;
      collected = new ByteArrayOutputStream();
      scratch = new byte[64 * 1024];
      try {
        digest = MessageDigest.getInstance("MD5");
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException(e);
      }
    }

    int blockAlign() {
      Format format = Objects.requireNonNull(this.format);
      int bytesPerSample;
      switch (format.pcmEncoding) {
        case C.ENCODING_PCM_8BIT:
          bytesPerSample = 1;
          break;
        case C.ENCODING_PCM_16BIT:
          bytesPerSample = 2;
          break;
        case C.ENCODING_PCM_24BIT:
          bytesPerSample = 3;
          break;
        case C.ENCODING_PCM_32BIT:
        case C.ENCODING_PCM_FLOAT:
          bytesPerSample = 4;
          break;
        default:
          throw new IllegalStateException("unexpected encoding " + format.pcmEncoding);
      }
      return bytesPerSample * format.channelCount;
    }

    String md5Hex() {
      StringBuilder sb = new StringBuilder();
      for (byte b : digest.digest()) {
        sb.append(String.format(Locale.ROOT, "%02X", b));
      }
      return sb.toString();
    }

    void resetCollection() {
      collected.reset();
    }

    long collectedBytes() {
      return collected.size();
    }

    byte[] collectedData() {
      return collected.toByteArray();
    }

    // ExtractorOutput.

    @Override
    public TrackOutput track(int id, int type) {
      return this;
    }

    @Override
    public void endTracks() {}

    @Override
    public void seekMap(SeekMap seekMap) {
      this.seekMap = seekMap;
    }

    // TrackOutput.

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
        long timeUs, int flags, int size, int offset, @Nullable CryptoData cryptoData) {}

    private void consume(byte[] data, int offset, int length) {
      digest.update(data, offset, length);
      totalBytes += length;
      if (collectData) {
        collected.write(data, offset, length);
      }
    }
  }
}
