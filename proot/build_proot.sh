#!/usr/bin/env bash
# Builds proot from source for one ABI.
# Usage: build_proot.sh <workdir> <abi>   (abi: arm64-v8a | armeabi-v7a | x86_64)
set -euo pipefail

WORKDIR="${1:?usage: build_proot.sh <workdir> <abi>}"
ABI="${2:?usage: build_proot.sh <workdir> <abi>}"
PINNED_COMMIT="cc03b4b4a302bea04a35a6e1c1b6bbf8a1dd368e"  # proot-me/proot master

case "${ABI}" in
arm64-v8a) TRIPLE="aarch64-linux-android" ;;
armeabi-v7a) TRIPLE="arm-linux-androideabi" ;;
x86_64) TRIPLE="x86_64-linux-android" ;;
*) echo "unsupported ABI: ${ABI}" >&2; exit 1 ;;
esac

cd "${WORKDIR}"
if [ ! -d proot-src ]; then
    git clone https://github.com/proot-me/proot.git proot-src
fi
cd proot-src
git checkout "${PINNED_COMMIT}"
git submodule update --init --recursive

make -j"$(nproc)" V=1
# proot builds a static-ish binary under ./proot
mkdir -p "${WORKDIR}/out/${ABI}"
cp proot "${WORKDIR}/out/${ABI}/proot"
"${TRIPLE}-strip" "${WORKDIR}/out/${ABI}/proot" 2>/dev/null || true
sha256sum "${WORKDIR}/out/${ABI}/proot" >"${WORKDIR}/out/${ABI}/proot.sha256"
echo "built ${WORKDIR}/out/${ABI}/proot"
