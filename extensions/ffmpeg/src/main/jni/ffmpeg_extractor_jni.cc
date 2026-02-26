#include <android/log.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>

extern "C"
{
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/log.h>
}

#define LOG_TAG "ffmpeg_extractor_jni"
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

#ifdef NDEBUG
#define LOGD(...)
#else
#define LOGD(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#endif

#define FFMPEG_EXTRACTOR_FUNC(RETURN_TYPE, NAME, ...)                             \
    extern "C"                                                                    \
    {                                                                             \
        JNIEXPORT RETURN_TYPE                                                     \
            Java_com_google_android_exoplayer2_ext_ffmpeg_FfmpegExtractor_##NAME( \
                JNIEnv *env, jobject thiz, ##__VA_ARGS__);                        \
    }                                                                             \
    JNIEXPORT RETURN_TYPE                                                         \
        Java_com_google_android_exoplayer2_ext_ffmpeg_FfmpegExtractor_##NAME(     \
            JNIEnv *env, jobject thiz, ##__VA_ARGS__)

#define AVIO_BUFFER_SIZE (32 * 1024) // 32KB AVIO buffer

struct FdInputWrapper {
  int fd;
  int64_t start_offset;
  int64_t length;
  int64_t cur_pos;
};

struct FfmpegDemuxContext {
  AVFormatContext *format_ctx;
  AVIOContext *avio_ctx;
  int audio_stream_index;
  AVStream *audio_stream;
  FdInputWrapper *fd_input_wrapper;
};

void log_error(const char *func_name, int error_no) {
  char buffer[256];
  av_strerror(error_no, buffer, sizeof(buffer));
  LOGE("Error in %s: %s", func_name, buffer);
}

// --- fd-based I/O functions (pread) ---

static int fd_input_wrapper_read(FdInputWrapper *fw, uint8_t *buf, int buf_size) {
  LOGD("fd_input_wrapper_read: buf_size=%d, cur_pos=%lld", buf_size, (long long) fw->cur_pos);
  if (!buf || buf_size <= 0) {
    LOGE("fd_input_wrapper_read: invalid parameters");
    return AVERROR(EINVAL);
  }

  if (fw->cur_pos >= fw->length) {
    return AVERROR_EOF;
  }

  int64_t remaining = fw->length - fw->cur_pos;
  int64_t to_read = remaining < buf_size ? remaining : buf_size;

  // pread() reads at a given offset without changing the file's seek position — thread-safe
  ssize_t bytes_read = pread(fw->fd, buf, (size_t) to_read, fw->start_offset + fw->cur_pos);
  if (bytes_read < 0) {
    LOGE("fd_input_wrapper_read: pread failed: %s", strerror(errno));
    return AVERROR(errno);
  }
  if (bytes_read == 0) {
    return AVERROR_EOF;
  }

  fw->cur_pos += bytes_read;
  return (int) bytes_read;
}

static int64_t fd_input_wrapper_seek(FdInputWrapper *fw, int64_t offset, int whence) {
  LOGD("fd_input_wrapper_seek: offset=%lld, whence=%d", (long long) offset, whence);

  if (whence & AVSEEK_SIZE) {
    return fw->length;
  }

  int64_t target;
  switch (whence & ~AVSEEK_FORCE) {
    case SEEK_SET:
      target = offset;
      break;
    case SEEK_CUR:
      target = fw->cur_pos + offset;
      break;
    case SEEK_END:
      target = fw->length + offset;
      break;
    default:
      return AVERROR(EINVAL);
  }

  if (target < 0 || target > fw->length) {
    LOGE("fd_input_wrapper_seek: overflow, target=%lld, length=%lld",
         (long long) target, (long long) fw->length);
    return AVERROR(EINVAL);
  }

  fw->cur_pos = target;
  return target;
}

static void fd_input_wrapper_free(FdInputWrapper *fw) {
  // Do NOT close fd — Java manages the lifecycle via ParcelFileDescriptor/AssetFileDescriptor.
  fw->fd = -1;
  fw->start_offset = 0;
  fw->length = 0;
  fw->cur_pos = 0;
}

// --- AVIO callbacks (fd-based) ---

static int avio_read_fd_callback(void *opaque, uint8_t *buf, int buf_size) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) opaque;
  return fd_input_wrapper_read(ctx->fd_input_wrapper, buf, buf_size);
}

static int64_t avio_seek_fd_callback(void *opaque, int64_t offset, int whence) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) opaque;
  return fd_input_wrapper_seek(ctx->fd_input_wrapper, offset, whence);
}

