/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.android.exoplayer.hls;

import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.SampleHolder;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.TrackInfo;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.upstream.HttpDataSource;
import com.google.android.exoplayer.upstream.Loader;
import com.google.android.exoplayer.upstream.Loader.Loadable;
import com.google.android.exoplayer.util.Assertions;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.LinkedList;

/**
 * A {@link SampleSource} for HLS streams.
 */
public class HlsSampleSource implements SampleSource, Loader.Callback {

  private static final String TAG = "HlsSampleSource";

  private static final long BUFFER_DURATION_US = 20000000;
  private static final int NO_RESET_PENDING = -1;
  private static final int MAX_LOADABLE_ATTEMPTS = 5;

  private final HlsChunkSource chunkSource;
  private final LinkedList<TsExtractor> extractors;
  private final boolean frameAccurateSeeking;

  private int remainingReleaseCount;
  private boolean prepared;
  private int trackCount;
  private int enabledTrackCount;
  private boolean[] trackEnabledStates;
  private boolean[] pendingDiscontinuities;
  private TrackInfo[] trackInfos;
  private MediaFormat[] downstreamMediaFormats;

  private long downstreamPositionUs;
  private long lastSeekPositionUs;
  private long pendingResetPositionUs;

  private TsChunk previousTsLoadable;
  private HlsChunk currentLoadable;
  private boolean loadingFinished;

  private Loader loader;
  private IOException currentLoadableException;
  private boolean currentLoadableExceptionFatal;
  private int currentLoadableExceptionCount;
  private long currentLoadableExceptionTimestamp;

  public HlsSampleSource(HlsChunkSource chunkSource,
      boolean frameAccurateSeeking, int downstreamRendererCount) {
    this.chunkSource = chunkSource;
    this.frameAccurateSeeking = frameAccurateSeeking;
    this.remainingReleaseCount = downstreamRendererCount;
    extractors = new LinkedList<TsExtractor>();
  }

  @Override
  public boolean prepare() throws IOException {
    if (prepared) {
      return true;
    }
    if (loader == null) {
      loader = new Loader("Loader:HLS");
    }
    continueBufferingInternal();
    if (extractors.isEmpty()) {
      return false;
    }
    TsExtractor extractor = extractors.getFirst();
    if (extractor.isPrepared()) {
      trackCount = extractor.getTrackCount();
      trackEnabledStates = new boolean[trackCount];
      pendingDiscontinuities = new boolean[trackCount];
      downstreamMediaFormats = new MediaFormat[trackCount];
      trackInfos = new TrackInfo[trackCount];
      for (int i = 0; i < trackCount; i++) {
        MediaFormat format = extractor.getFormat(i);
        trackInfos[i] = new TrackInfo(format.mimeType, chunkSource.getDurationUs());
      }
      prepared = true;
    }
    return prepared;
  }

  @Override
  public int getTrackCount() {
    Assertions.checkState(prepared);
    return trackCount;
  }

  @Override
  public TrackInfo getTrackInfo(int track) {
    Assertions.checkState(prepared);
    return trackInfos[track];
  }

  @Override
  public void enable(int track, long positionUs) {
    Assertions.checkState(prepared);
    Assertions.checkState(!trackEnabledStates[track]);
    enabledTrackCount++;
    trackEnabledStates[track] = true;
    downstreamMediaFormats[track] = null;
    if (enabledTrackCount == 1) {
      seekToUs(positionUs);
    }
  }

  @Override
  public void disable(int track) {
    Assertions.checkState(prepared);
    Assertions.checkState(trackEnabledStates[track]);
    enabledTrackCount--;
    trackEnabledStates[track] = false;
    pendingDiscontinuities[track] = false;
    if (enabledTrackCount == 0) {
      if (loader.isLoading()) {
        loader.cancelLoading();
      } else {
        discardExtractors();
        clearCurrentLoadable();
        previousTsLoadable = null;
      }
    }
  }

  @Override
  public boolean continueBuffering(long playbackPositionUs) throws IOException {
    Assertions.checkState(prepared);
    Assertions.checkState(enabledTrackCount > 0);
    downstreamPositionUs = playbackPositionUs;
    return continueBufferingInternal();
  }

  private boolean continueBufferingInternal() throws IOException {
    maybeStartLoading();
    if (isPendingReset() || extractors.isEmpty()) {
      return false;
    }
    boolean haveSamples = extractors.getFirst().hasSamples();
    if (!haveSamples) {
      maybeThrow();
    }
    return haveSamples;
  }

