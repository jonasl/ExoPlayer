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

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.hls.TsExtractor.SamplePool;
import com.google.android.exoplayer.upstream.Aes128DataSource;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.BitArray;
import com.google.android.exoplayer.util.Util;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A temporary test source of HLS chunks.
 * <p>
 * TODO: Figure out whether this should merge with the chunk package, or whether the hls
 * implementation is going to naturally diverge.
 */
public class HlsChunkSource {

  private static final String TAG = "HlsChunkSource";

  private static final float BANDWIDTH_FRACTION = 0.8f;
  private static final long MIN_BUFFER_TO_SWITCH_UP_US = 5000000;
  private static final long MAX_BUFFER_TO_SWITCH_DOWN_US = 15000000;
  private static final long MIN_BUFFER_TO_SWITCH_DOWN_US = 3000000;
  private static final boolean DEBUG_SWITCHING = true; // TODO: set to false.

  private final SamplePool samplePool = new TsExtractor.SamplePool();
  private final DataSource upstreamDataSource;
  private final HlsMediaPlaylistParser mediaPlaylistParser;
  private final Variant[] enabledVariants;
  private final BandwidthMeter bandwidthMeter;
  private final BitArray bitArray;
  private final boolean enableAdaptive;
  private final Uri baseUri;
  private final int maxWidth;
  private final int maxHeight;

  /* package */ final HlsMediaPlaylist[] mediaPlaylists;
  /* package */ final long[] lastMediaPlaylistLoadTimesMs;
  /* package */ boolean live;
  /* package */ long durationUs;

  private int variantIndex;
  private DataSource encryptedDataSource;
  private Uri encryptionKeyUri;
  private String encryptedDataSourceIv;
  private byte[] encryptedDataSourceSecretKey;

  /**
   * @param dataSource A {@link DataSource} suitable for loading the media data.
   * @param masterPlaylist The master playlist.
   * @param variantIndices A subset of variant indices to consider, or null to consider all of the
   *     variants in the master playlist.
   */
  public HlsChunkSource(DataSource dataSource, HlsMasterPlaylist masterPlaylist,
      BandwidthMeter bandwidthMeter, int[] variantIndices, boolean enableAdaptive) {
    this.upstreamDataSource = dataSource;
    this.bandwidthMeter = bandwidthMeter;
    this.enableAdaptive = enableAdaptive;
    baseUri = masterPlaylist.baseUri;
    bitArray = new BitArray();
    mediaPlaylistParser = new HlsMediaPlaylistParser();
    enabledVariants = filterVariants(masterPlaylist, variantIndices);
    lastMediaPlaylistLoadTimesMs = new long[enabledVariants.length];
    mediaPlaylists = new HlsMediaPlaylist[enabledVariants.length];
    int maxWidth = -1;
    int maxHeight = -1;
    // Select the first variant from the master playlist that's enabled.
    long minOriginalVariantIndex = Integer.MAX_VALUE;
    for (int i = 0; i < enabledVariants.length; i++) {
      if (enabledVariants[i].index < minOriginalVariantIndex) {
        minOriginalVariantIndex = enabledVariants[i].index;
        variantIndex = i;
      }
      maxWidth = Math.max(enabledVariants[i].width, maxWidth);
      maxHeight = Math.max(enabledVariants[i].width, maxHeight);
    }
    // TODO: We should allow the default values to be passed through the constructor.
    this.maxWidth = maxWidth > 0 ? maxWidth : 1920;
    this.maxHeight = maxHeight > 0 ? maxHeight : 1080;
  }

  public long getDurationUs() {
    return live ? TrackRenderer.UNKNOWN_TIME_US : durationUs;
  }

  /**
   * Adaptive implementations must set the maximum video dimensions on the supplied
   * {@link MediaFormat}. Other implementations do nothing.
   * <p>
   * Only called when the source is enabled.
   *
   * @param out The {@link MediaFormat} on which the maximum video dimensions should be set.
   */
  public void getMaxVideoDimensions(MediaFormat out) {
    out.setMaxVideoDimensions(maxWidth, maxHeight);
  }

