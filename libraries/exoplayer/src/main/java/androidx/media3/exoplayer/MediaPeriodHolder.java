/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.exoplayer.MediaPeriodQueue.areDurationsCompatible;
import static java.lang.Math.max;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.exoplayer.source.ClippingMediaPeriod;
import androidx.media3.exoplayer.source.EmptySampleStream;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.Allocator;
import java.io.IOException;

/** Holds a {@link MediaPeriod} with information required to play it as part of a timeline. */
/* package */ final class MediaPeriodHolder {

  private static final String TAG = "MediaPeriodHolder";

  /** The {@link MediaPeriod} wrapped by this class. */
  public final MediaPeriod mediaPeriod;

  /** The unique timeline period identifier the media period belongs to. */
  public final Object uid;

  /**
   * The sample streams for each renderer associated with this period. May contain null elements.
   */
  public final @NullableType SampleStream[] sampleStreams;

  /** The target buffer duration to preload. */
  public final long targetPreloadBufferDurationUs;

  /** Whether {@link #prepare(MediaPeriod.Callback, long)} has been called. */
  public boolean prepareCalled;

  /** Whether the media period has finished preparing. */
  public boolean prepared;

  /** Whether any of the tracks of this media period are enabled. */
  public boolean hasEnabledTracks;

  /** {@link MediaPeriodInfo} about this media period. */
  public MediaPeriodInfo info;

  /**
   * Whether all renderers are in the correct state for this {@link #mediaPeriod}.
   *
   * <p>Renderers that are needed must have been enabled with the {@link #sampleStreams} for this
   * {@link #mediaPeriod}. This means either {@link Renderer#enable(RendererConfiguration, Format[],
   * SampleStream, long, boolean, boolean, long, long, MediaPeriodId)} or {@link
   * Renderer#replaceStream(Format[], SampleStream, long, long, MediaPeriodId)} has been called.
   *
   * <p>Renderers that are not needed must have been {@link Renderer#disable() disabled}.
   */
  public boolean allRenderersInCorrectState;

  private final boolean[] mayRetainStreamFlags;
  private final RendererCapabilities[] rendererCapabilities;
  private final TrackSelector trackSelector;
  private final MediaSourceList mediaSourceList;

  @Nullable private MediaPeriodHolder next;
  private TrackGroupArray trackGroups;
  private TrackSelectorResult trackSelectorResult;
  private long rendererPositionOffsetUs;

  /**
   * Creates a new holder with information required to play it as part of a timeline.
   *
   * @param rendererCapabilities The renderer capabilities.
   * @param rendererPositionOffsetUs The renderer time of the start of the period, in microseconds.
   * @param trackSelector The track selector.
   * @param allocator The allocator.
   * @param mediaSourceList The playlist.
   * @param info Information used to identify this media period in its timeline period.
   * @param emptyTrackSelectorResult A {@link TrackSelectorResult} with empty selections for each
   *     renderer.
   */
  public MediaPeriodHolder(
      RendererCapabilities[] rendererCapabilities,
      long rendererPositionOffsetUs,
      TrackSelector trackSelector,
      Allocator allocator,
      MediaSourceList mediaSourceList,
      MediaPeriodInfo info,
      TrackSelectorResult emptyTrackSelectorResult,
      long targetPreloadBufferDurationUs) {
    this.rendererCapabilities = rendererCapabilities;
    this.rendererPositionOffsetUs = rendererPositionOffsetUs;
    this.trackSelector = trackSelector;
    this.mediaSourceList = mediaSourceList;
    this.uid = info.id.periodUid;
    this.info = info;
    this.targetPreloadBufferDurationUs = targetPreloadBufferDurationUs;
    this.trackGroups = TrackGroupArray.EMPTY;
    this.trackSelectorResult = emptyTrackSelectorResult;
    sampleStreams = new SampleStream[rendererCapabilities.length];
    mayRetainStreamFlags = new boolean[rendererCapabilities.length];
    mediaPeriod =
        createMediaPeriod(
            info.id,
            mediaSourceList,
            allocator,
            info.startPositionUs,
            info.endPositionUs,
            info.isPrecededByTransitionFromSameStream);
  }

  /**
   * Converts time relative to the start of the period to the respective renderer time using {@link
   * #getRendererOffset()}, in microseconds.
   */
  public long toRendererTime(long periodTimeUs) {
    return periodTimeUs + getRendererOffset();
  }

  /**
   * Converts renderer time to the respective time relative to the start of the period using {@link
   * #getRendererOffset()}, in microseconds.
   */
  public long toPeriodTime(long rendererTimeUs) {
    return rendererTimeUs - getRendererOffset();
  }

  /** Returns the renderer time of the start of the period, in microseconds. */
  public long getRendererOffset() {
    return rendererPositionOffsetUs;
  }

  /**
   * Sets the renderer time of the start of the period, in microseconds.
   *
   * @param rendererPositionOffsetUs The new renderer position offset, in microseconds.
   */
  public void setRendererOffset(long rendererPositionOffsetUs) {
    this.rendererPositionOffsetUs = rendererPositionOffsetUs;
  }

  /** Returns start position of period in renderer time. */
  public long getStartPositionRendererTime() {
    return info.startPositionUs + rendererPositionOffsetUs;
  }

  /** Returns whether the period is fully buffered. */
  public boolean isFullyBuffered() {
    return prepared
        && (!hasEnabledTracks || mediaPeriod.getBufferedPositionUs() == C.TIME_END_OF_SOURCE);
  }

  /** Returns whether the period is fully preloaded. */
  public boolean isFullyPreloaded() {
    return prepared
        && (isFullyBuffered()
            || getBufferedPositionUs() - info.startPositionUs >= targetPreloadBufferDurationUs);
  }

  /**
   * Returns the buffered position in microseconds. If the period is buffered to the end, then the
   * period duration is returned.
   *
   * @return The buffered position in microseconds.
   */
  public long getBufferedPositionUs() {
    if (!prepared) {
      return info.startPositionUs;
    }
    long bufferedPositionUs =
        hasEnabledTracks ? mediaPeriod.getBufferedPositionUs() : C.TIME_END_OF_SOURCE;
    return bufferedPositionUs == C.TIME_END_OF_SOURCE ? info.durationUs : bufferedPositionUs;
  }

  /**
   * Returns the next load time relative to the start of the period, or {@link C#TIME_END_OF_SOURCE}
   * if loading has finished.
   */
  public long getNextLoadPositionUs() {
    return !prepared ? 0 : mediaPeriod.getNextLoadPositionUs();
  }

  /**
   * Handles period preparation.
   *
   * @param playbackSpeed The current factor by which playback is sped up.
   * @param timeline The current {@link Timeline}.
   * @param playWhenReady The current value of whether playback should proceed when ready.
   * @throws ExoPlaybackException If an error occurs during track selection.
   */
  public void handlePrepared(float playbackSpeed, Timeline timeline, boolean playWhenReady)
      throws ExoPlaybackException {
    prepared = true;
    trackGroups = mediaPeriod.getTrackGroups();
    TrackSelectorResult selectorResult = selectTracks(playbackSpeed, timeline, playWhenReady);
    long requestedStartPositionUs = info.startPositionUs;
    if (info.durationUs != C.TIME_UNSET && requestedStartPositionUs >= info.durationUs) {
      // Make sure start position doesn't exceed period duration.
      requestedStartPositionUs = max(0, info.durationUs - 1);
    }
    long newStartPositionUs =
        applyTrackSelection(
            selectorResult, requestedStartPositionUs, /* forceRecreateStreams= */ false);
    rendererPositionOffsetUs += info.startPositionUs - newStartPositionUs;
    info = info.copyWithStartPositionUs(newStartPositionUs);
  }

  /**
   * Reevaluates the buffer of the media period at the given renderer position. Should only be
   * called if this is the loading media period.
   *
   * @param rendererPositionUs The playing position in renderer time, in microseconds.
   */
  public void reevaluateBuffer(long rendererPositionUs) {
    checkState(isLoadingMediaPeriod());
    if (prepared) {
      mediaPeriod.reevaluateBuffer(toPeriodTime(rendererPositionUs));
    }
  }

  /**
   * Continues loading the media period with the given {@link LoadingInfo}. Should only be called if
   * this is the loading media period.
   *
   * @param loadingInfo The {@link LoadingInfo} about the current player state relevant to this load
   *     request.
   */
  public void continueLoading(LoadingInfo loadingInfo) {
    checkState(isLoadingMediaPeriod());
    mediaPeriod.continueLoading(loadingInfo);
  }

  /**
   * Selects tracks for the period. Must only be called if {@link #prepared} is {@code true}.
   *
   * <p>The new track selection needs to be applied with {@link
   * #applyTrackSelection(TrackSelectorResult, long, boolean)} before taking effect.
   *
   * @param playbackSpeed The current factor by which playback is sped up.
   * @param timeline The current {@link Timeline}.
   * @param playWhenReady The current value of whether playback should proceed when ready.
   * @return The {@link TrackSelectorResult}.
   * @throws ExoPlaybackException If an error occurs during track selection.
   */
  public TrackSelectorResult selectTracks(
      float playbackSpeed, Timeline timeline, boolean playWhenReady) throws ExoPlaybackException {
    TrackSelectorResult selectorResult =
        trackSelector.selectTracks(rendererCapabilities, getTrackGroups(), info.id, timeline);
    for (int i = 0; i < selectorResult.length; i++) {
      if (selectorResult.isRendererEnabled(i)) {
        checkState(
            selectorResult.selections[i] != null
                || rendererCapabilities[i].getTrackType() == C.TRACK_TYPE_NONE);
      } else {
        checkState(selectorResult.selections[i] == null);
      }
    }
    for (ExoTrackSelection trackSelection : selectorResult.selections) {
      if (trackSelection != null) {
        trackSelection.onPlaybackSpeed(playbackSpeed);
        trackSelection.onPlayWhenReadyChanged(playWhenReady);
      }
    }
    return selectorResult;
  }

  /**
   * Applies a {@link TrackSelectorResult} to the period.
   *
   * @param trackSelectorResult The {@link TrackSelectorResult} to apply.
   * @param positionUs The position relative to the start of the period at which to apply the new
   *     track selections, in microseconds.
   * @param forceRecreateStreams Whether all streams are forced to be recreated.
   * @return The actual position relative to the start of the period at which the new track
   *     selections are applied.
   */
  public long applyTrackSelection(
      TrackSelectorResult trackSelectorResult, long positionUs, boolean forceRecreateStreams) {
    return applyTrackSelection(
        trackSelectorResult,
        positionUs,
        forceRecreateStreams,
        new boolean[rendererCapabilities.length]);
  }

  /**
   * Applies a {@link TrackSelectorResult} to the period.
   *
   * @param newTrackSelectorResult The {@link TrackSelectorResult} to apply.
   * @param positionUs The position relative to the start of the period at which to apply the new
   *     track selections, in microseconds.
   * @param forceRecreateStreams Whether all streams are forced to be recreated.
   * @param streamResetFlags Will be populated to indicate which streams have been reset or were
   *     newly created.
   * @return The actual position relative to the start of the period at which the new track
   *     selections are applied.
   */
  public long applyTrackSelection(
      TrackSelectorResult newTrackSelectorResult,
      long positionUs,
      boolean forceRecreateStreams,
      boolean[] streamResetFlags) {
    for (int i = 0; i < newTrackSelectorResult.length; i++) {
      mayRetainStreamFlags[i] =
          !forceRecreateStreams && newTrackSelectorResult.isEquivalent(trackSelectorResult, i);
    }

    // Undo the effect of previous call to associate no-sample renderers with empty tracks
    // so the mediaPeriod receives back whatever it sent us before.
    disassociateNoSampleRenderersWithEmptySampleStream(sampleStreams);
    disableTrackSelectionsInResult();
    trackSelectorResult = newTrackSelectorResult;
    enableTrackSelectionsInResult();
    // Disable streams on the period and get new streams for updated/newly-enabled tracks.
    positionUs =
        mediaPeriod.selectTracks(
            newTrackSelectorResult.selections,
            mayRetainStreamFlags,
            sampleStreams,
            streamResetFlags,
            positionUs);
    associateNoSampleRenderersWithEmptySampleStream(sampleStreams);

    // Update whether we have enabled tracks and check that the expected streams are non-null.
    hasEnabledTracks = false;
    for (int i = 0; i < sampleStreams.length; i++) {
      if (sampleStreams[i] != null) {
        checkState(newTrackSelectorResult.isRendererEnabled(i));
        // hasEnabledTracks should be true only when non-empty streams exists.
        if (rendererCapabilities[i].getTrackType() != C.TRACK_TYPE_NONE) {
          hasEnabledTracks = true;
        }
      } else {
        checkState(newTrackSelectorResult.selections[i] == null);
      }
    }
    return positionUs;
  }

  /** Releases the media period. No other method should be called after the release. */
  public void release() {
    disableTrackSelectionsInResult();
    releaseMediaPeriod(mediaSourceList, mediaPeriod);
  }

  /**
   * Sets the next media period holder in the queue.
   *
   * @param nextMediaPeriodHolder The next holder, or null if this will be the new loading media
   *     period holder at the end of the queue.
   */
  public void setNext(@Nullable MediaPeriodHolder nextMediaPeriodHolder) {
    if (nextMediaPeriodHolder == next) {
      return;
    }
    disableTrackSelectionsInResult();
    next = nextMediaPeriodHolder;
    enableTrackSelectionsInResult();
  }

  /**
   * Returns the next media period holder in the queue, or null if this is the last media period
   * (and thus the loading media period).
   */
  @Nullable
  public MediaPeriodHolder getNext() {
    return next;
  }

  /** Returns the {@link TrackGroupArray} exposed by this media period. */
  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  /** Returns the {@link TrackSelectorResult} which is currently applied. */
  public TrackSelectorResult getTrackSelectorResult() {
    return trackSelectorResult;
  }

  /** Updates the clipping to {@link MediaPeriodInfo#endPositionUs} if required. */
  public void updateClipping() {
    if (mediaPeriod instanceof ClippingMediaPeriod) {
      long endPositionUs =
          info.endPositionUs == C.TIME_UNSET ? C.TIME_END_OF_SOURCE : info.endPositionUs;
      ((ClippingMediaPeriod) mediaPeriod).updateClipping(/* startUs= */ 0, endPositionUs);
    }
  }

  /**
   * Returns whether the media period has encountered an error that prevents it from being prepared
   * or reading data.
   */
  public boolean hasLoadingError() {
    try {
      if (!prepared) {
        mediaPeriod.maybeThrowPrepareError();
      } else {
        for (SampleStream sampleStream : sampleStreams) {
          if (sampleStream != null) {
            sampleStream.maybeThrowError();
          }
        }
      }
    } catch (IOException e) {
      return true;
    }
    return false;
  }

  private void enableTrackSelectionsInResult() {
    if (!isLoadingMediaPeriod()) {
      return;
    }
    for (int i = 0; i < trackSelectorResult.length; i++) {
      boolean rendererEnabled = trackSelectorResult.isRendererEnabled(i);
      ExoTrackSelection trackSelection = trackSelectorResult.selections[i];
      if (rendererEnabled && trackSelection != null) {
        trackSelection.enable();
      }
    }
  }

  private void disableTrackSelectionsInResult() {
    if (!isLoadingMediaPeriod()) {
      return;
    }
    for (int i = 0; i < trackSelectorResult.length; i++) {
      boolean rendererEnabled = trackSelectorResult.isRendererEnabled(i);
      ExoTrackSelection trackSelection = trackSelectorResult.selections[i];
      if (rendererEnabled && trackSelection != null) {
        trackSelection.disable();
      }
    }
  }

  /**
   * For each renderer of type {@link C#TRACK_TYPE_NONE}, we will remove the {@link
   * EmptySampleStream} that was associated with it.
   */
  private void disassociateNoSampleRenderersWithEmptySampleStream(
      @NullableType SampleStream[] sampleStreams) {
    for (int i = 0; i < rendererCapabilities.length; i++) {
      if (rendererCapabilities[i].getTrackType() == C.TRACK_TYPE_NONE) {
        sampleStreams[i] = null;
      }
    }
  }

  /**
   * For each renderer of type {@link C#TRACK_TYPE_NONE} that was enabled, we will associate it with
   * an {@link EmptySampleStream}.
   */
  private void associateNoSampleRenderersWithEmptySampleStream(
      @NullableType SampleStream[] sampleStreams) {
    for (int i = 0; i < rendererCapabilities.length; i++) {
      if (rendererCapabilities[i].getTrackType() == C.TRACK_TYPE_NONE
          && trackSelectorResult.isRendererEnabled(i)) {
        sampleStreams[i] = new EmptySampleStream();
      }
    }
  }

  private boolean isLoadingMediaPeriod() {
    return next == null;
  }

  /** Returns a media period corresponding to the given {@code id}. */
  private static MediaPeriod createMediaPeriod(
      MediaPeriodId id,
      MediaSourceList mediaSourceList,
      Allocator allocator,
      long startPositionUs,
      long endPositionUs,
      boolean isPrecededByTransitionFromSameStream) {
    MediaPeriod mediaPeriod = mediaSourceList.createPeriod(id, allocator, startPositionUs);
    if (endPositionUs != C.TIME_UNSET) {
      mediaPeriod =
          new ClippingMediaPeriod(
              mediaPeriod,
              /* enableInitialDiscontinuity= */ !isPrecededByTransitionFromSameStream,
              /* startUs= */ 0,
              endPositionUs);
    }
    return mediaPeriod;
  }

  /** Releases the given {@code mediaPeriod}, logging and suppressing any errors. */
  private static void releaseMediaPeriod(MediaSourceList mediaSourceList, MediaPeriod mediaPeriod) {
    try {
      if (mediaPeriod instanceof ClippingMediaPeriod) {
        mediaSourceList.releasePeriod(((ClippingMediaPeriod) mediaPeriod).mediaPeriod);
      } else {
        mediaSourceList.releasePeriod(mediaPeriod);
      }
    } catch (RuntimeException e) {
      // There's nothing we can do.
      Log.e(TAG, "Period release failed.", e);
    }
  }

  public boolean canBeUsedForMediaPeriodInfo(MediaPeriodInfo info) {
    return areDurationsCompatible(this.info.durationUs, info.durationUs)
        && this.info.startPositionUs == info.startPositionUs
        && this.info.id.equals(info.id);
  }

  public void prepare(MediaPeriod.Callback callback, long startPositionUs) {
    prepareCalled = true;
    mediaPeriod.prepare(callback, startPositionUs);
  }

  /* package */ interface Factory {
    MediaPeriodHolder create(MediaPeriodInfo info, long rendererPositionOffsetUs);
  }
}
