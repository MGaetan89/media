/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import android.os.ConditionVariable;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.NullableType;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Utility {@link Player.Listener} for testing. */
public final class PlayerTestListener implements Player.Listener, AnalyticsListener {

  private final ConditionVariable playerIdle;
  private final ConditionVariable playerReady;
  private final ConditionVariable playerEnded;
  private final ConditionVariable firstFrameRendered;
  private final AtomicReference<@NullableType PlaybackException> playbackException;
  private final long testTimeoutMs;
  private @MonotonicNonNull DecoderCounters decoderCounters;

  /**
   * Creates a new instance.
   *
   * @param testTimeoutMs The timeout value in milliseconds for which {@link
   *     #waitUntilPlayerReady()} and {@link #waitUntilPlayerEnded()} waits.
   */
  public PlayerTestListener(long testTimeoutMs) {
    playerIdle = new ConditionVariable();
    playerReady = new ConditionVariable();
    playerEnded = new ConditionVariable();
    firstFrameRendered = new ConditionVariable();
    playbackException = new AtomicReference<>();
    this.testTimeoutMs = testTimeoutMs;
  }

  /** Waits until the {@link Player player} is {@linkplain Player#STATE_IDLE idle}. */
  public void waitUntilPlayerIdle() throws PlaybackException, TimeoutException {
    waitOrThrow(playerIdle);
  }

  /** Waits until the {@link Player player} is {@linkplain Player#STATE_READY ready}. */
  public void waitUntilPlayerReady() throws TimeoutException, PlaybackException {
    waitOrThrow(playerReady);
  }

  /** Waits until the {@link Player player} is {@linkplain Player#STATE_ENDED ended}. */
  public void waitUntilPlayerEnded() throws PlaybackException, TimeoutException {
    waitOrThrow(playerEnded);
  }

  /**
   * Waits until the {@link Player player} {@linkplain Player.Listener#onRenderedFirstFrame()
   * renders the first frame}.
   */
  public void waitUntilFirstFrameRendered() throws PlaybackException, TimeoutException {
    waitOrThrow(firstFrameRendered);
  }

  /**
   * Returns the {@link DecoderCounters} from {@link AnalyticsListener#onVideoEnabled(EventTime,
   * DecoderCounters)}, {@code null} if not available.
   */
  @Nullable
  public DecoderCounters getDecoderCounters() {
    return decoderCounters;
  }

  // Player.Listener methods

  @Override
  public void onPlaybackStateChanged(int playbackState) {
    if (playbackState == Player.STATE_IDLE) {
      playerIdle.open();
    } else if (playbackState == Player.STATE_READY) {
      playerReady.open();
    } else if (playbackState == Player.STATE_ENDED) {
      playerEnded.open();
    }
  }

  @Override
  public void onRenderedFirstFrame() {
    firstFrameRendered.open();
  }

  @Override
  public void onPlayerError(PlaybackException error) {
    playbackException.set(error);
    playerIdle.open();
    playerReady.open();
    playerEnded.open();
    firstFrameRendered.open();
  }

  // AnalyticsListener methods

  @Override
  public void onVideoEnabled(EventTime eventTime, DecoderCounters decoderCounters) {
    this.decoderCounters = decoderCounters;
  }

  public void resetStatus() {
    playerIdle.close();
    playerReady.close();
    playerEnded.close();
    firstFrameRendered.close();
    playbackException.set(null);
  }

  // Internal methods

  private void waitOrThrow(ConditionVariable conditionVariable)
      throws TimeoutException, PlaybackException {
    maybeThrowPlaybackException();
    boolean conditionVariableTimedOut = !conditionVariable.block(testTimeoutMs);
    maybeThrowPlaybackException();
    if (conditionVariableTimedOut) {
      throw new TimeoutException();
    }
  }

  private void maybeThrowPlaybackException() throws PlaybackException {
    @Nullable PlaybackException playbackException = this.playbackException.get();
    if (playbackException != null) {
      throw playbackException;
    }
  }
}