static void demux_context_free(FfmpegDemuxContext *ctx) {
  LOGD("demux_context_free: format_ctx");
  if (ctx->format_ctx) {
    avformat_close_input(&ctx->format_ctx);
    ctx->format_ctx = NULL;
    ctx->audio_stream_index = -1;
    ctx->audio_stream = NULL;
  }

  LOGD("demux_context_free: avio_ctx");
  if (ctx->avio_ctx) {
    av_freep(&ctx->avio_ctx->buffer);
    avio_context_free(&ctx->avio_ctx);
    ctx->avio_ctx = NULL;
  }

  LOGD("demux_context_free: fd_input_wrapper");
  if (ctx->fd_input_wrapper) {
    fd_input_wrapper_free(ctx->fd_input_wrapper);
    free(ctx->fd_input_wrapper);
    ctx->fd_input_wrapper = NULL;
  }

  LOGD("demux_context_free: ctx");
  free(ctx);
  LOGD("demux_context_free: done");
}

FFMPEG_EXTRACTOR_FUNC(jlong, nativeCreateContextFromFd, jint fd, jlong startOffset, jlong length) {
  if (fd < 0 || length <= 0) {
    LOGE("Invalid parameters for nativeCreateContextFromFd: fd=%d, length=%lld",
         fd, (long long) length);
    return 0L;
  }

  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) calloc(1, sizeof(FfmpegDemuxContext));
  if (!ctx) {
    LOGE("Failed to allocate FfmpegDemuxContext");
    return 0L;
  }

  ctx->audio_stream_index = -1;
  ctx->fd_input_wrapper = (FdInputWrapper *) calloc(1, sizeof(FdInputWrapper));
  if (!ctx->fd_input_wrapper) {
    LOGE("Failed to allocate FdInputWrapper");
    demux_context_free(ctx);
    return 0L;
  }

  ctx->fd_input_wrapper->fd = fd;
  ctx->fd_input_wrapper->start_offset = (int64_t) startOffset;
  ctx->fd_input_wrapper->length = (int64_t) length;
  ctx->fd_input_wrapper->cur_pos = 0;

  LOGD("nativeCreateContextFromFd: fd=%d, start_offset=%lld, length=%lld",
       fd, (long long) startOffset, (long long) length);

  unsigned char *avio_buffer = (unsigned char *) av_malloc(AVIO_BUFFER_SIZE);
  if (!avio_buffer) {
    LOGE("Failed to allocate AVIO buffer");
    demux_context_free(ctx);
    return 0L;
  }

  // Create AVIOContext with fd-based callbacks
  ctx->avio_ctx = avio_alloc_context(
      avio_buffer,
      AVIO_BUFFER_SIZE,
      0, // write_flag (0 for read-only)
      ctx,
      avio_read_fd_callback,
      NULL, // write_packet
      avio_seek_fd_callback);

  if (!ctx->avio_ctx) {
    LOGE("Failed to allocate AVIOContext");
    av_free(avio_buffer);
    demux_context_free(ctx);
    return 0L;
  }

  // Create format context
  ctx->format_ctx = avformat_alloc_context();
  if (!ctx->format_ctx) {
    LOGE("Failed to allocate format context");
    demux_context_free(ctx);
    return 0L;
  }

  ctx->format_ctx->format_probesize = 64 * 1024; // 64KB
  ctx->format_ctx->pb = ctx->avio_ctx;
  ctx->format_ctx->flags |= AVFMT_FLAG_CUSTOM_IO;

  // Open input
  LOGD("Opening input from fd, format_probesize: %d", ctx->format_ctx->format_probesize);

  int ret = avformat_open_input(&ctx->format_ctx, NULL, NULL, NULL);
  if (ret < 0) {
    log_error("avformat_open_input (fd)", ret);
    demux_context_free(ctx);
    return 0L;
  }

  // Find stream info
  LOGD("Finding stream info (fd)");
  ret = avformat_find_stream_info(ctx->format_ctx, NULL);
  if (ret < 0) {
    log_error("avformat_find_stream_info (fd)", ret);
    demux_context_free(ctx);
    return 0L;
  }
  LOGD("Stream info found (fd)");

  // Find first audio stream
  for (unsigned int i = 0; i < ctx->format_ctx->nb_streams; i++) {
    if (ctx->format_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
      ctx->audio_stream_index = (int) i;
      ctx->audio_stream = ctx->format_ctx->streams[i];
      break;
    }
  }

  if (ctx->audio_stream_index == -1) {
    LOGE("No audio stream found (fd)");
    demux_context_free(ctx);
    return 0L;
  }

  LOGD("Audio stream index (fd): %d", ctx->audio_stream_index);
  LOGD("Audio codec (fd): %s", avcodec_get_name(ctx->audio_stream->codecpar->codec_id));
  LOGD("Sample rate (fd): %d", ctx->audio_stream->codecpar->sample_rate);
  LOGD("Channels (fd): %d", ctx->audio_stream->codecpar->channels);
  LOGD("Bit rate (fd): %lld", (long long) ctx->audio_stream->codecpar->bit_rate);

  return (jlong) ctx;
}

FFMPEG_EXTRACTOR_FUNC(jint, nativeGetAudioCodecId, jlong context) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || ctx->audio_stream_index == -1) {
    return AV_CODEC_ID_NONE;
  }

  return (jint) ctx->audio_stream->codecpar->codec_id;
}