  /**
   * Returns the next {@link HlsChunk} that should be loaded.
   *
   * @param previousTsChunk The previously loaded chunk that the next chunk should follow.
   * @param seekPositionUs If there is no previous chunk, this parameter must specify the seek
   *     position. If there is a previous chunk then this parameter is ignored.
   * @param playbackPositionUs The current playback position.
   * @return The next chunk to load.
   */
  public HlsChunk getChunkOperation(TsChunk previousTsChunk, long seekPositionUs,
      long playbackPositionUs) {

    HlsMediaPlaylist mediaPlaylist = mediaPlaylists[variantIndex];
    if (mediaPlaylist == null ||
        (mediaPlaylist.live && shouldRerequestMediaPlaylist())) {
      return newMediaPlaylistChunk();
    }

    int chunkMediaSequence = 0;
    if (live) {
      if (previousTsChunk == null) {
        chunkMediaSequence = getLiveStartChunkMediaSequence();
      } else {
        // For live nextChunkIndex contains chunk media sequence number.
        chunkMediaSequence = previousTsChunk.nextChunkIndex;
        // If the updated playlist is far ahead and doesn't even have the last chunk from the
        // queue, then try to catch up, skip a few chunks and start as if it was a new playlist.
        if (chunkMediaSequence < mediaPlaylist.mediaSequence) {
          // TODO: Trigger discontinuity in this case.
          chunkMediaSequence = getLiveStartChunkMediaSequence();
        }
      }
    } else {
      // Not live.
      if (previousTsChunk == null) {
        chunkMediaSequence = Util.binarySearchFloor(mediaPlaylist.segments, seekPositionUs, true,
            true) + mediaPlaylist.mediaSequence;
      } else {
        chunkMediaSequence = previousTsChunk.nextChunkIndex;
      }
    }

    if (chunkMediaSequence == -1) {
      // We've reached the end of the stream.
      return null;
    }

    int chunkIndex = chunkMediaSequence - mediaPlaylist.mediaSequence;
    if (chunkIndex >= mediaPlaylist.segments.size()) {
      if (mediaPlaylist.live && shouldRerequestMediaPlaylist()) {
        return newMediaPlaylistChunk();
      } else {
        return null;
      }
    }

    HlsMediaPlaylist.Segment segment = mediaPlaylist.segments.get(chunkIndex);
    Uri chunkUri = Util.getMergedUri(mediaPlaylist.baseUri, segment.url);

    // Check if encryption is specified.
    if (HlsMediaPlaylist.ENCRYPTION_METHOD_AES_128.equals(segment.encryptionMethod)) {
      Uri keyUri = Util.getMergedUri(mediaPlaylist.baseUri, segment.encryptionKeyUri);
      if (!keyUri.equals(encryptionKeyUri)) {
        // Encryption is specified and the key has changed.
        HlsChunk toReturn = newEncryptionKeyChunk(keyUri, segment.encryptionIV);
        return toReturn;
      }
      if (!Util.areEqual(segment.encryptionIV, encryptedDataSourceIv)) {
        initEncryptedDataSource(keyUri, segment.encryptionIV, encryptedDataSourceSecretKey);
      }
    } else {
      clearEncryptedDataSource();
    }

    long startTimeUs;
    boolean splicingIn = previousTsChunk != null && previousTsChunk.splicingOut;
    int nextChunkMediaSequence = chunkMediaSequence + 1;
    if (live) {
      if (previousTsChunk == null) {
        startTimeUs = 0;
      } else {
        startTimeUs = previousTsChunk.endTimeUs;
      }
    } else {
      // Not live.
      startTimeUs = segment.startTimeUs;
    }
    if (!mediaPlaylist.live && chunkIndex == mediaPlaylist.segments.size() - 1) {
      nextChunkMediaSequence = -1;
    }

    long endTimeUs = startTimeUs + (long) (segment.durationSecs * 1000000);

    int currentVariantIndex = variantIndex;
    boolean splicingOut = false;
    if (splicingIn) {
      // Do nothing.
    } else if (enableAdaptive && nextChunkMediaSequence != -1) {
      // TODO: With this logic two segments of a stream are always downloaded at the initial
      // bit rate before we consider switching. In many cases that's too slow, iOS switches
      // already after the first segment. The whole splice in/out business complicates fixing
      // this cleanly, but it really should be fixed.
      int currentBandwidth = (int)(bandwidthMeter.getBitrateEstimate() * BANDWIDTH_FRACTION);
      long bufferedUs = startTimeUs - playbackPositionUs;
      if (previousTsChunk != null && previousTsChunk.isLoadFinished()) {
        maybeSwitchVariantEx(previousTsChunk, currentBandwidth, bufferedUs);
      } else {
        maybeSwitchVariantSimple(currentBandwidth, bufferedUs);
      }
      splicingOut = variantIndex != currentVariantIndex;
    }

    // Configure the datasource for loading the chunk.
    DataSource dataSource;
    if (encryptedDataSource != null) {
      dataSource = encryptedDataSource;
    } else {
      dataSource = upstreamDataSource;
    }
    DataSpec dataSpec = new DataSpec(chunkUri, segment.byterangeOffset, segment.byterangeLength,
        null);

    // Configure the extractor that will read the chunk.
    TsExtractor extractor;
    if (previousTsChunk == null || splicingIn || segment.discontinuity) {
      extractor = new TsExtractor(startTimeUs, samplePool);
    } else {
      extractor = previousTsChunk.extractor;
    }

    return new TsChunk(dataSource, dataSpec, extractor, enabledVariants[currentVariantIndex].index,
        startTimeUs, endTimeUs, nextChunkMediaSequence, splicingOut);
  }

