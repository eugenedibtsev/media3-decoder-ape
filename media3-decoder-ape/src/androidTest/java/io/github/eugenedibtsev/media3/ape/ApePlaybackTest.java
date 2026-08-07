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
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * End-to-end smoke test: real ExoPlayer playback of an APE file through {@link ApeExtractor},
 * including a mid-playback seek.
 */
@RunWith(AndroidJUnit4.class)
public final class ApePlaybackTest {

  private static final File TEST_FILE =
      new File(ApeTestUtil.corpusDir(), "gen-1399-normal-44k-16b-2ch.ape");
  private static final long PLAY_MS = 5_000;

  @Test
  public void playAndSeek_withRealPlayer() throws Exception {
    assumeTrue("corpus not pushed", TEST_FILE.exists());
    Context context = ApplicationProvider.getApplicationContext();

    HandlerThread playbackThread = new HandlerThread("ape-playback-test");
    playbackThread.start();
    Handler handler = new Handler(playbackThread.getLooper());

    AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
    AtomicReference<PlaybackException> errorRef = new AtomicReference<>();
    AtomicLong positionAfterPlayRef = new AtomicLong();
    AtomicLong positionAfterSeekRef = new AtomicLong();
    CountDownLatch playedLatch = new CountDownLatch(1);
    CountDownLatch seekedLatch = new CountDownLatch(1);

    handler.post(
        () -> {
          ExtractorsFactory extractorsFactory =
              () -> new Extractor[] {new ApeExtractor()};
          ExoPlayer player =
              new ExoPlayer.Builder(context)
                  .setLooper(Looper.myLooper())
                  .setMediaSourceFactory(new DefaultMediaSourceFactory(context, extractorsFactory))
                  .build();
          playerRef.set(player);
          player.addListener(
              new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                  errorRef.set(error);
                  playedLatch.countDown();
                  seekedLatch.countDown();
                }
              });
          player.setMediaItem(MediaItem.fromUri(Uri.fromFile(TEST_FILE)));
          player.prepare();
          player.play();
        });

    // Let it play for a while, then check the clock advanced.
    Thread.sleep(PLAY_MS);
    handler.post(
        () -> {
          ExoPlayer player = playerRef.get();
          positionAfterPlayRef.set(player.getCurrentPosition());
          playedLatch.countDown();
        });
    assertThat(playedLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(errorRef.get()).isNull();
    assertWithMessage("playback position after %s ms", PLAY_MS)
        .that(positionAfterPlayRef.get())
        .isAtLeast(PLAY_MS / 2);

    // Seek beyond the played region -- 60% of the track, whatever its length -- and verify
    // playback resumes there. The bundled file is short; a corpus of your own may be far longer.
    AtomicLong seekTargetRef = new AtomicLong();
    handler.post(
        () -> {
          ExoPlayer player = playerRef.get();
          long durationMs = player.getDuration();
          long target = Math.max(PLAY_MS + 1_000, durationMs * 6 / 10);
          seekTargetRef.set(target);
          player.seekTo(target);
        });
    Thread.sleep(3_000);
    handler.post(
        () -> {
          ExoPlayer player = playerRef.get();
          positionAfterSeekRef.set(player.getCurrentPosition());
          seekedLatch.countDown();
        });
    assertThat(seekedLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(errorRef.get()).isNull();
    assertWithMessage("position after seeking to %s ms", seekTargetRef.get())
        .that(positionAfterSeekRef.get())
        .isAtLeast(seekTargetRef.get());

    CountDownLatch releasedLatch = new CountDownLatch(1);
    handler.post(
        () -> {
          playerRef.get().release();
          releasedLatch.countDown();
        });
    assertThat(releasedLatch.await(10, TimeUnit.SECONDS)).isTrue();
    playbackThread.quitSafely();
  }
}
