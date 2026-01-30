package com.google.android.exoplayer2.ext.ffmpeg;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Log;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;

/**
 * Holds file descriptor information for native FFmpeg access.
 * Manages file resources lifecycle - must be closed when done.
 */
public final class FfmpegFileDescriptorInfo implements Closeable {

  private static final String TAG = "FfmpegFileDescriptor";

  /**
   * Native file descriptor.
   */
  public final int fd;

  /**
   * Start offset within the file. For content:// URIs, AssetFileDescriptor
   * may point to a sub-section of a larger file.
   */
  public final long startOffset;

  /**
   * Length of the accessible file region.
   */
  public final long length;

  // File resources that must stay open while native code uses the fd
  @Nullable
  private AssetFileDescriptor assetFileDescriptor;
  @Nullable
  private RandomAccessFile randomAccessFile;

  private FfmpegFileDescriptorInfo(
      int fd,
      long startOffset,
      long length,
      @Nullable AssetFileDescriptor assetFileDescriptor,
      @Nullable RandomAccessFile randomAccessFile) {
    this.fd = fd;
    this.startOffset = startOffset;
    this.length = length;
    this.assetFileDescriptor = assetFileDescriptor;
    this.randomAccessFile = randomAccessFile;
  }

  /**
   * Creates file descriptor info from a URI.
   *
   * @param context     The application context (needed for content:// URIs).
   * @param uri         The URI to open.
   * @param knownLength The known length, or C.LENGTH_UNSET if unknown.
   * @return File descriptor info, or null if the URI is not a local file.
   */
  @Nullable
  public static FfmpegFileDescriptorInfo createFromUri(
      Context context, Uri uri, long knownLength) {
    String scheme = uri.getScheme();
    try {
      if ("file".equals(scheme) || scheme == null) {
        return createFromFilePath(uri.getPath(), knownLength);
      } else if ("content".equals(scheme)) {
        return createFromContentUri(context, uri, knownLength);
      }
    } catch (Exception e) {
      Log.w(TAG, "Failed to create fd info from URI: " + uri, e);
    }
    return null;
  }

  @Nullable
  private static FfmpegFileDescriptorInfo createFromFilePath(
      @Nullable String path, long knownLength) throws Exception {
    if (path == null) {
      return null;
    }

    RandomAccessFile raf = new RandomAccessFile(path, "r");
    try {
      FileDescriptor fileDescriptor = raf.getFD();
      int fd = getNativeFd(fileDescriptor);
      long fileLength = knownLength != C.LENGTH_UNSET ? knownLength : raf.length();

      Log.d(TAG, "Created fd=" + fd + " from file: " + path + ", length=" + fileLength);
      return new FfmpegFileDescriptorInfo(fd, 0, fileLength, null, raf);
    } catch (Exception e) {
      raf.close();
      throw e;
    }
  }

  @Nullable
  private static FfmpegFileDescriptorInfo createFromContentUri(
      Context context, Uri uri, long knownLength) throws Exception {
    ContentResolver resolver = context.getContentResolver();
    AssetFileDescriptor afd = resolver.openAssetFileDescriptor(uri, "r");
    if (afd == null) {
      return null;
    }

    try {
      FileDescriptor fileDescriptor = afd.getFileDescriptor();
      int fd = getNativeFd(fileDescriptor);
      long startOffset = afd.getStartOffset();
      long declaredLength = afd.getDeclaredLength();

      long length;
      if (declaredLength != AssetFileDescriptor.UNKNOWN_LENGTH) {
        length = declaredLength;
      } else if (knownLength != C.LENGTH_UNSET) {
        length = knownLength;
      } else {
        length = afd.getParcelFileDescriptor().getStatSize() - startOffset;
      }

      Log.d(TAG, "Created fd=" + fd + " from content URI: " + uri
          + ", startOffset=" + startOffset + ", length=" + length);
      return new FfmpegFileDescriptorInfo(fd, startOffset, length, afd, null);
    } catch (Exception e) {
      afd.close();
      throw e;
    }
  }

  /**
   * Extracts native file descriptor from FileDescriptor using reflection.
   */
  private static int getNativeFd(FileDescriptor fd) throws Exception {
    Field descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
    descriptorField.setAccessible(true);
    return descriptorField.getInt(fd);
  }

  @Override
  public void close() {
    if (assetFileDescriptor != null) {
      try {
        assetFileDescriptor.close();
      } catch (IOException e) {
        Log.w(TAG, "Error closing AssetFileDescriptor", e);
      }
      assetFileDescriptor = null;
    }
    if (randomAccessFile != null) {
      try {
        randomAccessFile.close();
      } catch (IOException e) {
        Log.w(TAG, "Error closing RandomAccessFile", e);
      }
      randomAccessFile = null;
    }
  }
}
