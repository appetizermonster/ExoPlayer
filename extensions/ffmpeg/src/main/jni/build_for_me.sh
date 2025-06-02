#!/bin/bash

EXOPLAYER_ROOT="/Users/heejinlee/Workspace/ExoPlayer"
FFMPEG_EXT_PATH="/Users/heejinlee/Workspace/ExoPlayer/extensions/ffmpeg/src/main"
NDK_PATH="/Users/heejinlee/Library/Android/sdk/ndk/21.4.7075529"
HOST_PLATFORM="darwin-x86_64"
ENABLED_DECODERS=(vorbis opus flac alac wmav2)

echo "${FFMPEG_EXT_PATH}" "${NDK_PATH}" "${HOST_PLATFORM}" "${ENABLED_DECODERS[@]}"
./build_ffmpeg.sh "${FFMPEG_EXT_PATH}" "${NDK_PATH}" "${HOST_PLATFORM}" "${ENABLED_DECODERS[@]}"
