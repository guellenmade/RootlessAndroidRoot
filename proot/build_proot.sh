#!/usr/bin/env bash
# Builds proot from source for one ABI (Android NDK toolchain).
# Usage: build_proot.sh <workdir> <abi>   (abi: arm64-v8a | armeabi-v7a | x86_64)
set -euo pipefail

WORKDIR="${1:?usage: build_proot.sh <workdir> <abi>}"
ABI="${2:?usage: build_proot.sh <workdir> <abi>}"
PINNED_COMMIT="cc03b4b4a302bea04a35a6e1c1b6bbf8a1dd368e"  # proot-me/proot master

case "${ABI}" in
arm64-v8a) CC="aarch64-linux-android24-clang" ;;
armeabi-v7a) CC="armv7a-linux-androideabi24-clang" ;;
x86_64) CC="x86_64-linux-android24-clang" ;;
*)
    echo "unsupported ABI: ${ABI}" >&2
    exit 1
    ;;
esac

mkdir -p "${WORKDIR}"
cd "${WORKDIR}"

if [ ! -d proot-src ]; then
    git clone --depth 1 https://github.com/proot-me/proot.git proot-src
fi
cd proot-src
git checkout "${PINNED_COMMIT}" 2>/dev/null || git fetch --depth 1 origin "${PINNED_COMMIT}" && git checkout "${PINNED_COMMIT}"
git submodule update --init --recursive

# proot needs a C compiler + libtalloc; build with the NDK clang and static
# talloc sources bundled via proot's own submodules.
export CC="${CC}"
export AR=llvm-ar
export STRIP=llvm-strip
make -j"$(nproc)" V=1

mkdir -p "${WORKDIR}/out/${ABI}"
cp proot "${WORKDIR}/out/${ABI}/proot"
"${STRIP}" "${WORKDIR}/out/${ABI}/proot" 2>/dev/null || true
(cd "${WORKDIR}/out/${ABI}" && sha256sum proot > proot.sha256)
echo "built ${WORKDIR}/out/${ABI}/proot"
