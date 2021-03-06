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

import android.net.Uri;

import java.util.List;

/**
 * Represents an HLS media playlist.
 */
public final class HlsMediaPlaylist {

  /**
   * Media segment reference.
   */
  public static final class Segment implements Comparable<Long> {
    public final boolean discontinuity;
    public final double durationSecs;
    public final String url;
    public final long startTimeUs;
    public final String encryptionMethod;
    public final String encryptionKeyUri;
    public final String encryptionIV;
    public final int byterangeOffset;
    public final int byterangeLength;

    public Segment(String uri, double durationSecs, boolean discontinuity, long startTimeUs,
        String encryptionMethod, String encryptionKeyUri, String encryptionIV,
        int byterangeOffset, int byterangeLength) {
      this.url = uri;
      this.durationSecs = durationSecs;
      this.discontinuity = discontinuity;
      this.startTimeUs = startTimeUs;
      this.encryptionMethod = encryptionMethod;
      this.encryptionKeyUri = encryptionKeyUri;
      this.encryptionIV = encryptionIV;
      this.byterangeOffset = byterangeOffset;
      this.byterangeLength = byterangeLength;
    }

    @Override
    public int compareTo(Long startTimeUs) {
      return this.startTimeUs > startTimeUs ? 1 : (this.startTimeUs < startTimeUs ? -1 : 0);
    }
  }

  public static final String ENCRYPTION_METHOD_NONE = "NONE";
  public static final String ENCRYPTION_METHOD_AES_128 = "AES-128";

  public final Uri baseUri;
  public final int mediaSequence;
  public final int targetDurationSecs;
  public final int version;
  public final List<Segment> segments;
  public final boolean live;
  public final long durationUs;

  public HlsMediaPlaylist(Uri baseUri, int mediaSequence, int targetDurationSecs, int version,
      boolean live, List<Segment> segments) {
    this.baseUri = baseUri;
    this.mediaSequence = mediaSequence;
    this.targetDurationSecs = targetDurationSecs;
    this.version = version;
    this.live = live;
    this.segments = segments;

    if (this.segments.size() > 0) {
      Segment lastSegment = segments.get(this.segments.size() - 1);
      this.durationUs = lastSegment.startTimeUs + (long) (lastSegment.durationSecs * 1000000);
    } else {
      this.durationUs = 0;
    }
  }

}