  private void maybeSwitchVariantSimple(int currentBandwidth, long bufferedUs) {
    int idealVariantIndex = getVariantIndexForBandwidth(currentBandwidth);
    if ((idealVariantIndex > variantIndex && bufferedUs < MAX_BUFFER_TO_SWITCH_DOWN_US)
        || (idealVariantIndex < variantIndex && bufferedUs > MIN_BUFFER_TO_SWITCH_UP_US)) {
      variantIndex = idealVariantIndex;
    }
  }

  private void maybeSwitchVariantEx(TsChunk previousTsChunk, int currentBandwidth, long bufferedUs) {
    // Notations here are from the paper "Rate Adaptation for Adaptive HTTP Streaming"
    // by Chenghao Liu, Imed Bouazizi, Moncef Gabbouj
    double mediaSegmentTime = (double)(previousTsChunk.endTimeUs - previousTsChunk.startTimeUs);
    double segmentFetchTime = (double)previousTsChunk.getFetchTimeUs();
    Assertions.checkArgument(segmentFetchTime > 0);
    double mu = mediaSegmentTime / segmentFetchTime;
    double epsilon = getVariantEpsilon(variantIndex);
    double gamma = 0.7; // This is a tunable step down factor
    int segmentBandwidth = (int)(mu * enabledVariants[variantIndex].bandwidth);
    int effectiveBandwidth = Math.min(currentBandwidth, segmentBandwidth);
    if (DEBUG_SWITCHING) {
      Log.d(TAG, String.format("%d mu=%2.2f epsilon=%2.2f buffered=%2.2f stream=%2.2f"
              + " next=%2.2f prev=%2.2f current=%2.2f segment=%2.2f",
          variantIndex,
          mu, epsilon, bufferedUs / 1000000.0,
          bpsToMbps(enabledVariants[variantIndex].bandwidth),
          bpsToMbps(enabledVariants[Math.max(0, variantIndex - 1)].bandwidth),
          bpsToMbps(enabledVariants[Math.min(enabledVariants.length - 1, variantIndex + 1)].bandwidth),
          bpsToMbps(currentBandwidth),
          bpsToMbps(segmentBandwidth)
      ));
    }
    if ((mu > (1 + epsilon)) && bufferedUs >= MIN_BUFFER_TO_SWITCH_UP_US) {
      // Step up if our bandwidth meter says we can (and if there's a higher bps variant)
      if (variantIndex == 0) {
        // Can't step up, already at highest bit rate
      } else {
        // If we're fetching much faster than stream rate, step up faster.
        boolean changed = false;
        double nextEpsilon = getVariantEpsilon(variantIndex - 1);
        double totalEpsilon = epsilon + 2 * nextEpsilon;
        int step = mu > (1 + totalEpsilon) ? 2 : 1;
        while (variantIndex - step < 0) {
          step--;
        }
        // Try to make really sure we can handle the step up
        for (int i = step; i >= 0; i--) {
          if (enabledVariants[variantIndex - step].bandwidth <= effectiveBandwidth) {
            if (DEBUG_SWITCHING) {
              Log.d(TAG, String.format(" Step up %d (%d->%d). current=%2.2f stream=%2.2f" +
                      " new=%2.2f %2.2f %2.2f",
                  step, variantIndex, variantIndex - step,
                  bpsToMbps(currentBandwidth),
                  bpsToMbps(enabledVariants[variantIndex].bandwidth),
                  bpsToMbps(enabledVariants[variantIndex - step].bandwidth),
                  nextEpsilon, totalEpsilon));
            }
            variantIndex -= step;
            changed = true;
            break;
          }
        }
        if (!changed && DEBUG_SWITCHING) {
          Log.d(TAG, String.format(" NOT stepping up. current=%2.2f stream=%2.2f next=%2.2f" +
              " %2.2f %2.2f",
              bpsToMbps(currentBandwidth),
              bpsToMbps(enabledVariants[variantIndex - 0].bandwidth),
              bpsToMbps(enabledVariants[variantIndex - 1].bandwidth),
              nextEpsilon, totalEpsilon));
        }
      }
    } else if ((mu < gamma && bufferedUs < MAX_BUFFER_TO_SWITCH_DOWN_US)
        || (mu < 1 && bufferedUs < MIN_BUFFER_TO_SWITCH_DOWN_US)) {
      // We're switching down fast to avoid buffer under run
      int newIndex = getVariantIndexForBandwidth(Math.min(currentBandwidth, segmentBandwidth));
      if (DEBUG_SWITCHING) {
        Log.d(TAG, String.format(" Switch down" +
                " current=%2.2f segment=%2.2f stream=%2.2f new=%2.2f",
            bpsToMbps(currentBandwidth),
            bpsToMbps(segmentBandwidth),
            bpsToMbps(enabledVariants[variantIndex].bandwidth),
            bpsToMbps(enabledVariants[newIndex].bandwidth)));
      }
      variantIndex = newIndex;
    }
  }

