package com.google.android.exoplayer2.ext.ffmpeg;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.util.Log;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Holds a native file descriptor, start offset, and length for fd-based FFmpeg I/O.
 *
 * <p>Manages the lifecycle of the underlying {@link ParcelFileDescriptor} or {@link
 * AssetFileDescriptor}, ensuring the fd remains valid until {@link #close()} is called.
 */
public final class FfmpegFileDescriptorInfo implements Closeable {

  private static final String TAG = "FfmpegFdInfo";

  /** The native file descriptor. */
  public final int fd;

  /** The byte offset within the file where the asset starts. */
  public final long startOffset;

  /** The length of the asset in bytes, or -1 if unknown. */
  public final long length;

  @Nullable private ParcelFileDescriptor parcelFileDescriptor;
  @Nullable private AssetFileDescriptor assetFileDescriptor;

  private FfmpegFileDescriptorInfo(
      int fd,
      long startOffset,
      long length,
      @Nullable ParcelFileDescriptor parcelFileDescriptor,
      @Nullable AssetFileDescriptor assetFileDescriptor) {
    this.fd = fd;
    this.startOffset = startOffset;
    this.length = length;
    this.parcelFileDescriptor = parcelFileDescriptor;
    this.assetFileDescriptor = assetFileDescriptor;
  }

  /**
   * Creates an {@link FfmpegFileDescriptorInfo} from a local URI.
   *
   * <p>Supports {@code file://} and {@code content://} schemes. For content URIs, uses {@link
   * ContentResolver#openTypedAssetFileDescriptor} with {@link
   * MediaStore#EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT} to prevent media transcoding on Android 12+.
   *
   * @param context The application context.
   * @param uri The URI to open.
   * @return The fd info, or {@code null} if the URI scheme is not supported.
   * @throws IOException If the file cannot be opened.
   */
  @Nullable
  @SuppressWarnings("InlinedApi") // We are inlining EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT.
  public static FfmpegFileDescriptorInfo createFromUri(Context context, Uri uri)
      throws IOException {
    String scheme = uri.getScheme();
    if (scheme == null) {
      return null;
    }
    scheme = scheme.toLowerCase();

    if ("file".equals(scheme)) {
      return createFromFileUri(uri);
    } else if ("content".equals(scheme)) {
      return createFromContentUri(context, uri);
    }

    return null;
  }

  private static FfmpegFileDescriptorInfo createFromFileUri(Uri uri) throws IOException {
    String path = uri.getPath();
    if (path == null) {
      throw new FileNotFoundException("File URI has no path: " + uri);
    }

    File file = new File(path);
    ParcelFileDescriptor pfd =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    int fd = pfd.getFd();
    long length = file.length();

    Log.d(TAG, "Opened file:// fd=" + fd + " length=" + length);
    return new FfmpegFileDescriptorInfo(
        fd, /* startOffset= */ 0, length, pfd, /* assetFileDescriptor= */ null);
  }

  @SuppressWarnings("InlinedApi")
  private static FfmpegFileDescriptorInfo createFromContentUri(Context context, Uri uri)
      throws IOException {
    ContentResolver resolver = context.getContentResolver();

    Bundle providerOptions = new Bundle();
    providerOptions.putBoolean(MediaStore.EXTRA_ACCEPT_ORIGINAL_MEDIA_FORMAT, true);

    AssetFileDescriptor afd =
        resolver.openTypedAssetFileDescriptor(uri, /* mimeType= */ "*/*", providerOptions);
    if (afd == null) {
      throw new FileNotFoundException("Could not open asset file descriptor for: " + uri);
    }

    ParcelFileDescriptor pfd = afd.getParcelFileDescriptor();
    int fd = pfd.getFd();
    long startOffset = afd.getStartOffset();
    long declaredLength = afd.getDeclaredLength();

    if (declaredLength == AssetFileDescriptor.UNKNOWN_LENGTH) {
      // Resolve the actual length using fstat() via ParcelFileDescriptor.getStatSize().
      // Do NOT use FileInputStream+FileChannel here — closing the FileInputStream would close
      // the underlying native fd, making it invalid for subsequent pread() calls.
      long statSize = pfd.getStatSize();
      if (statSize >= 0) {
        declaredLength = statSize > startOffset ? statSize - startOffset : statSize;
      } else {
        afd.close();
        throw new IOException("Cannot determine file size for: " + uri);
      }
    }

    Log.d(
        TAG,
        "Opened content:// fd=" + fd + " startOffset=" + startOffset + " length=" + declaredLength);
    return new FfmpegFileDescriptorInfo(
        fd, startOffset, declaredLength, /* parcelFileDescriptor= */ null, afd);
  }

  @Override
  public void close() {
    try {
      if (assetFileDescriptor != null) {
        assetFileDescriptor.close();
        assetFileDescriptor = null;
      }
      if (parcelFileDescriptor != null) {
        parcelFileDescriptor.close();
        parcelFileDescriptor = null;
      }
    } catch (IOException e) {
      Log.e(TAG, "Error closing file descriptor", e);
    }
  }
}
