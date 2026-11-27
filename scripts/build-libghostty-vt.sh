#!/usr/bin/env bash
# Builds and caches libghostty-vt (pinned to 1.0.2) and the thin JNI bridge
# (app/src/main/jni/ghostty_vt_jni.c) for Android ABIs and/or the Linux host
# JVM used by unit tests.
set -euo pipefail

GHOSTTY_VT_VERSION="1.0.2"
GHOSTTY_VT_SRC_SHA256="1dcafa8a3357f3a6bbf4f2696e61de611400c5469518716793dfffd35f008b3e"
GHOSTTY_VT_DEPS_SHA256="c56156dbea82fdf71f13bf0d704cdf4393b6a04208d7f9691e0d7f60c7f9ca4d"

ZIG_VERSION="0.17.0"
ZIG_X86_64_SHA256="1cbe9df9f27e6b78d14ccbca43b6703a404ef79ef1c463de901d7f088d4e2026"
ZIG_AARCH64_SHA256="c6c6782a0a0cc0f4363554b1f92a6a3f03923eb082e4d57b98f79825a70eb518"

ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:-29.0.14206865}"
ANDROID_MIN_API="24"

MODE="all"
while [ $# -gt 0 ]; do
  case "$1" in
    --all) MODE="all" ;;
    --android-only) MODE="android" ;;
    --host-only) MODE="host" ;;
    --print-ndk-home) MODE="print-ndk-home" ;;
    *)
      echo "usage: $0 [--all|--android-only|--host-only|--print-ndk-home]" >&2
      exit 2
      ;;
  esac
  shift
done

ndk_matches_major() {
  local ndk_dir="$1"
  local required_major="${ANDROID_NDK_VERSION%%.*}"
  local rev=""
  if [ -f "$ndk_dir/source.properties" ]; then
    rev="$(sed -n 's/^[[:space:]]*Pkg\.Revision[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$ndk_dir/source.properties" | head -n 1)"
  fi
  if [ -z "$rev" ]; then
    local base
    base="$(basename "$ndk_dir")"
    if [[ "$base" =~ ^([0-9]+)\. ]]; then
      rev="${BASH_REMATCH[1]}"
    fi
  fi
  [ -n "$rev" ] && [ "$rev" = "$required_major" ]
}

resolve_ndk_home() {
  if [ -n "${GHOSTTY_VT_ANDROID_NDK_HOME:-}" ]; then
    if [ -d "$GHOSTTY_VT_ANDROID_NDK_HOME/toolchains/llvm" ]; then
      printf '%s\n' "$GHOSTTY_VT_ANDROID_NDK_HOME"
      return 0
    fi
    echo "ERROR: GHOSTTY_VT_ANDROID_NDK_HOME ($GHOSTTY_VT_ANDROID_NDK_HOME) does not contain toolchains/llvm" >&2
    exit 1
  fi

  local sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
  if [ -d "$sdk_dir/ndk/$ANDROID_NDK_VERSION/toolchains/llvm" ]; then
    printf '%s\n' "$sdk_dir/ndk/$ANDROID_NDK_VERSION"
    return 0
  fi

  local env_ndk
  for env_ndk in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    if [ -n "$env_ndk" ] && [ -d "$env_ndk/toolchains/llvm" ] && ndk_matches_major "$env_ndk"; then
      printf '%s\n' "$env_ndk"
      return 0
    fi
  done

  local found
  found="$(find "$sdk_dir/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
  if [ -n "$found" ] && [ -d "$found/toolchains/llvm" ]; then
    printf '%s\n' "$found"
    return 0
  fi

  for env_ndk in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
    if [ -n "$env_ndk" ] && [ -d "$env_ndk/toolchains/llvm" ]; then
      printf '%s\n' "$env_ndk"
      return 0
    fi
  done

  echo "ERROR: Android NDK $ANDROID_NDK_VERSION not found under $sdk_dir/ndk. Install with: sdkmanager \"ndk;$ANDROID_NDK_VERSION\"" >&2
  exit 1
}

