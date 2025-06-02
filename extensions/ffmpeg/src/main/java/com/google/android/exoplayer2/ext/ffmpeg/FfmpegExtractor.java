package com.google.android.exoplayer2.ext.ffmpeg;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
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
 */
public final class FfmpegExtractor implements Extractor {

  private static final String TAG = "FfmpegExtractor";
  private static final int SNIFF_BUFFER_SIZE = 16;
  private static final int PACKET_BUFFER_SIZE = 32768;
  private static final int MAX_INPUT_LENGTH = 15 * 1024 * 1024; // 15 MB

  // FFmpeg codec IDs (subset relevant for ASF/WMA)
  private static final int AV_CODEC_ID_WMAV1 = 0x15000 + 7;
  private static final int AV_CODEC_ID_WMAV2 = 0x15000 + 8;

  private long nativeContext;
  private ExtractorOutput extractorOutput;
  private TrackOutput trackOutput;
  private boolean tracksInitialized;
  @Nullable
  private byte[] inputData;
  private int inputLength = C.LENGTH_UNSET;
  private final byte[] packetBuffer;
  private final long[] timestampBuffer;
  private boolean endOfInput;
  private FfmpegSeekMap seekMap;

  public FfmpegExtractor() throws FfmpegDecoderException {
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native libraries.");
    }
    packetBuffer = new byte[PACKET_BUFFER_SIZE];
    timestampBuffer = new long[1];
    tracksInitialized = false;
    endOfInput = false;
    nativeContext = 0;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    byte[] sniffBuffer = new byte[SNIFF_BUFFER_SIZE];
    input.peekFully(sniffBuffer, 0, SNIFF_BUFFER_SIZE);

    try {
      return nativeSniff(sniffBuffer, SNIFF_BUFFER_SIZE);
    } catch (UnsatisfiedLinkError e) {
      Log.e(TAG, "Native sniff method not available", e);
      return false;
    }
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
    if (endOfInput) {
      return RESULT_END_OF_INPUT;
    }

    if (inputData == null) {
      inputLength = (int) input.getLength();
      if (inputLength <= 0) {
        throw new IOException("Input length must be greater than zero.");
      }
      if (inputLength > MAX_INPUT_LENGTH) {
        throw new IOException("Input length exceeds maximum allowed size: " + MAX_INPUT_LENGTH);
      }

      inputData = new byte[inputLength];
      boolean result = input.readFully(inputData, 0, inputLength, true);
      Log.d(TAG, "Read " + inputLength + " bytes from input: " + result);
    }

    try {
      if (nativeContext == 0) {
        nativeContext = nativeCreateContext(inputData, inputLength);
        if (nativeContext == 0) {
          Log.e(TAG, "Failed to create native context");
          return RESULT_END_OF_INPUT;
        }
      }

      if (!tracksInitialized) {
        initializeTracks();
        tracksInitialized = true;
      }

      return readSample();
    } catch (UnsatisfiedLinkError e) {
      Log.e(TAG, "Native method not available", e);
      return RESULT_END_OF_INPUT;
    }
  }

  private void initializeTracks() {
    int codecId = nativeGetAudioCodecId(nativeContext);
    int sampleRate = nativeGetSampleRate(nativeContext);
    int channelCount = nativeGetChannelCount(nativeContext);
    long bitRate = nativeGetBitRate(nativeContext);
    int blockAlign = nativeGetBlockAlign(nativeContext);
    byte[] extraData = nativeGetExtraData(nativeContext);

    String mimeType = getMimeTypeFromCodecId(codecId);
    if (mimeType == null) {
      Log.e(TAG, "Unsupported codec ID: " + codecId);
      return;
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

    // Set up seek map
    long durationUs = nativeGetDuration(nativeContext);

    if (durationUs > 0) {
      // Convert FFmpeg duration (in AV_TIME_BASE units) to microseconds
      durationUs = (durationUs * 1000000L) / 1000000L; // Already in microseconds from FFmpeg
    } else {
      durationUs = C.TIME_UNSET;
    }

    seekMap = new FfmpegSeekMap(durationUs);
    extractorOutput.seekMap(seekMap);

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

  private int readSample() throws IOException {
    // FFmpeg demuxer will handle reading directly from ExtractorInput
    // through the sliding buffer AVIO context
    timestampBuffer[0] = C.TIME_UNSET;
    int packetSize = nativeReadPacket(nativeContext, packetBuffer, PACKET_BUFFER_SIZE,
        timestampBuffer);

    if (packetSize < 0) {
      if (packetSize == -541478725) { // AVERROR_EOF
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
    ParsableByteArray packetData = new ParsableByteArray(packetBuffer, packetSize);
    trackOutput.sampleData(packetData, packetSize);

    // Use extracted timestamp from FFmpeg
    long sampleTimeUs = timestampBuffer[0];
    trackOutput.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, packetSize, 0, null);

    return RESULT_CONTINUE;
  }

  @Override
  public void seek(long position, long timeUs) {
    Log.d(TAG, "seek: position: " + position + ", timeUs: " + timeUs);
    nativeSeek(nativeContext, timeUs);
    endOfInput = false;
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
    tracksInitialized = false;
    endOfInput = false;
    inputLength = C.LENGTH_UNSET;
    inputData = null;
  }

  // JNI method declarations
  private native long nativeCreateContext(byte[] inputData, int inputLength);

  private native boolean nativeSniff(byte[] data, int length);

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
