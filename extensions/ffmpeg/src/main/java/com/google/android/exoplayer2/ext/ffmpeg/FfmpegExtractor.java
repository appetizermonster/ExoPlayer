package com.google.android.exoplayer2.ext.ffmpeg;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.ExtractorOutput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableByteArray;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Extracts data from files using FFmpeg demuxing.
 *
 * <p>Uses fd-based I/O via {@code pread()}, reading directly from disk with only a 32KB AVIO
 * buffer in memory. Requires a {@link Context} and {@link Uri}, provided automatically by {@link
 * com.google.android.exoplayer2.extractor.DefaultExtractorsFactory} when configured with {@link
 * com.google.android.exoplayer2.extractor.DefaultExtractorsFactory#setContext(Context)}.
 */
public final class FfmpegExtractor implements Extractor {

  private static final String TAG = "FfmpegExtractor";
  private static final int SNIFF_BUFFER_SIZE = 16;
  private static final int PACKET_BUFFER_SIZE = 32768;
  private static final byte[] ASF_SIGNATURE = new byte[]{
      (byte) 0x30, (byte) 0x26, (byte) 0xB2, (byte) 0x75,
      (byte) 0x8E, (byte) 0x66, (byte) 0xCF, (byte) 0x11,
      (byte) 0xA6, (byte) 0xD9, (byte) 0x00, (byte) 0xAA,
      (byte) 0x00, (byte) 0x62, (byte) 0xCE, (byte) 0x6C
  };


  // FFmpeg AVERROR_EOF: -(int)(('E') | ('O' << 8) | ('F' << 16) | ((unsigned)' ' << 24))
  private static final int AVERROR_EOF = -541478725;

  // FFmpeg codec IDs (subset relevant for ASF/WMA)
  private static final int AV_CODEC_ID_WMAV1 = 0x15000 + 7;
  private static final int AV_CODEC_ID_WMAV2 = 0x15000 + 8;

  private long nativeContext;
  private ExtractorOutput extractorOutput;
  private TrackOutput trackOutput;
  private boolean tracksInitialized;
  private final byte[] packetBuffer;
  private final ParsableByteArray packetData;
  private final long[] timestampBuffer;
  private boolean endOfInput;
  private boolean released;

  // For fd-based I/O (lazily initialized)
  @NonNull private final Context context;
  @NonNull private final Uri uri;
  @Nullable private FfmpegFileDescriptorInfo fdInfo;

  /**
   * Creates an extractor that will use fd-based I/O.
   *
   * <p>The file descriptor is not opened until the first call to {@link #read}, avoiding resource
   * leaks if this extractor loses sniffing and is never used.
   *
   * @param context The application context. Used to resolve content:// URIs.
   * @param uri The URI of the media to extract.
   */
  public FfmpegExtractor(@NonNull Context context, @NonNull Uri uri) {
    packetBuffer = new byte[PACKET_BUFFER_SIZE];
    packetData = new ParsableByteArray(packetBuffer, 0);
    timestampBuffer = new long[1];
    tracksInitialized = false;
    endOfInput = false;
    nativeContext = 0;
    this.context = context.getApplicationContext();
    this.uri = uri;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    if (released) {
      return false;
    }

    byte[] sniffData = new byte[SNIFF_BUFFER_SIZE];
    input.peekFully(sniffData, 0, SNIFF_BUFFER_SIZE);

    // ASF GUID signature
    for (int i = 0; i < 16; i++) {
      if (sniffData[i] != ASF_SIGNATURE[i]) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void init(ExtractorOutput output) {
    this.extractorOutput = output;
    this.trackOutput = extractorOutput.track(0, C.TRACK_TYPE_AUDIO);
    this.extractorOutput.endTracks();
  }

  @Override
  public @ReadResult int read(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    if (released) {
      return RESULT_END_OF_INPUT;
    }
    if (endOfInput) {
      return RESULT_END_OF_INPUT;
    }

    try {
      if (nativeContext == 0) {
        if (!FfmpegLibrary.isAvailable()) {
          throw ParserException.createForMalformedContainer(
              "Failed to load decoder native libraries.", null);
        }

        nativeContext = createContextFromFd();
      }

      if (!tracksInitialized) {
        initializeTracks();
        tracksInitialized = true;
      }

      return readSample();
    } catch (UnsatisfiedLinkError e) {
      Log.e(TAG, "Native method not available", e);
      endOfInput = true;
      return RESULT_END_OF_INPUT;
    }
  }

  /**
   * Creates a native context using fd-based I/O.
   *
   * @return The native context pointer.
   * @throws ParserException if the fd cannot be opened or the native context creation fails.
   */
  private long createContextFromFd() throws ParserException {
    try {
      fdInfo = FfmpegFileDescriptorInfo.createFromUri(context, uri);
      if (fdInfo == null) {
        throw ParserException.createForMalformedContainer(
            "URI scheme not supported for fd-based I/O: " + uri.getScheme(), null);
      }

      long ctx = nativeCreateContextFromFd(fdInfo.fd, fdInfo.startOffset, fdInfo.length);
      if (ctx != 0) {
        Log.d(TAG, "Created native context from fd (fd=" + fdInfo.fd
            + ", offset=" + fdInfo.startOffset + ", length=" + fdInfo.length + ")");
        return ctx;
      } else {
        closeFdInfo();
        throw ParserException.createForMalformedContainer(
            "nativeCreateContextFromFd returned 0", null);
      }
    } catch (IOException e) {
      closeFdInfo();
      throw ParserException.createForMalformedContainer(
          "Failed to open file descriptor for: " + uri, e);
    } catch (UnsatisfiedLinkError e) {
      closeFdInfo();
      throw ParserException.createForMalformedContainer(
          "nativeCreateContextFromFd not available", e);
    }
  }

  private void initializeTracks() throws ParserException {
    int codecId = nativeGetAudioCodecId(nativeContext);
    int sampleRate = nativeGetSampleRate(nativeContext);
    int channelCount = nativeGetChannelCount(nativeContext);
    long bitRate = nativeGetBitRate(nativeContext);
    int blockAlign = nativeGetBlockAlign(nativeContext);
    byte[] extraData = nativeGetExtraData(nativeContext);

    String mimeType = getMimeTypeFromCodecId(codecId);
    if (mimeType == null) {
      Log.e(TAG, "Unsupported codec ID: " + codecId);
      throw ParserException.createForMalformedContainer("Unsupported codec ID: " + codecId, null);
    }

    Format.Builder formatBuilder = new Format.Builder()
        .setSampleMimeType(mimeType)
        .setChannelCount(channelCount)
        .setSampleRate(sampleRate);

    if (bitRate > 0) {
      formatBuilder.setAverageBitrate((int) bitRate);
    }

    ArrayList<byte[]> initializationData = new ArrayList<>();
    if (extraData != null && extraData.length > 0) {
      initializationData.add(extraData);
    }

    byte[] blockAlignBytes = new byte[2];
    blockAlignBytes[0] = (byte) (blockAlign & 0xFF);
    blockAlignBytes[1] = (byte) ((blockAlign >> 8) & 0xFF);
    initializationData.add(blockAlignBytes);
    formatBuilder.setInitializationData(initializationData);

    Format format = formatBuilder.build();

    trackOutput.format(format);

    // Set up seek map. FFmpeg's format_ctx->duration is already in microseconds (AV_TIME_BASE).
    long durationUs = nativeGetDuration(nativeContext);
    if (durationUs <= 0) {
      durationUs = C.TIME_UNSET;
    }

    extractorOutput.seekMap(new FfmpegSeekMap(durationUs));

    Log.d(TAG, String.format("SeekMap initialized - Duration: %d us", durationUs));
    Log.d(TAG,
        String.format("Track initialized - Codec: %s, Sample Rate: %d, Channels: %d, Bitrate: %d",
            mimeType, sampleRate, channelCount, bitRate));
  }

  @Nullable
  private String getMimeTypeFromCodecId(int codecId) {
    switch (codecId) {
      case AV_CODEC_ID_WMAV1:
      case AV_CODEC_ID_WMAV2:
        return MimeTypes.AUDIO_WMA;
      default:
        return null;
    }
  }

  private int readSample() {
    // FFmpeg demuxer will handle reading directly from ExtractorInput
    // through the sliding buffer AVIO context
    timestampBuffer[0] = C.TIME_UNSET;
    int packetSize = nativeReadPacket(nativeContext, packetBuffer, PACKET_BUFFER_SIZE,
        timestampBuffer);

    if (packetSize < 0) {
      if (packetSize == AVERROR_EOF) {
        endOfInput = true;
        return RESULT_END_OF_INPUT;
      }
      Log.e(TAG, "Error reading packet: " + packetSize);
      return RESULT_CONTINUE; // Skip this packet and continue
    }

    if (packetSize == 0) {
      return RESULT_CONTINUE; // No packet data, continue reading
    }

    // Output the packet data to ExoPlayer
    packetData.reset(packetBuffer, packetSize);
    trackOutput.sampleData(packetData, packetSize);

    // Use extracted timestamp from FFmpeg
    long sampleTimeUs = timestampBuffer[0];
    trackOutput.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, packetSize, 0, null);

    return RESULT_CONTINUE;
  }

  @Override
  public void seek(long position, long timeUs) {
    if (released) {
      return;
    }

    Log.d(TAG, "seek: position: " + position + ", timeUs: " + timeUs);
    if (nativeContext != 0) {
      nativeSeek(nativeContext, timeUs);
      endOfInput = false;
    }
  }

  @Override
  public void release() {
    Log.d(TAG, "release() called");
    if (nativeContext != 0) {
      try {
        nativeReleaseContext(nativeContext);
      } catch (UnsatisfiedLinkError e) {
        Log.e(TAG, "Native releaseContext method not available", e);
      }
      nativeContext = 0;
    }
    closeFdInfo();
    tracksInitialized = false;
    endOfInput = false;
    released = true;
  }

  private void closeFdInfo() {
    if (fdInfo != null) {
      fdInfo.close();
      fdInfo = null;
    }
  }

  // JNI method declarations
  private native long nativeCreateContextFromFd(int fd, long startOffset, long length);

  private native int nativeGetAudioCodecId(long context);

  private native int nativeGetSampleRate(long context);

  private native int nativeGetChannelCount(long context);

  private native long nativeGetBitRate(long context);

  private native int nativeGetBlockAlign(long context);

  private native byte[] nativeGetExtraData(long context);

  private native int nativeReadPacket(long context, byte[] outputBuffer, int outputBufferSize,
      long[] timestampOut);

  private native long nativeGetDuration(long context);

  private native boolean nativeSeek(long context, long timeUs);

  private native void nativeReleaseContext(long context);
}