  private double getVariantEpsilon(int index) {
    if (enabledVariants.length < 2 || index < 1) {
      return 0.0;
    }

    double br_higher  = (double)enabledVariants[index - 1].bandwidth;
    double br_current = (double)enabledVariants[index - 0].bandwidth;
    return (br_higher - br_current) / br_current;
  }

  private static double bpsToMbps(int bps) {
    return bps / (1024.0 * 1024.0);
  }

  private int getVariantIndexForBandwidth(int bandwidth) {
    for (int i = 0; i < enabledVariants.length - 1; i++) {
      if (enabledVariants[i].bandwidth <= bandwidth) {
        return i;
      }
    }
    return enabledVariants.length - 1;
  }

  private boolean shouldRerequestMediaPlaylist() {
    // Don't re-request media playlist more often than the target duration.
    HlsMediaPlaylist mediaPlaylist = mediaPlaylists[variantIndex];
    long timeSinceLastMediaPlaylistLoadMs =
        SystemClock.elapsedRealtime() - lastMediaPlaylistLoadTimesMs[variantIndex];
    return timeSinceLastMediaPlaylistLoadMs >= (mediaPlaylist.targetDurationSecs * 1000);
  }

  private int getLiveStartChunkMediaSequence() {
    // For live start playback from the third chunk from the end.
    HlsMediaPlaylist mediaPlaylist = mediaPlaylists[variantIndex];
    int chunkIndex = mediaPlaylist.segments.size() > 3 ? mediaPlaylist.segments.size() - 3 : 0;
    return chunkIndex + mediaPlaylist.mediaSequence;
  }

  private MediaPlaylistChunk newMediaPlaylistChunk() {
    Uri mediaPlaylistUri = Util.getMergedUri(baseUri, enabledVariants[variantIndex].url);
    DataSpec dataSpec = new DataSpec(mediaPlaylistUri, 0, C.LENGTH_UNBOUNDED, null);
    Uri baseUri = Util.parseBaseUri(mediaPlaylistUri.toString());
    return new MediaPlaylistChunk(variantIndex, upstreamDataSource, dataSpec, baseUri);
  }

  private EncryptionKeyChunk newEncryptionKeyChunk(Uri keyUri, String iv) {
    DataSpec dataSpec = new DataSpec(keyUri, 0, C.LENGTH_UNBOUNDED, null);
    return new EncryptionKeyChunk(upstreamDataSource, dataSpec, iv);
  }

  /* package */ void initEncryptedDataSource(Uri keyUri, String iv, byte[] secretKey) {
    String trimmedIv;
    if (iv.toLowerCase(Locale.getDefault()).startsWith("0x")) {
      trimmedIv = iv.substring(2);
    } else {
      trimmedIv = iv;
    }

    byte[] ivData = new BigInteger(trimmedIv, 16).toByteArray();
    byte[] ivDataWithPadding = new byte[16];
    int offset = ivData.length > 16 ? ivData.length - 16 : 0;
    System.arraycopy(ivData, offset, ivDataWithPadding, ivDataWithPadding.length - ivData.length
        + offset, ivData.length - offset);

    encryptedDataSource = new Aes128DataSource(secretKey, ivDataWithPadding, upstreamDataSource);
    encryptionKeyUri = keyUri;
    encryptedDataSourceIv = iv;
    encryptedDataSourceSecretKey = secretKey;
  }