  @Override
  public int readData(int track, long playbackPositionUs, MediaFormatHolder formatHolder,
      SampleHolder sampleHolder, boolean onlyReadDiscontinuity) throws IOException {
    Assertions.checkState(prepared);
    downstreamPositionUs = playbackPositionUs;

    if (pendingDiscontinuities[track]) {
      pendingDiscontinuities[track] = false;
      return DISCONTINUITY_READ;
    }

    if (onlyReadDiscontinuity || isPendingReset() || extractors.isEmpty()) {
      maybeThrow();
      return NOTHING_READ;
    }

    TsExtractor extractor = extractors.getFirst();
    while (extractors.size() > 1 && !extractor.hasSamples()) {
      // We're finished reading from the extractor for all tracks, and so can discard it.
      extractors.removeFirst().clear();
      extractor = extractors.getFirst();
    }

    if (extractors.size() > 1) {
      // If there's more than one extractor, attempt to configure a seamless splice from the
      // current one to the next one.
      extractor.configureSpliceTo(extractors.get(1));
    }

    int extractorIndex = 0;
    while (extractors.size() > extractorIndex + 1 && !extractor.hasSamples(track)) {
      // We're finished reading from the extractor for this particular track, so advance to the
      // next one for the current read.
      extractor = extractors.get(++extractorIndex);
    }

    if (!extractor.isPrepared()) {
      maybeThrow();
      return NOTHING_READ;
    }

    MediaFormat mediaFormat = extractor.getFormat(track);
    if (mediaFormat != null && !mediaFormat.equals(downstreamMediaFormats[track], true)) {
      chunkSource.getMaxVideoDimensions(mediaFormat);
      formatHolder.format = mediaFormat;
      downstreamMediaFormats[track] = mediaFormat;
      return FORMAT_READ;
    }

    if (extractor.getSample(track, sampleHolder)) {
      sampleHolder.decodeOnly = frameAccurateSeeking && sampleHolder.timeUs < lastSeekPositionUs;
      return SAMPLE_READ;
    }

    if (loadingFinished) {
      return END_OF_STREAM;
    }

    maybeThrow();
    return NOTHING_READ;
  }

  @Override
  public void seekToUs(long positionUs) {
    Assertions.checkState(prepared);
    Assertions.checkState(enabledTrackCount > 0);
    lastSeekPositionUs = positionUs;
    if (pendingResetPositionUs == positionUs || downstreamPositionUs == positionUs) {
      downstreamPositionUs = positionUs;
      return;
    }
    downstreamPositionUs = positionUs;
    for (int i = 0; i < pendingDiscontinuities.length; i++) {
      pendingDiscontinuities[i] = true;
    }
    restartFrom(positionUs);
  }

  @Override
  public long getBufferedPositionUs() {
    Assertions.checkState(prepared);
    Assertions.checkState(enabledTrackCount > 0);
    if (isPendingReset()) {
      return pendingResetPositionUs;
    } else if (loadingFinished) {
      return TrackRenderer.END_OF_TRACK_US;
    } else {
      return extractors.getLast().getLargestSampleTimestamp();
    }
  }

  @Override
  public void release() {
    Assertions.checkState(remainingReleaseCount > 0);
    if (--remainingReleaseCount == 0 && loader != null) {
      loader.release();
      loader = null;
    }
  }

  @Override
  public void onLoadCompleted(Loadable loadable) {
    try {
      currentLoadable.consume();
    } catch (IOException e) {
      currentLoadableException = e;
      currentLoadableExceptionCount++;
      currentLoadableExceptionTimestamp = SystemClock.elapsedRealtime();
      currentLoadableExceptionFatal = true;
      Log.w(TAG, String.format("onLoadCompleted error: count=%d fatal=%b (%s)",
          currentLoadableExceptionCount, currentLoadableExceptionFatal, e.getMessage()));
    } finally {
      if (isTsChunk(currentLoadable)) {
        TsChunk tsChunk = (TsChunk) loadable;
        loadingFinished = tsChunk.isLastChunk();
      }
      if (!currentLoadableExceptionFatal) {
        clearCurrentLoadable();
      }
      maybeStartLoading();
    }
  }

  @Override
  public void onLoadCanceled(Loadable loadable) {
    clearCurrentLoadable();
    if (enabledTrackCount > 0) {
      restartFrom(pendingResetPositionUs);
    } else {
      previousTsLoadable = null;
    }
  }