if [ "$MODE" = "print-ndk-home" ]; then
  resolve_ndk_home
  exit 0
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_SRC="$REPO_ROOT/app/src/main/jni/ghostty_vt_jni.c"
OUT_ROOT="${GHOSTTY_VT_OUT_DIR:-$REPO_ROOT/app/build/ghostty-vt}"
CACHE_DIR="${GHOSTTY_VT_CACHE_DIR:-$HOME/.cache/agentsterm-ghostty-vt}"

if [ ! -f "$JNI_SRC" ]; then
  echo "ERROR: missing JNI source $JNI_SRC" >&2
  exit 1
fi

HOST_ARCH="$(uname -m)"
case "$HOST_ARCH" in
  x86_64)
    ZIG_ARCH="x86_64-linux"
    ZIG_SHA256="$ZIG_X86_64_SHA256"
    HOST_ZIG_TARGET="x86_64-linux-gnu"
    ;;
  aarch64 | arm64)
    ZIG_ARCH="aarch64-linux"
    ZIG_SHA256="$ZIG_AARCH64_SHA256"
    HOST_ZIG_TARGET="aarch64-linux-gnu"
    ;;
  *)
    echo "ERROR: unsupported host architecture: $HOST_ARCH" >&2
    exit 1
    ;;
esac

mkdir -p "$CACHE_DIR/downloads" "$CACHE_DIR/built-$GHOSTTY_VT_VERSION"

CACHE_LOCK_FILE="$CACHE_DIR/.lock"
with_cache_lock() {
  if command -v flock >/dev/null 2>&1; then
    (
      flock -x 9
      "$@"
    ) 9>"$CACHE_LOCK_FILE"
  else
    "$@"
  fi
}

download_verified() {
  local url="$1"
  local dest="$2"
  local expected_sha="$3"
  if [ -f "$dest" ]; then
    local actual_sha
    actual_sha="$(sha256sum "$dest" | awk '{print $1}')"
    if [ "$actual_sha" = "$expected_sha" ]; then
      return 0
    fi
    rm -f "$dest"
  fi
  local tmp="${dest}.tmp.$$"
  echo "==> Downloading $(basename "$dest")"
  curl -fsSL --retry 3 -o "$tmp" "$url"
  local actual_sha
  actual_sha="$(sha256sum "$tmp" | awk '{print $1}')"
  if [ "$actual_sha" != "$expected_sha" ]; then
    rm -f "$tmp"
    echo "ERROR: SHA-256 mismatch for $url (expected $expected_sha, got $actual_sha)" >&2
    exit 1
  fi
  mv "$tmp" "$dest"
}

ZIG_BIN="$CACHE_DIR/zig-$ZIG_VERSION/zig"
ensure_zig() {
  if [ -x "$ZIG_BIN" ] && [ "$("$ZIG_BIN" version 2>/dev/null || true)" = "$ZIG_VERSION" ]; then
    return 0
  fi
  local tarball="$CACHE_DIR/downloads/zig-$ZIG_ARCH-$ZIG_VERSION.tar.xz"
  download_verified \
    "https://ziglang.org/download/$ZIG_VERSION/zig-$ZIG_ARCH-$ZIG_VERSION.tar.xz" \
    "$tarball" \
    "$ZIG_SHA256"
  local stage="$CACHE_DIR/zig-$ZIG_VERSION.tmp.$$"
  rm -rf "$stage"
  mkdir -p "$stage"
  tar -xJf "$tarball" -C "$stage" --strip-components=1
  rm -rf "$CACHE_DIR/zig-$ZIG_VERSION"
  mv "$stage" "$CACHE_DIR/zig-$ZIG_VERSION"
}

