#!/usr/bin/env bash
# Builds proot from source for one ABI (Android NDK toolchain).
# Usage: build_proot.sh <workdir> <abi>   (abi: arm64-v8a | armeabi-v7a | x86_64)
#
# proot links libtalloc; Android has no talloc package, so we cross-build it
# from source (talloc.samba.org, LGPL-3.0 — GPL-compatible) and link proot
# statically (mirrors upstream's own release build, which uses -static).
set -euo pipefail

WORKDIR="${1:?usage: build_proot.sh <workdir> <abi>}"
ABI="${2:?usage: build_proot.sh <workdir> <abi>}"
PINNED_COMMIT="25dc6a3134891f98a79f57ce1c2c1b23ff15cad1"  # proot-me/proot v5.5.0 (verified 2026-10-05)
TALLOC_VERSION="2.4.2"

case "${ABI}" in
arm64-v8a) NDK_CC="aarch64-linux-android24-clang" ;;
armeabi-v7a) NDK_CC="armv7a-linux-androideabi24-clang" ;;
x86_64) NDK_CC="x86_64-linux-android24-clang" ;;
*)
    echo "unsupported ABI: ${ABI}" >&2
    exit 1
    ;;
esac

mkdir -p "${WORKDIR}"
cd "${WORKDIR}"
TALLOC_PREFIX="${WORKDIR}/talloc-${ABI}"
export PKG_CONFIG_PATH="${TALLOC_PREFIX}/lib/pkgconfig"

# ---- talloc (static, cross-compiled with the NDK clang) ----
if [ ! -f "${TALLOC_PREFIX}/lib/libtalloc.a" ]; then
    curl -sSLo talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
    tar -xzf talloc.tar.gz
    (
        cd "talloc-${TALLOC_VERSION}"
        # talloc uses waf: --cross-compile avoids running target binaries;
        # CC/LD/AR are taken from the environment.
        CC="${NDK_CC}" LD="${NDK_CC}" AR=llvm-ar \
            ./configure --prefix="${TALLOC_PREFIX}" \
            --cross-compile --cross-execute=/bin/true \
            --disable-python --disable-rpath --disable-symbol-versions \
            --bundled-libraries=ALL
        make -j"$(nproc)"
        make install
    )
    # waf only installs a shared lib; build a static archive from the single
    # talloc.c source with the NDK compiler so -static linking works.
    (
        cd "talloc-${TALLOC_VERSION}"
        # replace.h lives in lib/replace and includes waf-generated
        # config.h from bin/default; compile the bundled replace sources too
        # so every symbol talloc.c references resolves in the static archive.
        "${NDK_CC}" -c talloc.c -I. -Ilib/replace -Ibin/default -o talloc.o
        "${NDK_CC}" -c lib/replace/replace.c -Ilib/replace -Ibin/default -o replace.o
        "${NDK_CC}" -c lib/replace/closefrom.c -Ilib/replace -Ibin/default -o closefrom.o
        llvm-ar rcs "${TALLOC_PREFIX}/lib/libtalloc.a" talloc.o replace.o closefrom.o
    )
fi

# ---- proot (static, cross-compiled) ----
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
    # CC must be a MAKE variable (proot's GNUmakefile defaults to
    # $(CROSS_COMPILE)gcc). LDFLAGS on the command line OVERRIDES the
    # makefile's `LDFLAGS += $(pkg-config --libs talloc)` (command-line
    # variables beat += in makefiles), so -ltalloc must be passed here
    # explicitly or the link fails with undefined talloc_* symbols.
    # Static link mirrors upstream release builds and avoids NDK .so
    # arch mismatches at link time.
    make -C src -j"$(nproc)" V=1 WITHOUT_PYTHON=1 \
        CC="${NDK_CC}" \
        LDFLAGS="-static -L${TALLOC_PREFIX}/lib -ltalloc" \
        PKG_CONFIG="$(command -v pkg-config)"
)

mkdir -p "${WORKDIR}/out/${ABI}"
cp proot-src/src/proot "${WORKDIR}/out/${ABI}/proot"
llvm-strip "${WORKDIR}/out/${ABI}/proot" 2>/dev/null || true
(cd "${WORKDIR}/out/${ABI}" && sha256sum proot > proot.sha256)
echo "built ${WORKDIR}/out/${ABI}/proot"
