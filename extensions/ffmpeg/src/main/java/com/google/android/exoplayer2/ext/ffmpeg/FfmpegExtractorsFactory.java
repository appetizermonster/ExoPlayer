package com.google.android.exoplayer2.ext.ffmpeg;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.util.Log;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * An {@link ExtractorsFactory} that wraps another factory and adds FFmpeg support
 * with file descriptor-based access for local files (no memory buffering limit).
 *
 * <p>Usage:
 * <pre>{@code
 * ExtractorsFactory extractorsFactory = new FfmpegExtractorsFactory(
 *     context,
 *     new DefaultExtractorsFactory()
 *         .setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
 * );
 * }</pre>
 */
public final class FfmpegExtractorsFactory implements ExtractorsFactory {

  private static final String TAG = "FfmpegExtractorsFactory";

  private final Context context;
  @Nullable
  private final ExtractorsFactory baseFactory;

  /**
   * Creates a factory with only FFmpeg extractor.
   *
   * @param context The application context.
   */
  public FfmpegExtractorsFactory(Context context) {
    this(context, /* baseFactory= */ null);
  }

  /**
   * Creates a factory that wraps another factory and adds FFmpeg support.
   *
   * <p>{@link FfmpegExtractor} is automatically appended if not already present.
   * For local files (file:// and content://), file descriptor-based access is used
   * to bypass the 15MB memory limit.
   *
   * @param context     The application context.
   * @param baseFactory The base factory to wrap, or null for FFmpeg-only.
   */
  public FfmpegExtractorsFactory(Context context, @Nullable ExtractorsFactory baseFactory) {
    this.context = context.getApplicationContext();
    this.baseFactory = baseFactory;
  }

  @Override
  public Extractor[] createExtractors() {
    // Without URI info, we can't create fd-based extractor
    // Return base extractors + FfmpegExtractor (memory-buffered)
    return createExtractorsInternal(/* uri= */ null);
  }

  @Override
  public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
    return createExtractorsInternal(uri);
  }

  private Extractor[] createExtractorsInternal(@Nullable Uri uri) {
    // Get base extractors
    Extractor[] baseExtractors = baseFactory != null
        ? baseFactory.createExtractors()
        : new Extractor[0];

    // Check if FfmpegExtractor is already included
    boolean hasFfmpegExtractor = false;
    for (Extractor extractor : baseExtractors) {
      if (extractor instanceof FfmpegExtractor) {
        hasFfmpegExtractor = true;
        break;
      }
    }

    // Create FfmpegExtractor (with fd if local URI)
    FfmpegExtractor ffmpegExtractor = createFfmpegExtractor(uri);

    if (hasFfmpegExtractor) {
      // Replace existing FfmpegExtractor with our fd-enabled version
      Extractor[] result = baseExtractors.clone();
      for (int i = 0; i < result.length; i++) {
        if (result[i] instanceof FfmpegExtractor) {
          result[i] = ffmpegExtractor;
          break;
        }
      }
      return result;
    } else {
      // Append FfmpegExtractor
      Extractor[] result = Arrays.copyOf(baseExtractors, baseExtractors.length + 1);
      result[baseExtractors.length] = ffmpegExtractor;
      return result;
    }
  }

  private FfmpegExtractor createFfmpegExtractor(@Nullable Uri uri) {
    if (uri == null) {
      return new FfmpegExtractor();
    }

    // Try to create fd-based extractor for local files
    FfmpegFileDescriptorInfo fdInfo = FfmpegFileDescriptorInfo.createFromUri(
        context, uri, C.LENGTH_UNSET);

    if (fdInfo != null) {
      Log.d(TAG, "Using fd-based FFmpeg extractor for: " + uri);
      return new FfmpegExtractor(fdInfo);
    }

    // Fall back to memory-buffered extractor
    return new FfmpegExtractor();
  }
}