  private void clearEncryptedDataSource() {
    encryptionKeyUri = null;
    encryptedDataSource = null;
    encryptedDataSourceIv = null;
    encryptedDataSourceSecretKey = null;
  }

  private static Variant[] filterVariants(HlsMasterPlaylist masterPlaylist, int[] variantIndices) {
    List<Variant> masterVariants = masterPlaylist.variants;
    ArrayList<Variant> enabledVariants = new ArrayList<Variant>();
    if (variantIndices != null) {
      for (int i = 0; i < variantIndices.length; i++) {
        enabledVariants.add(masterVariants.get(variantIndices[i]));
      }
    } else {
      // If variantIndices is null then all variants are initially considered.
      enabledVariants.addAll(masterVariants);
    }

    ArrayList<Variant> definiteVideoVariants = new ArrayList<Variant>();
    ArrayList<Variant> definiteAudioOnlyVariants = new ArrayList<Variant>();
    for (int i = 0; i < enabledVariants.size(); i++) {
      Variant variant = enabledVariants.get(i);
      if (variant.height > 0 || variantHasExplicitCodecWithPrefix(variant, "avc")) {
        definiteVideoVariants.add(variant);
      } else if (variantHasExplicitCodecWithPrefix(variant, "mp4a")) {
        definiteAudioOnlyVariants.add(variant);
      }
    }

    if (!definiteVideoVariants.isEmpty()) {
      // We've identified some variants as definitely containing video. Assume variants within the
      // master playlist are marked consistently, and hence that we have the full set. Filter out
      // any other variants, which are likely to be audio only.
      enabledVariants = definiteVideoVariants;
    } else if (definiteAudioOnlyVariants.size() < enabledVariants.size()) {
      // We've identified some variants, but not all, as being audio only. Filter them out to leave
      // the remaining variants, which are likely to contain video.
      enabledVariants.removeAll(definiteAudioOnlyVariants);
    } else {
      // Leave the enabled variants unchanged. They're likely either all video or all audio.
    }

    Collections.sort(enabledVariants, new Variant.DecreasingBandwidthComparator());

    Variant[] enabledVariantsArray = new Variant[enabledVariants.size()];
    enabledVariants.toArray(enabledVariantsArray);
    return enabledVariantsArray;
  }

  private static boolean variantHasExplicitCodecWithPrefix(Variant variant, String prefix) {
    String[] codecs = variant.codecs;
    if (codecs == null) {
      return false;
    }
    for (int i = 0; i < codecs.length; i++) {
      if (codecs[i].startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private class MediaPlaylistChunk extends BitArrayChunk {

    @SuppressWarnings("hiding")
    private final int variantIndex;
    private final Uri playlistBaseUri;

    public MediaPlaylistChunk(int variantIndex, DataSource dataSource, DataSpec dataSpec,
        Uri playlistBaseUri) {
      super(dataSource, dataSpec, bitArray);
      this.variantIndex = variantIndex;
      this.playlistBaseUri = playlistBaseUri;
    }

    @Override
    protected void consume(BitArray data) throws IOException {
      HlsMediaPlaylist mediaPlaylist = mediaPlaylistParser.parse(
          new ByteArrayInputStream(data.getData(), 0, data.bytesLeft()), null, null,
          playlistBaseUri);
      mediaPlaylists[variantIndex] = mediaPlaylist;
      lastMediaPlaylistLoadTimesMs[variantIndex] = SystemClock.elapsedRealtime();
      live |= mediaPlaylist.live;
      durationUs = mediaPlaylist.durationUs;
    }

  }

  private class EncryptionKeyChunk extends BitArrayChunk {

    private final String iv;

    public EncryptionKeyChunk(DataSource dataSource, DataSpec dataSpec, String iv) {
      super(dataSource, dataSpec, bitArray);
      this.iv = iv;
    }

    @Override
    protected void consume(BitArray data) throws IOException {
      byte[] secretKey = new byte[data.bytesLeft()];
      data.readBytes(secretKey, 0, secretKey.length);
      initEncryptedDataSource(dataSpec.uri, iv, secretKey);
    }

  }

}