SRC_DIR="$CACHE_DIR/src-$GHOSTTY_VT_VERSION"
DEPS_ROOT="$CACHE_DIR/deps-$GHOSTTY_VT_VERSION/libghostty-vt-$GHOSTTY_VT_VERSION-deps"
ensure_ghostty_source() {
  if [ -f "$SRC_DIR/build.zig" ] && [ -d "$DEPS_ROOT" ]; then
    return 0
  fi
  local src_tar="$CACHE_DIR/downloads/libghostty-vt-$GHOSTTY_VT_VERSION.tar.gz"
  local deps_tar="$CACHE_DIR/downloads/libghostty-vt-$GHOSTTY_VT_VERSION-deps.tar.gz"
  download_verified \
    "https://release.files.ghostty.org/libghostty-vt/$GHOSTTY_VT_VERSION/libghostty-vt-$GHOSTTY_VT_VERSION.tar.gz" \
    "$src_tar" \
    "$GHOSTTY_VT_SRC_SHA256"
  download_verified \
    "https://release.files.ghostty.org/libghostty-vt/$GHOSTTY_VT_VERSION/libghostty-vt-$GHOSTTY_VT_VERSION-deps.tar.gz" \
    "$deps_tar" \
    "$GHOSTTY_VT_DEPS_SHA256"

  local src_stage="$SRC_DIR.tmp.$$"
  local deps_stage="$CACHE_DIR/deps-$GHOSTTY_VT_VERSION.tmp.$$"
  rm -rf "$src_stage" "$deps_stage"
  mkdir -p "$src_stage" "$deps_stage"
  tar -xzf "$src_tar" -C "$src_stage" --strip-components=1
  tar -xzf "$deps_tar" -C "$deps_stage"
  rm -rf "$SRC_DIR" "$CACHE_DIR/deps-$GHOSTTY_VT_VERSION"
  mv "$src_stage" "$SRC_DIR"
  mv "$deps_stage" "$CACHE_DIR/deps-$GHOSTTY_VT_VERSION"
}

resolve_jdk_include() {
  local java_home="${JAVA_HOME:-}"
  if [ -z "$java_home" ] && command -v javac >/dev/null 2>&1; then
    local javac_path
    javac_path="$(readlink -f "$(command -v javac)")"
    java_home="$(dirname "$(dirname "$javac_path")")"
  fi
  if [ -z "$java_home" ] || [ ! -f "$java_home/include/jni.h" ]; then
    echo "ERROR: Could not locate JDK jni.h (set JAVA_HOME or install a JDK)." >&2
    exit 1
  fi
  printf '%s\n' "$java_home/include"
}

build_libghostty_static_locked() {
  local zig_target="$1"
  local optimize="$2"
  local ndk_home="${3:-}"
  local prefix="$CACHE_DIR/built-$GHOSTTY_VT_VERSION/$zig_target-$optimize"
  if [ -f "$prefix/lib/libghostty-vt.a" ] && [ -f "$prefix/include/ghostty/vt.h" ]; then
    return 0
  fi
  ensure_zig
  ensure_ghostty_source
  echo "==> Building libghostty-vt $GHOSTTY_VT_VERSION ($zig_target, $optimize)"
  local stage="${prefix}.tmp.$$"
  rm -rf "$stage"
  (
    cd "$SRC_DIR"
    export ZIG_GLOBAL_CACHE_DIR="$CACHE_DIR/zig-global-cache"
    export ZIG_LOCAL_CACHE_DIR="$CACHE_DIR/zig-local-cache-$zig_target"
    if [ -n "$ndk_home" ]; then
      export ANDROID_NDK_HOME="$ndk_home"
      export ANDROID_NDK_ROOT="$ndk_home"
    fi
    "$ZIG_BIN" build \
      -Demit-lib-vt \
      -Dtarget="$zig_target" \
      -Doptimize="$optimize" \
      --system "$DEPS_ROOT" \
      --prefix "$stage"
  )
  rm -rf "$prefix"
  mv "$stage" "$prefix"
}

build_libghostty_static() {
  with_cache_lock build_libghostty_static_locked "$@"
}

