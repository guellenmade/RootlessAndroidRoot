#!/usr/bin/env bash
# Builds the Vector injection artifacts from pinned source.
# Usage: build_vector.sh <workdir> <android-api-level> [ndk-dir]
# Produces: <workdir>/out/libvector_inject.so, xposed.dex, vector-manager.apk,
#           artifact-manifest.json
set -euo pipefail

WORKDIR="${1:?usage: build_vector.sh <workdir> <android-api-level> [ndk-dir]}"
API="${2:?usage: build_vector.sh <workdir> <android-api-level> [ndk-dir]}"
NDK_DIR="${3:-${ANDROID_NDK_HOME:-}}"
PINNED_COMMIT="ddeed8c"

mkdir -p "${WORKDIR}"
cd "${WORKDIR}"
if [ ! -d vector-src ]; then
    git clone --recurse-submodules https://github.com/JingMatrix/Vector.git vector-src
fi
cd vector-src
if ! git cat-file -e "${PINNED_COMMIT}"^{commit} 2>/dev/null; then
    git fetch --depth 1 origin "${PINNED_COMMIT}"
fi
git checkout "${PINNED_COMMIT}"
git submodule update --init --recursive

if [ -n "${NDK_DIR}" ] && [ -d "${NDK_DIR}" ]; then
    export ANDROID_NDK_HOME="${NDK_DIR}"
fi
export VECTOR_TARGET_API="${API}"
# Ubuntu/AGP ship Ninja 1.10; C++20 module scanning requires 1.11+. The
# Vector native sources do not use C++20 modules, so disable the scan.
export CMAKE_CXX_SCAN_FOR_MODULES=OFF

# Build via upstream's own packaging task (zygisk/build.gradle.kts zipAll):
# it produces Vector-v*-Release.zip with the complete component set:
#   lib/<abi>/libzygisk.so, framework/vector.dex, manager.apk, daemon.apk,
#   bin/dex2oat, bin/liboat_hook.so, module.prop
# Map to the artifact names the container deploy step expects (ADR-005):
#   libvector_inject.so <- lib/arm64-v8a/libzygisk.so
#   xposed.dex          <- framework/vector.dex
#   vector-manager.apk  <- manager.apk
./gradlew zipAll
mkdir -p "${WORKDIR}/out"
ZIP="$(ls zygisk/release/Vector-v*-Release.zip | head -1)"
[ -n "${ZIP}" ] || { echo "zipAll did not produce a release zip" >&2; exit 1; }
unzip -o "${ZIP}" -d "${WORKDIR}/module-unpack"
cp "${WORKDIR}/module-unpack/lib/arm64-v8a/libzygisk.so" "${WORKDIR}/out/libvector_inject.so"
cp "${WORKDIR}/module-unpack/framework/vector.dex" "${WORKDIR}/out/xposed.dex"
cp "${WORKDIR}/module-unpack/manager.apk" "${WORKDIR}/out/vector-manager.apk"
cp "${WORKDIR}/module-unpack/daemon.apk" "${WORKDIR}/out/vector-daemon.apk" || true
mkdir -p "${WORKDIR}/out/bin"
cp "${WORKDIR}/module-unpack/bin/dex2oat" "${WORKDIR}/out/bin/dex2oat" || true
cp "${WORKDIR}/module-unpack/bin/liboat_hook.so" "${WORKDIR}/out/bin/liboat_hook.so" || true

{
    echo '{'
    echo "  \"vectorCommit\": \"${PINNED_COMMIT}\","
    echo "  \"targetApi\": ${API},"
    echo "  \"lsplant\": \"$(git -C external/lsplant rev-parse HEAD)\","
    echo "  \"dobby\": \"$(git -C external/dobby rev-parse HEAD)\","
    echo "  \"libxposed\": \"$(git -C external/libxposed_api rev-parse HEAD 2>/dev/null || true)\""
    echo '}'
} >"${WORKDIR}/out/artifact-manifest.json"

sha256sum "${WORKDIR}"/out/libvector_inject.so "${WORKDIR}"/out/xposed.dex "${WORKDIR}"/out/vector-manager.apk \
    >"${WORKDIR}/out/SHA256SUMS"
echo "Vector artifacts built in ${WORKDIR}/out"