  @Override
  public void onLoadError(Loadable loadable, IOException e) {
    currentLoadableException = e;
    currentLoadableExceptionCount++;
    currentLoadableExceptionTimestamp = SystemClock.elapsedRealtime();
    if (e instanceof HttpDataSource.InvalidResponseCodeException) {
      HttpDataSource.InvalidResponseCodeException exception =
          (HttpDataSource.InvalidResponseCodeException) e;
      HlsChunk hlsChunk = (HlsChunk)loadable;
      if (isTsChunk(hlsChunk) && exception.responseCode == 404 && chunkSource.live) {
        // If this is a live stream and we get a 404, we're likely behind the live window.
        Log.d(TAG, String.format("Error 404 loading %s, assuming behind live window",
            hlsChunk.dataSpec.uri));
        // TODO: Double check that this actually works as expected. We want the chunk source
        // to update the playlist and select a new live start position because we missed the
        // train loading the current one. By setting previousTsLoadable to null we should
        // trigger a discont (new extractors) so this could work. Needs testing.
        clearCurrentLoadable();
        previousTsLoadable = null;
      } else {
        Log.d(TAG, String.format("Error 404 loading %s, consider fatal",
            hlsChunk.dataSpec.uri));
        currentLoadableExceptionFatal = true;
      }
    }
    Log.w(TAG, String.format("onLoadError: count=%d fatal=%b (%s)",
        currentLoadableExceptionCount, currentLoadableExceptionFatal, e.getMessage()));
    maybeStartLoading();
  }

  private void maybeThrow() throws IOException {
    if (currentLoadableException != null &&
        (currentLoadableExceptionFatal || currentLoadableExceptionCount >= MAX_LOADABLE_ATTEMPTS)) {
      Log.e(TAG, String.format("Throwing error: count=%d fatal=%b",
          currentLoadableExceptionCount, currentLoadableExceptionFatal));
      throw currentLoadableException;
    }
  }

  private void restartFrom(long positionUs) {
    pendingResetPositionUs = positionUs;
    previousTsLoadable = null;
    loadingFinished = false;
    discardExtractors();
    if (loader.isLoading()) {
      loader.cancelLoading();
    } else {
      clearCurrentLoadable();
      maybeStartLoading();
    }
  }

  private void clearCurrentLoadable() {
    currentLoadable = null;
    currentLoadableException = null;
    currentLoadableExceptionCount = 0;
    currentLoadableExceptionFatal = false;
  }

  private void maybeStartLoading() {
    if (currentLoadableExceptionFatal || loadingFinished) {
      return;
    }

    boolean isBackedOff = currentLoadableException != null;
    if (isBackedOff) {
      long elapsedMillis = SystemClock.elapsedRealtime() - currentLoadableExceptionTimestamp;
      if (elapsedMillis >= getRetryDelayMillis(currentLoadableExceptionCount)) {
        currentLoadableException = null;
        loader.startLoading(currentLoadable, this);
      }
      return;
    }

    boolean bufferFull = !extractors.isEmpty() && (extractors.getLast().getLargestSampleTimestamp()
        - downstreamPositionUs) >= BUFFER_DURATION_US;
    if (loader.isLoading() || bufferFull) {
      return;
    }

    HlsChunk nextLoadable = chunkSource.getChunkOperation(previousTsLoadable,
        pendingResetPositionUs, downstreamPositionUs);
    if (nextLoadable == null) {
      return;
    }

    currentLoadable = nextLoadable;
    if (isTsChunk(currentLoadable)) {
      previousTsLoadable = (TsChunk) currentLoadable;
      if (isPendingReset()) {
        pendingResetPositionUs = NO_RESET_PENDING;
      }
      if (extractors.isEmpty() || extractors.getLast() != previousTsLoadable.extractor) {
        extractors.addLast(previousTsLoadable.extractor);
      }
    }
    loader.startLoading(currentLoadable, this);
  }

  private void discardExtractors() {
    for (int i = 0; i < extractors.size(); i++) {
      extractors.get(i).clear();
    }
    extractors.clear();
  }

  private boolean isTsChunk(HlsChunk chunk) {
    return chunk instanceof TsChunk;
  }

  private boolean isPendingReset() {
    return pendingResetPositionUs != NO_RESET_PENDING;
  }

  private long getRetryDelayMillis(long errorCount) {
    return Math.min((errorCount - 1) * 1000, 5000);
  }

  protected final int usToMs(long timeUs) {
    return (int) (timeUs / 1000);
  }

}