JNI_SHA="$(sha256sum "$JNI_SRC" | awk '{print $1}')"

build_android_abi() {
  local abi="$1"
  local zig_target="$2"
  local clang_prefix="$3"
  local ndk_home="$4"

  local lib_prefix="$CACHE_DIR/built-$GHOSTTY_VT_VERSION/$zig_target-ReleaseSmall"
  local out_dir="$OUT_ROOT/jniLibs/$abi"
  local out_so="$out_dir/libghostty_vt_jni.so"
  local stamp_file="$out_dir/.libghostty_vt_jni.stamp"
  local expected_stamp="$GHOSTTY_VT_VERSION:$zig_target:$JNI_SHA"

  if [ -f "$out_so" ] && [ -f "$stamp_file" ] && [ "$(cat "$stamp_file")" = "$expected_stamp" ]; then
    return 0
  fi

  build_libghostty_static "$zig_target" "ReleaseSmall" "$ndk_home"

  local cc="$ndk_home/toolchains/llvm/prebuilt/linux-x86_64/bin/${clang_prefix}${ANDROID_MIN_API}-clang"
  if [ ! -x "$cc" ]; then
    echo "ERROR: NDK compiler not found at $cc" >&2
    exit 1
  fi

  mkdir -p "$out_dir"
  echo "==> Compiling Android JNI library ($abi)"
  "$cc" \
    -shared -fPIC -O2 -Wall -Wextra -Werror \
    -I"$lib_prefix/include" \
    "$JNI_SRC" \
    "$lib_prefix/lib/libghostty-vt.a" \
    -lm -llog \
    -Wl,-z,max-page-size=16384 \
    -Wl,--gc-sections \
    -Wl,--exclude-libs,ALL \
    -s \
    -o "$out_so"
  printf '%s' "$expected_stamp" > "$stamp_file"
}

build_host_jni() {
  local lib_prefix="$CACHE_DIR/built-$GHOSTTY_VT_VERSION/$HOST_ZIG_TARGET-ReleaseFast"
  local out_dir="$OUT_ROOT/host"
  local out_so="$out_dir/libghostty_vt_jni.so"
  local stamp_file="$out_dir/.libghostty_vt_jni.stamp"
  local expected_stamp="$GHOSTTY_VT_VERSION:$HOST_ZIG_TARGET:$JNI_SHA"

  if [ -f "$out_so" ] && [ -f "$stamp_file" ] && [ "$(cat "$stamp_file")" = "$expected_stamp" ]; then
    return 0
  fi

  build_libghostty_static "$HOST_ZIG_TARGET" "ReleaseFast"
  local jdk_inc
  jdk_inc="$(resolve_jdk_include)"

  mkdir -p "$out_dir"
  echo "==> Compiling host Linux JNI library ($HOST_ZIG_TARGET)"
  cc \
    -shared -fPIC -O2 -Wall -Wextra -Werror \
    -I"$jdk_inc" -I"$jdk_inc/linux" \
    -I"$lib_prefix/include" \
    "$JNI_SRC" \
    "$lib_prefix/lib/libghostty-vt.a" \
    -lm -lpthread \
    -o "$out_so"
  printf '%s' "$expected_stamp" > "$stamp_file"
}

if [ "$MODE" = "all" ] || [ "$MODE" = "host" ]; then
  build_host_jni
fi

if [ "$MODE" = "all" ] || [ "$MODE" = "android" ]; then
  NDK_HOME="$(resolve_ndk_home)"
  build_android_abi "arm64-v8a" "aarch64-linux-android" "aarch64-linux-android" "$NDK_HOME"
  build_android_abi "x86_64" "x86_64-linux-android" "x86_64-linux-android" "$NDK_HOME"
  build_android_abi "armeabi-v7a" "arm-linux-androideabi" "armv7a-linux-androideabi" "$NDK_HOME"
fi

echo "==> libghostty-vt JNI artifacts ready under $OUT_ROOT"
