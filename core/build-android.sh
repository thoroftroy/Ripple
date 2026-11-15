#!/bin/sh
# Builds libripple_jni.so for the Android app (arm64 phones and x86_64 emulators).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.0.12433566}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=29
OUT="$HERE/../ripple/src/main/jniLibs"
for pair in "aarch64-linux-android:arm64-v8a" "x86_64-linux-android:x86_64"; do
  T="${pair%%:*}"; ABI="${pair##*:}"
  U=$(echo "$T" | tr 'a-z-' 'A-Z_')
  export "CC_$(echo $T | tr '-' '_')=$TC/$T$API-clang"
  export "AR_$(echo $T | tr '-' '_')=$TC/llvm-ar"
  export "CARGO_TARGET_${U}_LINKER=$TC/$T$API-clang"
  (cd "$HERE" && cargo build --release -p ripple-jni --target "$T" -q)
  mkdir -p "$OUT/$ABI"
  cp "$HERE/target/$T/release/libripple_jni.so" "$OUT/$ABI/libripple_jni.so"
  "$TC/llvm-strip" "$OUT/$ABI/libripple_jni.so"
  "$TC/$T$API-clang" -std=c11 -Wall -Wextra -Os -fno-stack-protector -shared -fPIC -Wl,--gc-sections \
    -o "$OUT/$ABI/libtermux.so" "$HERE/../terminal-emulator/src/main/jni/termux.c"
  "$TC/llvm-strip" "$OUT/$ABI/libtermux.so"
  echo "$ABI: $(du -h "$OUT/$ABI/libripple_jni.so" | cut -f1), libtermux $(du -h "$OUT/$ABI/libtermux.so" | cut -f1)"
done
