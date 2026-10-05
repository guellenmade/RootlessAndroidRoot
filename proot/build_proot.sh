#!/usr/bin/env bash
# Builds proot from source for one ABI (Android NDK toolchain).
# Usage: build_proot.sh <workdir> <abi>   (abi: arm64-v8a | armeabi-v7a | x86_64)
#
# proot needs libtalloc; upstream CI builds it from source, so do we.
# Talloc: https://talloc.samba.org (LGPL-3.0, GPL-compatible).
set -euo pipefail

WORKDIR="${1:?usage: build_proot.sh <workdir> <abi>}"
ABI="${2:?usage: build_proot.sh <workdir> <abi>}"
PINNED_COMMIT="25dc6a3134891f98a79f57ce1c2c1b23ff15cad1"  # proot-me/proot v5.5.0 (verified 2026-10-05)
TALLOC_VERSION="2.4.2"

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
export CC
export AR=llvm-ar
export STRIP=llvm-strip
TALLOC_PREFIX="${WORKDIR}/talloc-${ABI}"
export PKG_CONFIG_PATH="${TALLOC_PREFIX}/lib/pkgconfig"

# ---- talloc (static, cross-compiled with the NDK clang) ----
if [ ! -f "${TALLOC_PREFIX}/lib/libtalloc.a" ]; then
    curl -sSLo talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
    tar -xzf talloc.tar.gz
    (
        cd "talloc-${TALLOC_VERSION}"
        # talloc's waf configure honors CC from the environment.
        ./configure --prefix="${TALLOC_PREFIX}" \
            --cross-compile --cross-execute="true" \
            --disable-python --disable-rpath --disable-symbol-versions \
            --bundled-libraries=ALL \
            CC="${CC}"
        make -j"$(nproc)"
        make install
    )
fi

# ---- proot ----
if [ ! -d proot-src/.git ]; then
    git clone https://github.com/proot-me/proot.git proot-src
fi
(
    cd proot-src
    if ! git cat-file -e "${PINNED_COMMIT}^{commit}" 2>/dev/null; then
        git fetch --depth 1 origin "${PINNED_COMMIT}"
    fi
    git checkout "${PINNED_COMMIT}"
    git submodule update --init --recursive
    make -C src clean >/dev/null 2>&1 || true
    # Note: do NOT override CPPFLAGS/LDFLAGS on the command line — that would
    # replace proot's own default include paths and break internal headers.
    # talloc flags are picked up via pkg-config (PKG_CONFIG_PATH is exported).
    make -C src -j"$(nproc)" V=1 WITHOUT_PYTHON=1
)

mkdir -p "${WORKDIR}/out/${ABI}"
cp proot-src/src/proot "${WORKDIR}/out/${ABI}/proot"
"${STRIP}" "${WORKDIR}/out/${ABI}/proot" 2>/dev/null || true
(cd "${WORKDIR}/out/${ABI}" && sha256sum proot > proot.sha256)
echo "built ${WORKDIR}/out/${ABI}/proot"
