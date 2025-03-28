/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.usToMs;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.util.Pair;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.upstream.Allocator;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Concatenates multiple {@link MediaSource MediaSources}, combining everything in one single {@link
 * Timeline.Window}.
 *
 * <p>This class can be used under the following conditions:
 *
 * <ul>
 *   <li>All sources must be non-empty.
 *   <li>The {@link Timeline.Window#getPositionInFirstPeriodUs() period offset} in all windows
 *       (except for the first one) must not change during the lifetime of this media source. This
 *       excludes, for example, live streams with moving live windows or dynamic updates to the
 *       clipping start time of a {@link ClippingMediaSource}.
 * </ul>
 */
@UnstableApi
public final class ConcatenatingMediaSource2 extends CompositeMediaSource<Integer> {

  /** A builder for {@link ConcatenatingMediaSource2} instances. */
  public static final class Builder {

    private final ImmutableList.Builder<MediaSourceHolder> mediaSourceHoldersBuilder;

    private int index;
    @Nullable private MediaItem mediaItem;
    @Nullable private MediaSource.Factory mediaSourceFactory;

    /** Creates the builder. */
    public Builder() {
      mediaSourceHoldersBuilder = ImmutableList.builder();
    }

    /**
     * Instructs the builder to use a {@link DefaultMediaSourceFactory} to convert {@link MediaItem
     * MediaItems} to {@link MediaSource MediaSources} for all future calls to {@link
     * #add(MediaItem)} or {@link #add(MediaItem, long)}.
     *
     * @param context A {@link Context}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder useDefaultMediaSourceFactory(Context context) {
      return setMediaSourceFactory(new DefaultMediaSourceFactory(context));
    }

    /**
     * Sets a {@link MediaSource.Factory} that is used to convert {@link MediaItem MediaItems} to
     * {@link MediaSource MediaSources} for all future calls to {@link #add(MediaItem)} or {@link
     * #add(MediaItem, long)}.
     *
     * @param mediaSourceFactory A {@link MediaSource.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
      this.mediaSourceFactory = checkNotNull(mediaSourceFactory);
      return this;
    }

    /**
     * Sets the {@link MediaItem} to be used for the concatenated media source.
     *
     * <p>This {@link MediaItem} will be used as {@link Timeline.Window#mediaItem} for the
     * concatenated source and will be returned by {@link Player#getCurrentMediaItem()}.
     *
     * <p>The default is {@code MediaItem.fromUri(Uri.EMPTY)}.
     *
     * @param mediaItem The {@link MediaItem}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMediaItem(MediaItem mediaItem) {
      this.mediaItem = mediaItem;
      return this;
    }

    /**
     * Adds a {@link MediaItem} to the concatenation.
     *
     * <p>{@link #useDefaultMediaSourceFactory(Context)} or {@link
     * #setMediaSourceFactory(MediaSource.Factory)} must be called before this method.
     *
     * <p>This method must not be used with media items for progressive media that can't provide
     * their duration with their first {@link Timeline} update. Use {@link #add(MediaItem, long)}
     * instead.
     *
     * @param mediaItem The {@link MediaItem}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder add(MediaItem mediaItem) {
      return add(mediaItem, /* initialPlaceholderDurationMs= */ C.TIME_UNSET);
    }

    /**
     * Adds a {@link MediaItem} to the concatenation and specifies its initial placeholder duration
     * used while the actual duration is still unknown.
     *
     * <p>{@link #useDefaultMediaSourceFactory(Context)} or {@link
     * #setMediaSourceFactory(MediaSource.Factory)} must be called before this method.
     *
     * <p>Setting a placeholder duration is required for media items for progressive media that
     * can't provide their duration with their first {@link Timeline} update. It may also be used
     * for other items to make the duration known immediately.
     *
     * @param mediaItem The {@link MediaItem}.
     * @param initialPlaceholderDurationMs The initial placeholder duration in milliseconds used
     *     while the actual duration is still unknown, or {@link C#TIME_UNSET} to not define one.
     *     The placeholder duration is used for every {@link Timeline.Window} defined by {@link
     *     Timeline} of the {@link MediaItem}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder add(MediaItem mediaItem, long initialPlaceholderDurationMs) {
      checkNotNull(mediaItem);
      if (initialPlaceholderDurationMs == C.TIME_UNSET
          && mediaItem.clippingConfiguration.endPositionMs != C.TIME_END_OF_SOURCE) {
        // If the item is going to be clipped, we can provide a placeholder duration automatically.
        initialPlaceholderDurationMs =
            usToMs(
                mediaItem.clippingConfiguration.endPositionUs
                    - mediaItem.clippingConfiguration.startPositionUs);
      }
      checkStateNotNull(
          mediaSourceFactory,
          "Must use useDefaultMediaSourceFactory or setMediaSourceFactory first.");
      return add(mediaSourceFactory.createMediaSource(mediaItem), initialPlaceholderDurationMs);
    }

    /**
     * Adds a {@link MediaSource} to the concatenation.
     *
     * <p>This method must not be used for sources like {@link ProgressiveMediaSource} that can't
     * provide their duration with their first {@link Timeline} update. Use {@link #add(MediaSource,
     * long)} instead.
     *
     * @param mediaSource The {@link MediaSource}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder add(MediaSource mediaSource) {
      return add(mediaSource, /* initialPlaceholderDurationMs= */ C.TIME_UNSET);
    }

    /**
     * Adds a {@link MediaSource} to the concatenation and specifies its initial placeholder
     * duration used while the actual duration is still unknown.
     *
     * <p>Setting a placeholder duration is required for sources like {@link ProgressiveMediaSource}
     * that can't provide their duration with their first {@link Timeline} update. It may also be
     * used for other sources to make the duration known immediately.
     *
     * @param mediaSource The {@link MediaSource}.
     * @param initialPlaceholderDurationMs The initial placeholder duration in milliseconds used
     *     while the actual duration is still unknown, or {@link C#TIME_UNSET} to not define one.
     *     The placeholder duration is used for every {@link Timeline.Window} defined by {@link
     *     Timeline} of the {@link MediaSource}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder add(MediaSource mediaSource, long initialPlaceholderDurationMs) {
      checkNotNull(mediaSource);
      checkState(
          !(mediaSource instanceof ProgressiveMediaSource)
              || initialPlaceholderDurationMs != C.TIME_UNSET,
          "Progressive media source must define an initial placeholder duration.");
      mediaSourceHoldersBuilder.add(
          new MediaSourceHolder(mediaSource, index++, Util.msToUs(initialPlaceholderDurationMs)));
      return this;
    }

    /** Builds the concatenating media source. */
    public ConcatenatingMediaSource2 build() {
      checkArgument(index > 0, "Must add at least one source to the concatenation.");
      if (mediaItem == null) {
        mediaItem = MediaItem.fromUri(Uri.EMPTY);
      }
      return new ConcatenatingMediaSource2(mediaItem, mediaSourceHoldersBuilder.build());
    }
  }

  private static final int MSG_UPDATE_TIMELINE = 1;

  private final ImmutableList<MediaSourceHolder> mediaSourceHolders;
  private final IdentityHashMap<MediaPeriod, MediaSourceHolder> mediaSourceByMediaPeriod;

  @Nullable private Handler playbackThreadHandler;
  private boolean timelineUpdateScheduled;

  @GuardedBy("this")
  private MediaItem mediaItem;

  private ConcatenatingMediaSource2(
      MediaItem mediaItem, ImmutableList<MediaSourceHolder> mediaSourceHolders) {
    this.mediaItem = mediaItem;
    this.mediaSourceHolders = mediaSourceHolders;
    mediaSourceByMediaPeriod = new IdentityHashMap<>();
  }

  @Nullable
  @Override
  public Timeline getInitialTimeline() {
    return maybeCreateConcatenatedTimeline();
  }

  @Override
  public synchronized MediaItem getMediaItem() {
    return mediaItem;
  }

  @Override
  public boolean canUpdateMediaItem(MediaItem mediaItem) {
    return true;
  }

  @Override
  public synchronized void updateMediaItem(MediaItem mediaItem) {
    this.mediaItem = mediaItem;
  }

  @Override
  protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
    super.prepareSourceInternal(mediaTransferListener);
    playbackThreadHandler = new Handler(/* callback= */ this::handleMessage);
    for (int i = 0; i < mediaSourceHolders.size(); i++) {
      MediaSourceHolder holder = mediaSourceHolders.get(i);
      prepareChildSource(/* id= */ i, holder.mediaSource);
    }
    scheduleTimelineUpdate();
  }

  @SuppressWarnings("MissingSuperCall")
  @Override
  protected void enableInternal() {
    // Suppress enabling all child sources here as they can be lazily enabled when creating periods.
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    int holderIndex = getChildIndex(id.periodUid);
    MediaSourceHolder holder = mediaSourceHolders.get(holderIndex);
    MediaPeriodId childMediaPeriodId =
        id.copyWithPeriodUid(getChildPeriodUid(id.periodUid))
            .copyWithWindowSequenceNumber(
                getChildWindowSequenceNumber(
                    id.windowSequenceNumber, mediaSourceHolders.size(), holder.index));
    enableChildSource(holder.index);
    holder.activeMediaPeriods++;
    long timeOffsetUs =
        id.isAd()
            ? 0
            : checkNotNull(holder.periodTimeOffsetsByUid.get(childMediaPeriodId.periodUid));
    MediaPeriod mediaPeriod =
        new TimeOffsetMediaPeriod(
            holder.mediaSource.createPeriod(
                childMediaPeriodId, allocator, startPositionUs - timeOffsetUs),
            timeOffsetUs);
    mediaSourceByMediaPeriod.put(mediaPeriod, holder);
    disableUnusedMediaSources();
    return mediaPeriod;
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    MediaSourceHolder holder = checkNotNull(mediaSourceByMediaPeriod.remove(mediaPeriod));
    holder.mediaSource.releasePeriod(((TimeOffsetMediaPeriod) mediaPeriod).getWrappedMediaPeriod());
    holder.activeMediaPeriods--;
    if (!mediaSourceByMediaPeriod.isEmpty()) {
      disableUnusedMediaSources();
    }
  }

  @Override
  protected void releaseSourceInternal() {
    super.releaseSourceInternal();
    if (playbackThreadHandler != null) {
      playbackThreadHandler.removeCallbacksAndMessages(null);
      playbackThreadHandler = null;
    }
    timelineUpdateScheduled = false;
  }

  @Override
  protected void onChildSourceInfoRefreshed(
      Integer childSourceId, MediaSource mediaSource, Timeline newTimeline) {
    scheduleTimelineUpdate();
  }

  @Override
  @Nullable
  protected MediaPeriodId getMediaPeriodIdForChildMediaPeriodId(
      Integer childSourceId, MediaPeriodId mediaPeriodId) {
    int childIndex =
        getChildIndexFromChildWindowSequenceNumber(
            mediaPeriodId.windowSequenceNumber, mediaSourceHolders.size());
    if (childSourceId != childIndex) {
      // Ensure the reported media period id has the expected window sequence number. Otherwise it
      // does not belong to this child source.
      return null;
    }
    long windowSequenceNumber =
        getWindowSequenceNumberFromChildWindowSequenceNumber(
            mediaPeriodId.windowSequenceNumber, mediaSourceHolders.size());
    Object periodUid = getPeriodUid(childSourceId, mediaPeriodId.periodUid);
    return mediaPeriodId
        .copyWithPeriodUid(periodUid)
        .copyWithWindowSequenceNumber(windowSequenceNumber);
  }

  @Override
  protected int getWindowIndexForChildWindowIndex(Integer childSourceId, int windowIndex) {
    return 0;
  }

  @Override
  protected long getMediaTimeForChildMediaTime(
      Integer childSourceId, long mediaTimeMs, @Nullable MediaPeriodId mediaPeriodId) {
    if (mediaTimeMs == C.TIME_UNSET || mediaPeriodId == null || mediaPeriodId.isAd()) {
      return mediaTimeMs;
    }
    @Nullable
    Long timeOffsetUs =
        mediaSourceHolders.get(childSourceId).periodTimeOffsetsByUid.get(mediaPeriodId.periodUid);
    if (timeOffsetUs == null) {
      return mediaTimeMs;
    }
    return mediaTimeMs + usToMs(timeOffsetUs);
  }

  private boolean handleMessage(Message msg) {
    if (msg.what == MSG_UPDATE_TIMELINE) {
      updateTimeline();
    }
    return true;
  }

  private void scheduleTimelineUpdate() {
    if (!timelineUpdateScheduled) {
      checkNotNull(playbackThreadHandler).obtainMessage(MSG_UPDATE_TIMELINE).sendToTarget();
      timelineUpdateScheduled = true;
    }
  }

  private void updateTimeline() {
    timelineUpdateScheduled = false;
    @Nullable ConcatenatedTimeline timeline = maybeCreateConcatenatedTimeline();
    if (timeline != null) {
      refreshSourceInfo(timeline);
    }
  }

  private void disableUnusedMediaSources() {
    for (int i = 0; i < mediaSourceHolders.size(); i++) {
      MediaSourceHolder holder = mediaSourceHolders.get(i);
      if (holder.activeMediaPeriods == 0) {
        disableChildSource(holder.index);
      }
    }
  }

  @Nullable
  private ConcatenatedTimeline maybeCreateConcatenatedTimeline() {
    Timeline.Window window = new Timeline.Window();
    Timeline.Period period = new Timeline.Period();
    ImmutableList.Builder<Timeline> timelinesBuilder = ImmutableList.builder();
    ImmutableList.Builder<Integer> firstPeriodIndicesBuilder = ImmutableList.builder();
    ImmutableList.Builder<Long> periodOffsetsInWindowUsBuilder = ImmutableList.builder();
    int periodCount = 0;
    boolean isSeekable = true;
    boolean isDynamic = false;
    long durationUs = 0;
    long defaultPositionUs = 0;
    long nextPeriodOffsetInWindowUs = 0;
    boolean manifestsAreIdentical = true;
    boolean hasInitialManifest = false;
    @Nullable Object initialManifest = null;
    int mediaSourceHoldersCount = mediaSourceHolders.size();
    for (int i = 0; i < mediaSourceHoldersCount; i++) {
      MediaSourceHolder holder = mediaSourceHolders.get(i);
      Timeline timeline = holder.mediaSource.getTimeline();
      checkArgument(!timeline.isEmpty(), "Can't concatenate empty child Timeline.");
      timelinesBuilder.add(timeline);
      firstPeriodIndicesBuilder.add(periodCount);
      int periodCountInMediaSourceHolder = timeline.getPeriodCount();
      periodCount += periodCountInMediaSourceHolder;
      for (int j = 0; j < timeline.getWindowCount(); j++) {
        timeline.getWindow(/* windowIndex= */ j, window);
        if (!hasInitialManifest) {
          initialManifest = window.manifest;
          hasInitialManifest = true;
        }
        manifestsAreIdentical =
            manifestsAreIdentical && Objects.equals(initialManifest, window.manifest);

        long windowDurationUs = window.durationUs;
        if (windowDurationUs == C.TIME_UNSET) {
          if (holder.initialPlaceholderDurationUs == C.TIME_UNSET) {
            // Source duration isn't known yet and we have no placeholder duration.
            return null;
          }
          windowDurationUs = holder.initialPlaceholderDurationUs;
        }
        durationUs += windowDurationUs;
        if (holder.index == 0 && j == 0) {
          defaultPositionUs = window.defaultPositionUs;
          nextPeriodOffsetInWindowUs = -window.positionInFirstPeriodUs;
        }
        // Assume placeholder windows are seekable to not prevent seeking in other periods.
        isSeekable &= window.isSeekable || window.isPlaceholder;
        isDynamic |= window.isDynamic;

        for (int k = window.firstPeriodIndex; k <= window.lastPeriodIndex; k++) {
          periodOffsetsInWindowUsBuilder.add(nextPeriodOffsetInWindowUs);
          timeline.getPeriod(/* periodIndex= */ k, period, /* setIds= */ true);
          long periodDurationUs = period.durationUs;
          if (periodDurationUs == C.TIME_UNSET) {
            checkArgument(
                window.firstPeriodIndex == window.lastPeriodIndex,
                "Can't apply placeholder duration to multiple periods with unknown duration "
                    + "in a single window.");
            periodDurationUs = windowDurationUs + window.positionInFirstPeriodUs;
          }
          long timeOffsetUsForPeriod = 0;
          boolean isFirstPeriodInNonFirstWindow =
              k == window.firstPeriodIndex && (holder.index != 0 || j != 0);
          if (isFirstPeriodInNonFirstWindow && periodDurationUs != C.TIME_UNSET) {
            timeOffsetUsForPeriod = -window.positionInFirstPeriodUs;
            periodDurationUs += timeOffsetUsForPeriod;
          }
          Object periodUid = checkNotNull(period.uid);
          checkArgument(
              holder.activeMediaPeriods == 0
                  || !holder.periodTimeOffsetsByUid.containsKey(periodUid)
                  || holder.periodTimeOffsetsByUid.get(periodUid).equals(timeOffsetUsForPeriod),
              "Can't handle windows with changing offset in first period.");
          holder.periodTimeOffsetsByUid.put(periodUid, timeOffsetUsForPeriod);
          nextPeriodOffsetInWindowUs += periodDurationUs;
        }
      }
    }
    return new ConcatenatedTimeline(
        getMediaItem(),
        timelinesBuilder.build(),
        firstPeriodIndicesBuilder.build(),
        periodOffsetsInWindowUsBuilder.build(),
        isSeekable,
        isDynamic,
        durationUs,
        defaultPositionUs,
        manifestsAreIdentical ? initialManifest : null);
  }

  /**
   * Returns the period uid for the concatenated source from the child index and child period uid.
   */
  private static Object getPeriodUid(int childIndex, Object childPeriodUid) {
    return Pair.create(childIndex, childPeriodUid);
  }

  /** Returns the child index from the period uid of the concatenated source. */
  @SuppressWarnings("unchecked")
  private static int getChildIndex(Object periodUid) {
    return ((Pair<Integer, Object>) periodUid).first;
  }

  /** Returns the uid of child period from the period uid of the concatenated source. */
  @SuppressWarnings("unchecked")
  private static Object getChildPeriodUid(Object periodUid) {
    return ((Pair<Integer, Object>) periodUid).second;
  }

  /** Returns the window sequence number used for the child source. */
  private static long getChildWindowSequenceNumber(
      long windowSequenceNumber, int childCount, int childIndex) {
    return windowSequenceNumber * childCount + childIndex;
  }

  /** Returns the index of the child source from a child window sequence number. */
  private static int getChildIndexFromChildWindowSequenceNumber(
      long childWindowSequenceNumber, int childCount) {
    return (int) (childWindowSequenceNumber % childCount);
  }

  /** Returns the concatenated window sequence number from a child window sequence number. */
  private static long getWindowSequenceNumberFromChildWindowSequenceNumber(
      long childWindowSequenceNumber, int childCount) {
    return childWindowSequenceNumber / childCount;
  }

  /* package */ static final class MediaSourceHolder {

    public final MaskingMediaSource mediaSource;
    public final int index;
    public final long initialPlaceholderDurationUs;
    public final HashMap<Object, Long> periodTimeOffsetsByUid;

    public int activeMediaPeriods;

    public MediaSourceHolder(
        MediaSource mediaSource, int index, long initialPlaceholderDurationUs) {
      this.mediaSource = new MaskingMediaSource(mediaSource, /* useLazyPreparation= */ false);
      this.index = index;
      this.initialPlaceholderDurationUs = initialPlaceholderDurationUs;
      this.periodTimeOffsetsByUid = new HashMap<>();
    }
  }

  private static final class ConcatenatedTimeline extends Timeline {

    private final MediaItem mediaItem;
    private final ImmutableList<Timeline> timelines;
    private final ImmutableList<Integer> firstPeriodIndices;
    private final ImmutableList<Long> periodOffsetsInWindowUs;
    private final boolean isSeekable;
    private final boolean isDynamic;
    private final long durationUs;
    private final long defaultPositionUs;
    @Nullable private final Object manifest;

    public ConcatenatedTimeline(
        MediaItem mediaItem,
        ImmutableList<Timeline> timelines,
        ImmutableList<Integer> firstPeriodIndices,
        ImmutableList<Long> periodOffsetsInWindowUs,
        boolean isSeekable,
        boolean isDynamic,
        long durationUs,
        long defaultPositionUs,
        @Nullable Object manifest) {
      this.mediaItem = mediaItem;
      this.timelines = timelines;
      this.firstPeriodIndices = firstPeriodIndices;
      this.periodOffsetsInWindowUs = periodOffsetsInWindowUs;
      this.isSeekable = isSeekable;
      this.isDynamic = isDynamic;
      this.durationUs = durationUs;
      this.defaultPositionUs = defaultPositionUs;
      this.manifest = manifest;
    }

    @Override
    public int getWindowCount() {
      return 1;
    }

    @Override
    public int getPeriodCount() {
      return periodOffsetsInWindowUs.size();
    }

    @Override
    public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
      return window.set(
          Window.SINGLE_WINDOW_UID,
          mediaItem,
          manifest,
          /* presentationStartTimeMs= */ C.TIME_UNSET,
          /* windowStartTimeMs= */ C.TIME_UNSET,
          /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
          isSeekable,
          isDynamic,
          /* liveConfiguration= */ null,
          defaultPositionUs,
          durationUs,
          /* firstPeriodIndex= */ 0,
          /* lastPeriodIndex= */ getPeriodCount() - 1,
          /* positionInFirstPeriodUs= */ -periodOffsetsInWindowUs.get(0));
    }

    @Override
    public Period getPeriodByUid(Object periodUid, Period period) {
      int childIndex = getChildIndex(periodUid);
      Object childPeriodUid = getChildPeriodUid(periodUid);
      Timeline timeline = timelines.get(childIndex);
      int periodIndex =
          firstPeriodIndices.get(childIndex) + timeline.getIndexOfPeriod(childPeriodUid);
      timeline.getPeriodByUid(childPeriodUid, period);
      period.windowIndex = 0;
      period.positionInWindowUs = periodOffsetsInWindowUs.get(periodIndex);
      period.durationUs = getPeriodDurationUs(period, periodIndex);
      period.uid = periodUid;
      return period;
    }

    @Override
    public Period getPeriod(int periodIndex, Period period, boolean setIds) {
      int childIndex = getChildIndexByPeriodIndex(periodIndex);
      int firstPeriodIndexInChild = firstPeriodIndices.get(childIndex);
      timelines.get(childIndex).getPeriod(periodIndex - firstPeriodIndexInChild, period, setIds);
      period.windowIndex = 0;
      period.positionInWindowUs = periodOffsetsInWindowUs.get(periodIndex);
      period.durationUs = getPeriodDurationUs(period, periodIndex);
      if (setIds) {
        period.uid = getPeriodUid(childIndex, checkNotNull(period.uid));
      }
      return period;
    }

    @Override
    public int getIndexOfPeriod(Object uid) {
      if (!(uid instanceof Pair) || !(((Pair<?, ?>) uid).first instanceof Integer)) {
        return C.INDEX_UNSET;
      }
      int childIndex = getChildIndex(uid);
      Object periodUid = getChildPeriodUid(uid);
      int periodIndexInChild = timelines.get(childIndex).getIndexOfPeriod(periodUid);
      return periodIndexInChild == C.INDEX_UNSET
          ? C.INDEX_UNSET
          : firstPeriodIndices.get(childIndex) + periodIndexInChild;
    }

    @Override
    public Object getUidOfPeriod(int periodIndex) {
      int childIndex = getChildIndexByPeriodIndex(periodIndex);
      int firstPeriodIndexInChild = firstPeriodIndices.get(childIndex);
      Object periodUidInChild =
          timelines.get(childIndex).getUidOfPeriod(periodIndex - firstPeriodIndexInChild);
      return getPeriodUid(childIndex, periodUidInChild);
    }

    private int getChildIndexByPeriodIndex(int periodIndex) {
      return Util.binarySearchFloor(
          firstPeriodIndices, periodIndex + 1, /* inclusive= */ false, /* stayInBounds= */ false);
    }

    private long getPeriodDurationUs(Timeline.Period childPeriod, int periodIndex) {
      // Keep unset duration, but force duration to match offset of next period otherwise.
      if (childPeriod.durationUs == C.TIME_UNSET) {
        return C.TIME_UNSET;
      }
      long periodStartTimeInWindowUs = periodOffsetsInWindowUs.get(periodIndex);
      long periodEndTimeInWindowUs =
          periodIndex == periodOffsetsInWindowUs.size() - 1
              ? durationUs
              : periodOffsetsInWindowUs.get(periodIndex + 1);
      return periodEndTimeInWindowUs - periodStartTimeInWindowUs;
    }
  }
}