FFMPEG_EXTRACTOR_FUNC(jint, nativeGetSampleRate, jlong context) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || ctx->audio_stream_index == -1) {
    return -1;
  }

  return ctx->audio_stream->codecpar->sample_rate;
}

FFMPEG_EXTRACTOR_FUNC(jint, nativeGetChannelCount, jlong context) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || ctx->audio_stream_index == -1) {
    return -1;
  }

  return ctx->audio_stream->codecpar->channels;
}

FFMPEG_EXTRACTOR_FUNC(jlong, nativeGetBitRate, jlong context) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || ctx->audio_stream_index == -1) {
    return -1;
  }

  return (jlong) ctx->audio_stream->codecpar->bit_rate;
}

FFMPEG_EXTRACTOR_FUNC(jint, nativeGetBlockAlign, jlong context) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || ctx->audio_stream_index == -1) {
    return -1;
  }

  return ctx->audio_stream->codecpar->block_align;
}

FFMPEG_EXTRACTOR_FUNC(jbyteArray, nativeGetExtraData, jlong context) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || ctx->audio_stream_index == -1) {
    return NULL;
  }

  if (ctx->audio_stream->codecpar->extradata_size <= 0) {
    return NULL;
  }

  jbyteArray extraData = env->NewByteArray(ctx->audio_stream->codecpar->extradata_size);
  if (!extraData) {
    return NULL;
  }

  env->SetByteArrayRegion(extraData,
                          0, ctx->audio_stream->codecpar->extradata_size,
                          (jbyte *) ctx->audio_stream->codecpar->extradata);

  return extraData;
}

FFMPEG_EXTRACTOR_FUNC(jint, nativeReadPacket, jlong context, jbyteArray outputBuffer,
                      jint outputBufferSize, jlongArray timestampOut) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || ctx->audio_stream_index == -1 || !outputBuffer) {
    LOGE("Invalid parameters for nativeReadPacket");
    return -1;
  }

  AVPacket packet;
  av_init_packet(&packet);

  // Loop to skip non-audio packets in native code, avoiding tight JNI round-trips
  while (true) {
    int ret = av_read_frame(ctx->format_ctx, &packet);
    if (ret < 0) {
      if (ret == AVERROR_EOF) {
        LOGD("End of stream reached");
      } else {
        log_error("av_read_frame", ret);
      }
      av_packet_unref(&packet);
      return ret;
    }

    if (packet.stream_index == ctx->audio_stream_index) {
      break; // Found an audio packet
    }
    av_packet_unref(&packet);
  }

  if (packet.size > outputBufferSize) {
    LOGE("Packet size (%d) exceeds output buffer size (%d)", packet.size, outputBufferSize);
    av_packet_unref(&packet);
    return -1;
  }

  // Extract timestamp and convert to microseconds
  jlong timestampUs = -9223372036854775807LL; // C.TIME_UNSET equivalent
  if (packet.pts != AV_NOPTS_VALUE) {
    timestampUs = av_rescale_q(packet.pts, ctx->audio_stream->time_base, AV_TIME_BASE_Q);
  } else if (packet.dts != AV_NOPTS_VALUE) {
    timestampUs = av_rescale_q(packet.dts, ctx->audio_stream->time_base, AV_TIME_BASE_Q);
  }

  // Set timestamp output if array provided
  if (timestampOut) {
    env->SetLongArrayRegion(timestampOut, 0, 1, &timestampUs);
  }

  // Copy packet data to output buffer
  env->SetByteArrayRegion(outputBuffer, 0, packet.size, (jbyte *) packet.data);

  int packet_size = packet.size;
  av_packet_unref(&packet);

  return packet_size;
}

FFMPEG_EXTRACTOR_FUNC(jlong, nativeGetDuration, jlong context) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || !ctx->format_ctx) {
    return -1;
  }

  return ctx->format_ctx->duration;
}

FFMPEG_EXTRACTOR_FUNC(jboolean, nativeSeek, jlong context, jlong timeUs) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx || !ctx->format_ctx || ctx->audio_stream_index == -1) {
    return JNI_FALSE;
  }

  // Convert microseconds to FFmpeg time base
  int64_t timestamp = av_rescale_q(timeUs, AV_TIME_BASE_Q, ctx->audio_stream->time_base);

  int ret = av_seek_frame(
      ctx->format_ctx,
      ctx->audio_stream_index,
      timestamp,
      AVSEEK_FLAG_BACKWARD);
  if (ret < 0) {
    log_error("av_seek_frame", ret);
    return JNI_FALSE;
  }

  LOGD("nativeSeek: seek to %lld us successful", (long long) timeUs);
  return JNI_TRUE;
}

FFMPEG_EXTRACTOR_FUNC(void, nativeReleaseContext, jlong context) {
  FfmpegDemuxContext *ctx = (FfmpegDemuxContext *) context;
  if (!ctx) {
    return;
  }
  demux_context_free(ctx);
}
