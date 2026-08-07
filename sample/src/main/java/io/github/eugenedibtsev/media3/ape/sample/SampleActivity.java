// Copyright (C) 2026 The media3-decoder-ape Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package io.github.eugenedibtsev.media3.ape.sample;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import io.github.eugenedibtsev.media3.ape.ApeStreamMetadata;
import io.github.eugenedibtsev.media3.ape.ApeSupport;
import java.io.EOFException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * The README quick start as a runnable app, doubling as a minimal APE file viewer: opening an
 * {@code .ape} file (from the in-app picker or straight from a file manager, the way a browser
 * opens an mp3) shows the container's version, compression level, format and average bitrate, and
 * plays it through a stock ExoPlayer built with {@link ApeSupport#extractorsFactory()}.
 *
 * <p>The parameters come from {@link ApeStreamMetadata#parse}, the same parser the extractor uses,
 * fed with the container prefix read from the content URI.
 */
@UnstableApi
public final class SampleActivity extends Activity {

  private static final int REQUEST_OPEN_FILE = 1;

  /** ID3v2 junk longer than this is not searched for the container magic. */
  private static final int MAX_HEADER_SEARCH_BYTES = 1 << 20;

  private ExoPlayer player;
  private TextView infoView;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    player =
        new ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(this, ApeSupport.extractorsFactory()))
            .build();

    Button openButton = new Button(this);
    openButton.setText("Open audio file");
    openButton.setOnClickListener(
        v -> {
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          // .ape has no MIME type most pickers know, so do not filter by one.
          intent.setType("*/*");
          startActivityForResult(intent, REQUEST_OPEN_FILE);
        });

    infoView = new TextView(this);
    infoView.setTypeface(android.graphics.Typeface.MONOSPACE);
    infoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
    int pad = (int) (12 * getResources().getDisplayMetrics().density);
    infoView.setPadding(pad, pad, pad, pad);
    infoView.setText("No file open.");

    PlayerView playerView = new PlayerView(this);
    playerView.setPlayer(player);

    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    // targetSdk 35+ draws edge-to-edge; keep the controls out from under the system bars.
    layout.setFitsSystemWindows(true);
    layout.addView(
        openButton,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    layout.addView(
        infoView,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    layout.addView(
        playerView,
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    setContentView(layout);

    // Opened from a file manager: the file to view arrives in the launch intent.
    Uri viewed = getIntent() != null ? getIntent().getData() : null;
    if (viewed != null) {
      open(viewed);
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    Uri uri = intent.getData();
    if (uri != null) {
      open(uri);
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_OPEN_FILE || resultCode != RESULT_OK || data == null) {
      return;
    }
    Uri uri = data.getData();
    if (uri != null) {
      open(uri);
    }
  }

  private void open(Uri uri) {
    player.setMediaItem(MediaItem.fromUri(uri));
    player.prepare();
    player.play();

    infoView.setText("Reading header…");
    new Thread(
            () -> {
              String info;
              try {
                info = describe(uri);
              } catch (Exception e) {
                info = "Not a readable APE container: " + e.getMessage();
              }
              String text = info;
              mainHandler.post(() -> infoView.setText(text));
            },
            "ape-header-read")
        .start();
  }

  /** Reads the container prefix from {@code uri} and formats its parameters. */
  private String describe(Uri uri) throws Exception {
    long fileLength = C.LENGTH_UNSET;
    AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
    if (afd != null) {
      fileLength = afd.getLength();
      afd.close();
    }

    byte[] head;
    try (InputStream in = getContentResolver().openInputStream(uri)) {
      if (in == null) {
        throw new EOFException("no stream");
      }
      head = readUpTo(in, MAX_HEADER_SEARCH_BYTES);
    }

    // The container may sit behind ID3v2 junk; find the 'MAC ' / 'MAC F' magic.
    int junk = indexOfMagic(head);
    if (junk < 0) {
      throw new EOFException("no APE magic in the first " + head.length + " bytes");
    }
    byte[] fromMagic = Arrays.copyOfRange(head, junk, head.length);
    int prefixSize = ApeStreamMetadata.peekPrefixSize(fromMagic, fromMagic.length);
    if (prefixSize == C.LENGTH_UNSET || prefixSize > fromMagic.length) {
      throw new EOFException("container prefix exceeds " + MAX_HEADER_SEARCH_BYTES + " bytes");
    }
    ApeStreamMetadata metadata =
        ApeStreamMetadata.parse(Arrays.copyOfRange(fromMagic, 0, prefixSize), junk);

    long durationUs = metadata.getDurationUs();
    StringBuilder sb = new StringBuilder();
    sb.append(String.format(Locale.ROOT, "Monkey's Audio %.2f", metadata.version / 1000.0));
    sb.append("  ").append(levelName(metadata.compressionLevel)).append('\n');
    sb.append(
        String.format(
            Locale.ROOT,
            "%d Hz  %d-bit%s  %s\n",
            metadata.sampleRate,
            metadata.bitsPerSample,
            metadata.isFloatingPoint() ? " float" : "",
            channelsName(metadata.channelCount)));
    sb.append(formatDuration(durationUs));
    if (fileLength != C.LENGTH_UNSET && durationUs > 0) {
      long bitrate = fileLength * 8 * C.MICROS_PER_SECOND / durationUs / 1000;
      long pcmBitrate =
          (long) metadata.sampleRate * metadata.bitsPerSample * metadata.channelCount / 1000;
      sb.append(
          String.format(
              Locale.ROOT, "  %d kbps (%.0f%% of PCM)", bitrate, 100.0 * bitrate / pcmBitrate));
    }
    return sb.toString();
  }

  private static byte[] readUpTo(InputStream in, int maxBytes) throws Exception {
    byte[] buffer = new byte[maxBytes];
    int filled = 0;
    while (filled < maxBytes) {
      int read = in.read(buffer, filled, maxBytes - filled);
      if (read == -1) {
        break;
      }
      filled += read;
    }
    return filled == maxBytes ? buffer : Arrays.copyOf(buffer, filled);
  }

  private static int indexOfMagic(byte[] data) {
    for (int i = 0; i + 6 <= data.length; i++) {
      if (data[i] == 'M'
          && data[i + 1] == 'A'
          && data[i + 2] == 'C'
          && (data[i + 3] == ' ' || data[i + 3] == 'F')) {
        int version = (data[i + 4] & 0xFF) | ((data[i + 5] & 0xFF) << 8);
        if (version >= 1000 && version <= 9999) {
          return i;
        }
      }
    }
    return -1;
  }

  private static String levelName(int compressionLevel) {
    switch (compressionLevel) {
      case 1000:
        return "Fast";
      case 2000:
        return "Normal";
      case 3000:
        return "High";
      case 4000:
        return "Extra High";
      case 5000:
        return "Insane";
      default:
        return "level " + compressionLevel;
    }
  }

  private static String channelsName(int channelCount) {
    switch (channelCount) {
      case 1:
        return "mono";
      case 2:
        return "stereo";
      default:
        return channelCount + " channels";
    }
  }

  private static String formatDuration(long durationUs) {
    long totalSeconds = durationUs / C.MICROS_PER_SECOND;
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
  }

  @Override
  protected void onDestroy() {
    player.release();
    super.onDestroy();
  }
}
