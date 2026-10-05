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

./gradlew :daemon:assembleRelease
mkdir -p "${WORKDIR}/out"
# Vector's release assemble produces the injectable native payload and the
# framework dex; exact output paths follow the upstream layout.
find daemon/build -name 'liblspd.so' -exec cp {} "${WORKDIR}/out/libvector_inject.so" \;
find . -name 'xposed.dex' -exec cp {} "${WORKDIR}/out/xposed.dex" \;
find manager/build/outputs/apk/release -name '*.apk' -exec cp {} "${WORKDIR}/out/vector-manager.apk" \;

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
