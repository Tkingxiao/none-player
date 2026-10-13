#!/bin/bash
# 最小 LGPL FFmpeg：只开 wmv2 与 mpeg4 解码器，静态库安装到 prebuilt/<abi>。
# 不启用 --enable-gpl，不链接 libxvid / avformat。产物供 libnplegacy.so 静态链入。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC_TAR="/mnt/d/Code/Player/ffmpeg_7.1.1.orig.tar.xz"
NDK_ZIP="/mnt/d/Code/Player/android-ndk-r27c-linux.zip"
WORK="${HOME}/src/nplegacy-ffmpeg"
NDK="${WORK}/android-ndk-r27c"
SRC="${WORK}/ffmpeg-7.1.1"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
API=26
mkdir -p "${WORK}"
# configure 还要编一个宿主编译器测试；NDK clang 没有宿主头文件，用系统 gcc。
if [[ -x /usr/bin/gcc ]]; then
  HOST_CC=/usr/bin/gcc
else
  HOST_CC="${WORK}/host-cc"
  cat > "${HOST_CC}" <<EOF
#!/bin/bash
exec "${TOOLCHAIN}/bin/clang" --target=x86_64-linux-gnu "\$@"
EOF
  chmod +x "${HOST_CC}"
fi

mkdir -p "${WORK}"
if [[ ! -x "${TOOLCHAIN}/bin/clang" ]]; then
  echo "extracting NDK..."
  rm -rf "${NDK}"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "${NDK_ZIP}" -d "${WORK}"
  else
    python3 -c 'import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "${NDK_ZIP}" "${WORK}"
  fi
fi
if [[ ! -f "${SRC}/configure" ]]; then
  echo "extracting FFmpeg..."
  rm -rf "${SRC}"
  tar -xf "${SRC_TAR}" -C "${WORK}"
fi

build_one() {
  local abi="$1" arch="$2" cpu="$3" triple="$4"
  local prefix="${WORK}/install/${abi}"
  local builddir="${WORK}/build-${abi}"
  local cc="${TOOLCHAIN}/bin/${triple}${API}-clang"
  local cpu_arg=()
  # aarch64 的 clang 不接受 -mcpu=aarch64；空 cpu 就不要传，交给目标三元组。
  if [[ -n "${cpu}" ]]; then
    cpu_arg=(--cpu="${cpu}")
  fi
  echo "===== configure ${abi} ====="
  rm -rf "${builddir}" "${prefix}"
  mkdir -p "${builddir}"
  (
    cd "${builddir}"
    "${SRC}/configure" \
      --prefix="${prefix}" \
      --target-os=android \
      --arch="${arch}" \
      "${cpu_arg[@]}" \
      --enable-cross-compile \
      --sysroot="${TOOLCHAIN}/sysroot" \
      --cc="${cc}" \
      --cxx="${TOOLCHAIN}/bin/${triple}${API}-clang++" \
      --ld="${cc}" \
      --ar="${TOOLCHAIN}/bin/llvm-ar" \
      --nm="${TOOLCHAIN}/bin/llvm-nm" \
      --ranlib="${TOOLCHAIN}/bin/llvm-ranlib" \
      --strip="${TOOLCHAIN}/bin/llvm-strip" \
      --host-cc="${HOST_CC}" \
      --disable-shared \
      --enable-static \
      --enable-pic \
      --disable-programs \
      --disable-doc \
      --disable-debug \
      --disable-avdevice \
      --disable-avformat \
      --disable-avfilter \
      --disable-swresample \
      --disable-swscale \
      --disable-postproc \
      --disable-network \
      --disable-everything \
      --disable-asm \
      --disable-inline-asm \
      --disable-runtime-cpudetect \
      --disable-zlib \
      --disable-iconv \
      --disable-lzma \
      --enable-small \
      --enable-decoder=wmv2,mpeg4 \
      --enable-parser=mpeg4video,mpegvideo \
      --extra-cflags="-Os -fvisibility=hidden -ffunction-sections -fdata-sections -fPIC -DANDROID" \
      --extra-ldflags="-Wl,-z,max-page-size=16384 -Wl,--gc-sections"
    echo "===== license ${abi} ====="
    grep -E "License|license" config.h | head -20 || true
    make -j"$(nproc)"
    make install
  )
  local dest="${ROOT}/prebuilt/${abi}"
  rm -rf "${dest}"
  mkdir -p "${dest}/lib" "${dest}/include"
  cp -a "${prefix}/include/." "${dest}/include/"
  cp -a "${prefix}/lib/libavcodec.a" "${prefix}/lib/libavutil.a" "${dest}/lib/"
  echo "===== sizes ${abi} ====="
  ls -lh "${dest}/lib/"*.a
}

build_one arm64-v8a   aarch64 ""       "aarch64-linux-android"
build_one armeabi-v7a arm     armv7-a "armv7a-linux-androideabi"
build_one x86         x86     ""      "i686-linux-android"
build_one x86_64      x86_64  ""      "x86_64-linux-android"

echo "DONE"
