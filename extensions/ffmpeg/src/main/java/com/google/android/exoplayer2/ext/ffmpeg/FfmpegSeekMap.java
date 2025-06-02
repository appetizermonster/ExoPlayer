package com.google.android.exoplayer2.ext.ffmpeg;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.SeekMap;
import com.google.android.exoplayer2.extractor.SeekPoint;

/**
 * A {@link SeekMap} implementation for ASF files that delegates seeking to the native FFmpeg
 * demuxer.
 */
/* package */ final class FfmpegSeekMap implements SeekMap {

  private final long durationUs;

  /**
   * Creates a new FFmpeg seek map.
   *
   * @param durationUs The duration of the stream in microseconds, or {@link C#TIME_UNSET} if
   *                   unknown.
   */
  public FfmpegSeekMap(long durationUs) {
    this.durationUs = durationUs;
  }

  @Override
  public boolean isSeekable() {
    return true;
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    if (timeUs <= 0) {
      return new SeekPoints(SeekPoint.START);
    }

    if (durationUs != C.TIME_UNSET && timeUs >= durationUs) {
      return new SeekPoints(new SeekPoint(durationUs, /* position= */ 0));
    }

    // We can only provide approximate seek points
    // since the exact byte position is managed by the native demuxer
    SeekPoint seekPoint = new SeekPoint(timeUs, /* position= */ 0);
    return new SeekPoints(seekPoint);
  }
}
